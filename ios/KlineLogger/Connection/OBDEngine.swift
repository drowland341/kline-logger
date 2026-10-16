//
//  OBDEngine.swift
//  KlineLogger
//
//  Transport-agnostic half of the Kawasaki KWP2000-over-K-line dialect
//  (StartCommunication, StartDiagnosticSession, ReadDataByLocalIdentifier) driven
//  through an ELM327's AT-command interface. Originally lived inside WiFiOBDManager;
//  extracted so the exact same session/polling/scanning/watching/research-logging
//  logic works unmodified over any ELM327Link — Wi-Fi (NWConnection) or Bluetooth
//  (CBPeripheral characteristics) — instead of drifting between two copies.
//
//  Session sequencing and constants are ported from drowland341/kline-logger's
//  kline.py, which was reverse-engineered against this exact ECU family using a raw
//  K-line cable. Here the ELM327 chip handles wake-up timing, framing, and
//  checksums; this engine just sets its header to the KWP2000 address and sends/
//  parses service bytes.

import Foundation
import Observation

@Observable
final class OBDEngine {
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

    /// 0x0D and 0x6B read identically at rest — originally suspected as the paddlewheel speed
    /// sensor, but a 2026-10-13 water test ruled that out: both sat completely flat through a ride
    /// that swung from 0 to 42.8 mph repeatedly, so they're some other duplicated status/config
    /// byte, not speed. Still polled at low priority so Live Data can show them next to GPS speed
    /// in case that changes on a different ECU, without slowing down the fast PIDs.
    private let speedCandidatePIDs: [UInt8] = [0x0D, 0x6B]

    /// Extra PIDs Research Mode wants logged alongside the known channels, also polled at low
    /// priority. Set from outside (e.g. ResearchLogger); empty when Research Mode isn't active.
    var extraLoggedPIDs: [UInt8] = []

    /// Best-effort, unverified: Kawasaki's DTC-read service isn't documented anywhere for this
    /// ECU family (unlike ReadDataByLocalIdentifier), so this tries the standard KWP2000 DTC
    /// services and surfaces whatever the ECU actually says rather than guessing silently.
    private(set) var troubleCodes: [DiagnosticTroubleCode] = []
    private(set) var isReadingCodes = false
    private(set) var troubleCodesError: String?

    /// Set by ResearchLogger while a log is running. Along with isScanningPIDs/isWatching/
    /// isReadingCodes, feeds `isBusy` so views can grey out actions that would otherwise
    /// contend for the bus with whatever's already in control of it.
    var isResearchLogging = false

    /// True while anything other than plain background polling owns the bus — a scan, a
    /// watch session, a trouble-code read, or a research log. Views use this to disable
    /// buttons that would start a second, conflicting activity: scanning and watching are
    /// already serialized through `mode` below, but a research log doesn't touch `mode` at
    /// all (it just rides along on normal polling), so without this flag it's possible to
    /// e.g. start a trouble-code read mid-log and get a confusing gap in the CSV, or start a
    /// scan that starves the log of fresh readings for its ~15-20s duration.
    var isBusy: Bool {
        isScanningPIDs || isWatching || isReadingCodes || isResearchLogging
    }

    private weak var link: ELM327Link?
    private var workLoopTask: Task<Void, Never>?
    private var onSessionFailed: ((String) -> Void)?
    private var receiveBuffer = Data()

    private let testerAddress: UInt8 = 0xF1
    private var ecuAddress: UInt8 = 0x11
    private let requestGap: Duration = .milliseconds(55)
    private var lastRequestAt: ContinuousClock.Instant?
    private let clock = ContinuousClock()

    private var compiledFormulas: [UInt8: CompiledFormula] = [:]
    private var sweepCounter = 0
    private var sweepPlan: [UInt8] = []
    private var consecutiveFailures = 0
    private let maxConsecutiveFailures = 3

    /// Fires once a `startScan` run finishes on its own (not via manual `stopScan`), so a caller
    /// can chain straight into another action — e.g. Research Mode's one-tap "scan everything,
    /// then log everything that responded" flow — without polling `isScanningPIDs` from the view.
    private var scanCompletion: (() -> Void)?

    /// Alternates every other work-loop tick between a direct re-read of the priority
    /// PID(s) (Engine RPM) and the normal round-robin sweep, so RPM gets roughly half
    /// of all bus transactions instead of splitting evenly with every other "every=1"
    /// channel. This is the practical ceiling given the ~50ms Kawasaki bus floor (see
    /// README Protocol notes) — we can't poll faster than the ECU tolerates, but we can
    /// stop spending half of RPM's bus time on Throttle/Intake Pressure.
    private var priorityTick = false
    private var priorityIndex = 0

    private enum Mode {
        case polling
        case scanning(remaining: [UInt8])
        case watching(pids: [UInt8])
        case readingCodes
    }
    private var mode: Mode = .polling
    private var watchIndex = 0
    private var codesCompletion: (() -> Void)?

    init() {
        for def in PIDLibrary.all {
            compiledFormulas[def.pid] = try? CompiledFormula(def.formula)
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

    /// Entry point for a transport manager once its physical link (TCP socket, BLE
    /// characteristic pair) is open and ready to carry AT commands.
    func start(link: ELM327Link, ecuAddressCandidates: [UInt8] = [0x11, 0x28], onEstablished: @escaping () -> Void, onFailed: @escaping (String) -> Void) {
        reset()
        self.link = link
        self.onSessionFailed = onFailed
        Task { [weak self] in
            await self?.establishSession(ecuAddressCandidates: ecuAddressCandidates, onEstablished: onEstablished)
        }
    }

    func stop() {
        workLoopTask?.cancel()
        workLoopTask = nil
        link = nil
        onSessionFailed = nil
        reset()
    }

    private func reset() {
        handshakeLog = []
        pidReadings = []
        scanResults = []
        watchSamples = []
        isWatching = false
        isScanningPIDs = false
        rawValuesByPID = [:]
        extraLoggedPIDs = []
        troubleCodes = []
        isReadingCodes = false
        troubleCodesError = nil
        isResearchLogging = false
        codesCompletion = nil
        receiveBuffer = Data()
        sweepCounter = 0
        sweepPlan = []
        consecutiveFailures = 0
        scanCompletion = nil
        priorityTick = false
        priorityIndex = 0
        mode = .polling
        lastRequestAt = nil
    }

    private func establishSession(ecuAddressCandidates: [UInt8], onEstablished: @escaping () -> Void) async {
        do {
            try await send("ATZ")
            try await send("ATE0")
            try await send("ATL0")
            try await send("ATH0")
            try await send("ATST19") // cap the adapter's per-request wait at ~100ms instead of its slower default
        } catch {
            onSessionFailed?("Adapter didn't respond as expected: \(error.localizedDescription)")
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
            onSessionFailed?("No response from the ECU after trying \(protocolCandidates.count) protocol variants and \(ecuAddressCandidates.count) known ECU addresses. Make sure the ignition is on (or in accessory mode) and try again. Check Nearby/Technical details below for exactly what the adapter said.")
            return
        }
        ecuAddress = connectedAddress

        onEstablished()
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
                let completion = scanCompletion
                scanCompletion = nil
                completion?()
                return
            }
            let pid = remaining.removeFirst()
            mode = .scanning(remaining: remaining)
            await scanStep(pid: pid)
        case .watching(let pids):
            await watchStep(pids: pids)
        case .readingCodes:
            await readCodesStep()
        }
    }

    private func pollStep() async {
        priorityTick.toggle()
        let priorityDefs = PIDLibrary.all.filter { $0.enabled && $0.priority }
        if priorityTick, !priorityDefs.isEmpty {
            let def = priorityDefs[priorityIndex % priorityDefs.count]
            priorityIndex += 1
            await pollOne(def)
            return
        }

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

        await pollOne(def)
    }

    private func pollOne(_ def: PIDDefinition) async {
        do {
            let data = try await readLocalID(def.pid)
            consecutiveFailures = 0
            rawValuesByPID[def.pid] = data
            updateReading(for: def, data: data, error: nil)
        } catch let error as NegativeResponseError {
            consecutiveFailures = 0
            updateReading(for: def, data: nil, error: "ECU refused: \(error.nrcName)")
        } catch {
            consecutiveFailures += 1
            updateReading(for: def, data: nil, error: error.localizedDescription)
            if consecutiveFailures >= maxConsecutiveFailures {
                workLoopTask?.cancel()
                let onFailed = onSessionFailed
                link = nil
                onFailed?("Lost contact with the ECU. If the ski shut itself off, cycle the key and reconnect.")
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

    /// Scans every local identifier in `range` against the ECU. When `onComplete` is supplied, it
    /// fires once the scan finishes on its own — not if `stopScan` cancels it early — so a caller
    /// can safely chain into logic that depends on `scanResults` being complete (e.g. starting a
    /// research log against everything that responded).
    func startScan(range: ClosedRange<UInt8> = 0x00...0xFF, onComplete: (() -> Void)? = nil) {
        guard !isBusy else { return }
        scanResults = []
        isScanningPIDs = true
        scanCompletion = onComplete
        mode = .scanning(remaining: Array(range))
    }

    func stopScan() {
        mode = .polling
        isScanningPIDs = false
        scanCompletion = nil
    }

    /// "Watch" mode: continuously re-reads just a handful of chosen PIDs (fast cycle, since there
    /// are so few) and tracks low/high so you can wiggle a sensor and see which address moves.
    func startWatching(pids: [UInt8]) {
        guard !isBusy else { return }
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
                    reading = PIDReading(name: def.name, shortCode: shortCode, value: Self.formatValue(value), unit: def.units, icon: def.icon, isFault: false, faultReason: nil)
                } catch {
                    reading = PIDReading(name: def.name, shortCode: shortCode, value: "—", unit: "", icon: def.icon, isFault: true, faultReason: "formula error: \(error.localizedDescription)")
                }
            } else {
                reading = PIDReading(name: def.name, shortCode: shortCode, value: "—", unit: "", icon: def.icon, isFault: true, faultReason: "invalid formula")
            }
        } else {
            reading = PIDReading(name: def.name, shortCode: shortCode, value: "—", unit: "", icon: def.icon, isFault: true, faultReason: error ?? "No data")
        }

        if let index = pidReadings.firstIndex(where: { $0.shortCode == shortCode }) {
            pidReadings[index] = reading
        } else {
            pidReadings.append(reading)
        }
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
        guard !bytes.isEmpty else { throw OBDLinkError.malformedResponse(response) }
        if bytes[0] == 0x7F {
            let sid: UInt8 = bytes.count > 1 ? bytes[1] : 0x21
            let nrc: UInt8 = bytes.count > 2 ? bytes[2] : 0
            throw NegativeResponseError(sid: sid, nrc: nrc)
        }
        guard bytes.count >= 2, bytes[0] == 0x61, bytes[1] == pid else {
            throw OBDLinkError.malformedResponse(response)
        }
        return Array(bytes[2...])
    }

    // MARK: - Trouble codes (best-effort, unverified — see header comment)

    /// Standard OBD-II/KWP2000 two-byte DTC encoding: top 2 bits of the high byte pick the
    /// letter (P/C/B/U), next 2 bits are the first digit, and the remaining 20 bits (4 from
    /// the high byte + 8 from the low byte) render as 3 more hex digits.
    private static func decodeDTC(_ high: UInt8, _ low: UInt8) -> String {
        let letters = ["P", "C", "B", "U"]
        let letter = letters[Int(high >> 6) & 0x3]
        let firstDigit = (high >> 4) & 0x3
        let rest = (UInt16(high & 0x0F) << 8) | UInt16(low)
        return String(format: "%@%d%03X", letter, firstDigit, rest)
    }

    private static func parseDTCResponse(_ bytes: [UInt8], requestSID: UInt8) throws -> [DiagnosticTroubleCode] {
        guard !bytes.isEmpty else { throw OBDLinkError.malformedResponse("empty response") }
        if bytes[0] == 0x7F {
            let sid: UInt8 = bytes.count > 1 ? bytes[1] : requestSID
            let nrc: UInt8 = bytes.count > 2 ? bytes[2] : 0
            throw NegativeResponseError(sid: sid, nrc: nrc)
        }
        guard bytes[0] == requestSID &+ 0x40 else {
            throw OBDLinkError.malformedResponse(Self.hexString(bytes))
        }
        guard bytes.count >= 2 else { return [] }
        let count = Int(bytes[1])
        var codes: [DiagnosticTroubleCode] = []
        var i = 2
        for _ in 0..<count {
            guard i + 2 < bytes.count else { break }
            let high = bytes[i], low = bytes[i + 1], status = bytes[i + 2]
            codes.append(DiagnosticTroubleCode(
                code: decodeDTC(high, low),
                description: "Status byte 0x\(String(format: "%02X", status)) — decode unverified against this ECU.",
                isActive: status & 0x01 != 0
            ))
            i += 3
        }
        return codes
    }

    /// Tries KWP2000's two standard DTC-read services in turn (0x18 ReadDiagnosticTroubleCodesByStatus,
    /// then 0x17 ReadStatusOfDiagnosticTroubleCodes as a fallback). Neither is documented anywhere for
    /// this Kawasaki ECU family — ReadDataByLocalIdentifier (0x21) is the only confirmed service — so
    /// either attempt may simply be refused. That's an acceptable, correctly-surfaced outcome here.
    /// Routed through `mode` (like scanning/watching) rather than firing its own Task, so it
    /// can never run while the work loop is mid-poll/scan/watch and racing the same `send()`/
    /// `receiveBuffer` — which previously could and did (this was the source of crashes when
    /// reading codes while a scan or research log was active).
    func readTroubleCodes() async {
        guard !isBusy else {
            troubleCodesError = "Can't read codes while a scan, watch, or research log is active. Stop it first."
            return
        }
        isReadingCodes = true
        troubleCodesError = nil
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            codesCompletion = { continuation.resume() }
            mode = .readingCodes
        }
    }

    private func readCodesStep() async {
        defer {
            isReadingCodes = false
            mode = .polling
            let completion = codesCompletion
            codesCompletion = nil
            completion?()
        }

        let attempts: [(sid: UInt8, request: String)] = [
            (0x18, "18FF00FF"),
            (0x17, "17FF"),
        ]

        for attempt in attempts {
            do {
                let response = try await send(attempt.request)
                let bytes = try Self.parseHexBytes(response)
                troubleCodes = try Self.parseDTCResponse(bytes, requestSID: attempt.sid)
                troubleCodesError = nil
                return
            } catch let error as NegativeResponseError {
                troubleCodesError = "ECU refused service 0x\(String(format: "%02X", attempt.sid)): \(error.nrcName)."
            } catch {
                troubleCodesError = "Service 0x\(String(format: "%02X", attempt.sid)) failed: \(error.localizedDescription)."
            }
        }
        troubleCodes = []
    }

    // MARK: - Low-level AT/TCP transport

    @discardableResult
    private func send(_ command: String) async throws -> String {
        guard let link else { throw OBDLinkError.notConnected }

        if let lastRequestAt {
            let elapsed = clock.now - lastRequestAt
            if elapsed < requestGap {
                try? await Task.sleep(for: requestGap - elapsed)
            }
        }

        let data = (command + "\r").data(using: .ascii) ?? Data()
        try await link.write(data)

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

            guard let link else { throw OBDLinkError.notConnected }
            let chunk = try await link.nextIncomingChunk()
            if chunk.isEmpty { throw OBDLinkError.connectionClosed }
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
                throw OBDLinkError.elmReported(text.trimmingCharacters(in: .whitespacesAndNewlines))
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
            throw OBDLinkError.elmReported(text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        let hexOnly = upper.filter { $0.isHexDigit }
        guard !hexOnly.isEmpty, hexOnly.count % 2 == 0 else {
            throw OBDLinkError.malformedResponse(text)
        }
        var bytes: [UInt8] = []
        let chars = Array(hexOnly)
        var i = 0
        while i < chars.count {
            let pair = String(chars[i...i + 1])
            guard let byte = UInt8(pair, radix: 16) else { throw OBDLinkError.malformedResponse(text) }
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

enum OBDLinkError: LocalizedError {
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
