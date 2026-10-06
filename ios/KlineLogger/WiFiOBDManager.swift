//
//  WiFiOBDManager.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//
//  Drives the Veepeak's ELM327 Wi-Fi interface in raw/custom-header mode to
//  speak Kawasaki's KWP2000-over-K-line dialect directly (StartCommunication,
//  StartDiagnosticSession, ReadDataByLocalIdentifier). Session sequencing and
//  constants are ported from drowland341/kline-logger's kline.py, which was
//  reverse-engineered against this exact ECU family using a raw K-line cable.
//  Here the ELM327 chip handles wake-up timing, framing, and checksums; we
//  just set its header to the KWP2000 address and send/parse service bytes.

import Foundation
import Network
import Observation

@Observable
final class WiFiOBDManager {
    private(set) var phase: ConnectionPhase = .idle
    private(set) var handshakeLog: [String] = []
    private(set) var pidReadings: [PIDReading] = []
    private(set) var scanResults: [ScannedPID] = []
    private(set) var isScanningPIDs = false
    private(set) var watchSamples: [WatchedPIDSample] = []
    private(set) var isWatching = false

    /// Ground-truth raw bytes for every PID read during normal polling — both the defined
    /// channels (in pids.json) and anything in `extraLoggedPIDs` below. Research Mode and the
    /// Speed Comparison card both read from this rather than re-deriving formulas.
    private(set) var rawValuesByPID: [UInt8: [UInt8]] = [:]

    /// Unconfirmed "might be the paddlewheel speed sensor" PIDs from the 0x00-0xFF scan (0x0D and
    /// 0x6B read identically at rest). Always polled at low priority so Live Data can show them
    /// next to GPS speed for comparison, without slowing down the fast PIDs.
    private let speedCandidatePIDs: [UInt8] = [0x0D, 0x6B]

    /// Extra PIDs Research Mode wants logged alongside the known channels, also polled at low
    /// priority. Set from outside (e.g. ResearchLogger); empty when Research Mode isn't active.
    var extraLoggedPIDs: [UInt8] = []

    private var connection: NWConnection?
    private var connectTimeoutTask: Task<Void, Never>?
    private var workLoopTask: Task<Void, Never>?
    private var receiveBuffer = Data()

    private let host: NWEndpoint.Host = "192.168.0.10"
    private let port: NWEndpoint.Port = 35000
    private let connectTimeout: Duration = .seconds(30)
    private let adapterDisplayName = "OBDCheck Wi-Fi"

    private let testerAddress: UInt8 = 0xF1
    private let ecuAddressCandidates: [UInt8] = [0x11, 0x28]
    private var ecuAddress: UInt8 = 0x11
    private let requestGap: Duration = .milliseconds(55)
    private var lastRequestAt: ContinuousClock.Instant?
    private let clock = ContinuousClock()

    private var compiledFormulas: [UInt8: CompiledFormula] = [:]
    private var sweepCounter = 0
    private var sweepPlan: [UInt8] = []
    private var consecutiveFailures = 0
    private let maxConsecutiveFailures = 3

    private enum Mode {
        case polling
        case scanning(remaining: [UInt8])
        case watching(pids: [UInt8])
    }
    private var mode: Mode = .polling
    private var watchIndex = 0

    init() {
        for def in PIDLibrary.all {
            compiledFormulas[def.pid] = try? CompiledFormula(def.formula)
        }
    }

    // MARK: - Connect / disconnect

    func connect() {
        handshakeLog = []
        pidReadings = []
        scanResults = []
        watchSamples = []
        isWatching = false
        rawValuesByPID = [:]
        extraLoggedPIDs = []
        sweepCounter = 0
        sweepPlan = []
        consecutiveFailures = 0
        mode = .polling
        phase = .connecting(adapterDisplayName)

        let conn = NWConnection(host: host, port: port, using: .tcp)
        connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.connectTimeoutTask?.cancel()
                Task { await self.establishSession() }
            case .failed(let error):
                self.connectTimeoutTask?.cancel()
                self.phase = .failed("Couldn't reach the adapter at \(self.host):\(self.port): \(error.localizedDescription). Make sure your phone is joined to the adapter's Wi-Fi network (usually named something like \"OBDCHECK\") in Settings.")
            default:
                break
            }
        }
        conn.start(queue: .main)
        armConnectTimeout()
    }

    func cancel() {
        connectTimeoutTask?.cancel()
        workLoopTask?.cancel()
        connection?.cancel()
        connection = nil
        phase = .idle
    }

    func disconnect() {
        workLoopTask?.cancel()
        workLoopTask = nil
        connection?.cancel()
        connection = nil
        handshakeLog = []
        pidReadings = []
        scanResults = []
        watchSamples = []
        isWatching = false
        rawValuesByPID = [:]
        extraLoggedPIDs = []
        phase = .idle
    }

    private func armConnectTimeout() {
        connectTimeoutTask?.cancel()
        connectTimeoutTask = Task { [weak self, connectTimeout] in
            try? await Task.sleep(for: connectTimeout)
            guard let self, !Task.isCancelled else { return }
            if case .connecting = self.phase {
                self.connection?.cancel()
                self.phase = .failed("Timed out reaching the adapter's Wi-Fi network. Make sure your phone is joined to the adapter's Wi-Fi (usually named something like \"OBDCHECK\") before connecting.")
            }
        }
    }

    // MARK: - Session establishment

    /// Protocol numbers to try, in order: ISO14230-4 KWP fast init, KWP 5-baud init, then ISO9141-2.
    /// Kawasaki's wake-up pattern matches KWP fast init, but cheap ELM327 clones vary in how well
    /// they implement less-common protocols, so we fall back rather than betting on one.
    private let protocolCandidates: [(number: String, label: String)] = [
        ("5", "ISO 14230-4 KWP (fast init)"),
        ("4", "ISO 14230-4 KWP (5-baud init)"),
        ("3", "ISO 9141-2"),
    ]

    private func establishSession() async {
        do {
            try await send("ATZ")
            try await send("ATE0")
            try await send("ATL0")
            try await send("ATH0")
            try await send("ATST19") // cap the adapter's per-request wait at ~100ms instead of its slower default
        } catch {
            phase = .failed("Adapter didn't respond as expected: \(error.localizedDescription)")
            return
        }

        // The ELM327's own bus-init (wake-up + key-byte exchange) already performs KWP2000's
        // StartCommunication internally, so the first real message we send IS StartDiagnosticSession —
        // sending an explicit "81" afterward is redundant and gets refused by the ECU since it's
        // already mid-session.
        var connectedAddress: UInt8?
        protocolLoop: for candidate in protocolCandidates {
            do {
                try await send("ATSP\(candidate.number)")
            } catch {
                handshakeLog.append("ATSP\(candidate.number) (\(candidate.label)) → \(error.localizedDescription)")
                continue
            }
            for address in ecuAddressCandidates {
                do {
                    try await setHeader(target: address)
                    let response = try await send("1080")
                    let bytes = try Self.parseHexBytes(response)
                    connectedAddress = address
                    if bytes.first == 0x50 {
                        handshakeLog.append("StartDiagnosticSession via \(candidate.label), ECU 0x\(String(format: "%02X", address)) → accepted")
                    } else if bytes.first == 0x7F {
                        let nrc: UInt8 = bytes.count > 2 ? bytes[2] : 0
                        handshakeLog.append("StartDiagnosticSession via \(candidate.label), ECU 0x\(String(format: "%02X", address)) → refused (\(NegativeResponseError.nrcNames[nrc] ?? "unknown reason")), continuing anyway")
                    } else {
                        handshakeLog.append("StartDiagnosticSession via \(candidate.label), ECU 0x\(String(format: "%02X", address)) → bus woke up but unexpected reply \(Self.hexString(bytes))")
                    }
                    break protocolLoop
                } catch {
                    handshakeLog.append("StartCommunication via \(candidate.label), ECU 0x\(String(format: "%02X", address)) → \(error.localizedDescription)")
                }
            }
        }

        guard let connectedAddress else {
            phase = .failed("No response from the ECU after trying \(protocolCandidates.count) protocol variants and both known ECU addresses. Make sure the ignition is on (or in accessory mode) and try again. Check Nearby/Technical details below for exactly what the adapter said.")
            return
        }
        ecuAddress = connectedAddress

        phase = .connected(adapterDisplayName)
        startWorkLoop()
    }

    private func setHeader(target: UInt8) async throws {
        try await send("ATSH81\(String(format: "%02X", target))\(String(format: "%02X", testerAddress))")
    }

    // MARK: - Work loop (polling / scanning; exactly one request in flight at a time)

    private func startWorkLoop() {
        workLoopTask?.cancel()
        workLoopTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                await self.workLoopStep()
            }
        }
    }

    private func workLoopStep() async {
        switch mode {
        case .polling:
            await pollStep()
        case .scanning(var remaining):
            guard !remaining.isEmpty else {
                mode = .polling
                isScanningPIDs = false
                return
            }
            let pid = remaining.removeFirst()
            mode = .scanning(remaining: remaining)
            await scanStep(pid: pid)
        case .watching(let pids):
            await watchStep(pids: pids)
        }
    }

    private func pollStep() async {
        if sweepPlan.isEmpty {
            sweepCounter += 1
            sweepPlan = PIDLibrary.all
                .filter { $0.enabled && (sweepCounter - 1) % $0.every == 0 }
                .map(\.pid)
            if sweepCounter % 3 == 0 {
                sweepPlan.append(contentsOf: speedCandidatePIDs)
                sweepPlan.append(contentsOf: extraLoggedPIDs.filter { !speedCandidatePIDs.contains($0) })
            }
            if sweepPlan.isEmpty {
                try? await Task.sleep(for: .milliseconds(100))
                return
            }
        }
        let pid = sweepPlan.removeFirst()

        guard let def = PIDLibrary.all.first(where: { $0.pid == pid }) else {
            if let data = try? await readLocalID(pid) {
                rawValuesByPID[pid] = data
            }
            return
        }

        do {
            let data = try await readLocalID(pid)
            consecutiveFailures = 0
            rawValuesByPID[pid] = data
            updateReading(for: def, data: data, error: nil)
        } catch let error as NegativeResponseError {
            consecutiveFailures = 0
            updateReading(for: def, data: nil, error: "ECU refused: \(error.nrcName)")
        } catch {
            consecutiveFailures += 1
            updateReading(for: def, data: nil, error: error.localizedDescription)
            if consecutiveFailures >= maxConsecutiveFailures {
                workLoopTask?.cancel()
                connection?.cancel()
                connection = nil
                phase = .failed("Lost contact with the ECU. If the ski shut itself off, cycle the key and reconnect.")
            }
        }
    }

    private func scanStep(pid: UInt8) async {
        let address = String(format: "0x%02X", pid)
        let name = PIDLibrary.name(forPID: pid)
        do {
            let data = try await readLocalID(pid)
            let rawHex = Self.hexString(data)
            let label = name ?? "\(data.count) byte\(data.count == 1 ? "" : "s")"
            scanResults.append(ScannedPID(address: address, name: label, didRespond: true, rawValue: rawHex))
        } catch {
            scanResults.append(ScannedPID(address: address, name: name, didRespond: false))
        }
    }

    func startScan(range: ClosedRange<UInt8> = 0x00...0xFF) {
        scanResults = []
        isScanningPIDs = true
        mode = .scanning(remaining: Array(range))
    }

    func stopScan() {
        mode = .polling
        isScanningPIDs = false
    }

    /// "Watch" mode: continuously re-reads just a handful of chosen PIDs (fast cycle, since there
    /// are so few) and tracks low/high so you can wiggle a sensor and see which address moves.
    func startWatching(pids: [UInt8]) {
        watchSamples = []
        watchIndex = 0
        isWatching = true
        mode = .watching(pids: pids)
    }

    func stopWatching() {
        mode = .polling
        isWatching = false
    }

    private func watchStep(pids: [UInt8]) async {
        guard !pids.isEmpty else { return }
        let pid = pids[watchIndex % pids.count]
        watchIndex += 1
        do {
            let data = try await readLocalID(pid)
            let rawHex = Self.hexString(data)
            let rawInt = data.reduce(0) { ($0 << 8) | Int($1) }
            if let index = watchSamples.firstIndex(where: { $0.id == pid }) {
                watchSamples[index].rawValue = rawHex
                watchSamples[index].low = min(watchSamples[index].low, rawInt)
                watchSamples[index].high = max(watchSamples[index].high, rawInt)
            } else {
                let address = String(format: "0x%02X", pid)
                watchSamples.append(WatchedPIDSample(id: pid, address: address, name: PIDLibrary.name(forPID: pid), rawValue: rawHex, low: rawInt, high: rawInt))
            }
        } catch {
            // Transient refusal/timeout: leave the last known sample on screen rather than thrashing.
        }
    }

    // MARK: - Reading updates

    private func updateReading(for def: PIDDefinition, data: [UInt8]?, error: String?) {
        let shortCode = String(format: "0x%02X", def.pid)
        let reading: PIDReading
        if let data, error == nil {
            if let formula = compiledFormulas[def.pid] {
                do {
                    let vars = PIDByteDecoding.variables(from: data)
                    let value = try formula.evaluate(variables: vars)
                    reading = PIDReading(name: def.name, shortCode: shortCode, value: Self.formatValue(value), unit: def.units, icon: Self.icon(for: def), isFault: false, faultReason: nil)
                } catch {
                    reading = PIDReading(name: def.name, shortCode: shortCode, value: "—", unit: "", icon: Self.icon(for: def), isFault: true, faultReason: "formula error: \(error.localizedDescription)")
                }
            } else {
                reading = PIDReading(name: def.name, shortCode: shortCode, value: "—", unit: "", icon: Self.icon(for: def), isFault: true, faultReason: "invalid formula")
            }
        } else {
            reading = PIDReading(name: def.name, shortCode: shortCode, value: "—", unit: "", icon: Self.icon(for: def), isFault: true, faultReason: error ?? "No data")
        }

        if let index = pidReadings.firstIndex(where: { $0.shortCode == shortCode }) {
            pidReadings[index] = reading
        } else {
            pidReadings.append(reading)
        }
    }

    private static func icon(for def: PIDDefinition) -> String {
        let u = def.units.lowercased()
        if u.contains("rpm") { return "gauge.with.dots.needle.67percent" }
        if u == "%" { return "gauge.medium" }
        if u.contains("kpa") || u.contains("psi") { return "wind" }
        if u.contains("°") { return "thermometer" }
        if u == "v" { return "bolt.fill" }
        return "waveform.path.ecg"
    }

    private static func formatValue(_ value: Double) -> String {
        if value.truncatingRemainder(dividingBy: 1) == 0, abs(value) < 1_000_000 {
            return String(Int(value))
        }
        return String(format: "%.2f", value)
    }

    // MARK: - Kawasaki KWP2000 requests

    private func readLocalID(_ pid: UInt8) async throws -> [UInt8] {
        let response = try await send("21" + String(format: "%02X", pid))
        let bytes = try Self.parseHexBytes(response)
        guard !bytes.isEmpty else { throw WiFiOBDError.malformedResponse(response) }
        if bytes[0] == 0x7F {
            let sid: UInt8 = bytes.count > 1 ? bytes[1] : 0x21
            let nrc: UInt8 = bytes.count > 2 ? bytes[2] : 0
            throw NegativeResponseError(sid: sid, nrc: nrc)
        }
        guard bytes.count >= 2, bytes[0] == 0x61, bytes[1] == pid else {
            throw WiFiOBDError.malformedResponse(response)
        }
        return Array(bytes[2...])
    }

    // MARK: - Low-level AT/TCP transport

    @discardableResult
    private func send(_ command: String) async throws -> String {
        guard let connection else { throw WiFiOBDError.notConnected }

        if let lastRequestAt {
            let elapsed = clock.now - lastRequestAt
            if elapsed < requestGap {
                try? await Task.sleep(for: requestGap - elapsed)
            }
        }

        let data = (command + "\r").data(using: .ascii) ?? Data()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            })
        }

        let response = try await receiveUntilPrompt()
        lastRequestAt = clock.now
        if command.uppercased().hasPrefix("AT") {
            handshakeLog.append("\(command) → \(response)")
        }
        return response
    }

    private func receiveUntilPrompt() async throws -> String {
        while true {
            if let promptRange = receiveBuffer.range(of: Data([0x3E])) {
                let messageData = receiveBuffer[..<promptRange.lowerBound]
                receiveBuffer.removeSubrange(..<promptRange.upperBound)
                let text = String(data: messageData, encoding: .ascii) ?? ""
                return text.trimmingCharacters(in: .whitespacesAndNewlines)
            }

            guard let connection else { throw WiFiOBDError.notConnected }
            let chunk = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, _, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(returning: data ?? Data())
                    }
                }
            }
            if chunk.isEmpty { throw WiFiOBDError.connectionClosed }
            receiveBuffer.append(chunk)
        }
    }

    // MARK: - Hex parsing

    private static func hexString(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02X", $0) }.joined(separator: " ")
    }

    private static func parseHexBytes(_ text: String) throws -> [UInt8] {
        var remaining = Substring(text)

        // K-line protocols (ISO9141/KWP) make ELM327 report "BUS INIT: OK" or "BUS INIT: ...ERROR"
        // ahead of the actual reply. Strip that status line rather than treating "BUS INIT" itself
        // as a failure — only "...ERROR" means the physical wake-up failed.
        if let busInitRange = remaining.range(of: "BUS INIT:", options: .caseInsensitive) {
            let after = remaining[busInitRange.upperBound...]
            if after.range(of: "ERROR", options: .caseInsensitive) != nil {
                throw WiFiOBDError.elmReported(text.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            if let okRange = after.range(of: "OK", options: .caseInsensitive) {
                remaining = after[okRange.upperBound...]
            } else {
                remaining = after
            }
        }

        let upper = remaining.uppercased()
        let knownErrors = ["NO DATA", "UNABLE TO CONNECT", "ERROR", "STOPPED", "CAN ERROR", "?", "SEARCHING"]
        for marker in knownErrors where upper.contains(marker) {
            throw WiFiOBDError.elmReported(text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        let hexOnly = upper.filter { $0.isHexDigit }
        guard !hexOnly.isEmpty, hexOnly.count % 2 == 0 else {
            throw WiFiOBDError.malformedResponse(text)
        }
        var bytes: [UInt8] = []
        let chars = Array(hexOnly)
        var i = 0
        while i < chars.count {
            let pair = String(chars[i...i + 1])
            guard let byte = UInt8(pair, radix: 16) else { throw WiFiOBDError.malformedResponse(text) }
            bytes.append(byte)
            i += 2
        }
        return bytes
    }
}

struct NegativeResponseError: Error, LocalizedError {
    let sid: UInt8
    let nrc: UInt8

    static let nrcNames: [UInt8: String] = [
        0x10: "general reject",
        0x11: "service not supported",
        0x12: "sub-function not supported / invalid format",
        0x21: "busy, repeat request",
        0x22: "conditions not correct",
        0x31: "request out of range",
        0x33: "security access denied",
        0x35: "invalid key",
        0x78: "response pending",
        0x80: "not supported in active session",
    ]

    var nrcName: String { Self.nrcNames[nrc] ?? "unknown reason" }

    var errorDescription: String? {
        "ECU refused service 0x\(String(format: "%02X", sid)): \(nrcName) (0x\(String(format: "%02X", nrc)))"
    }
}

enum WiFiOBDError: LocalizedError {
    case notConnected
    case connectionClosed
    case elmReported(String)
    case malformedResponse(String)

    var errorDescription: String? {
        switch self {
        case .notConnected: return "not connected"
        case .connectionClosed: return "connection closed unexpectedly"
        case .elmReported(let text): return text
        case .malformedResponse(let text): return "unexpected response: \(text)"
        }
    }
}
