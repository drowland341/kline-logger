//
//  ResearchLogger.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//
//  Developer-only tool (gated behind the Research Logging setting) that samples every
//  known channel, GPS speed, and a chosen set of unknown PIDs together once per second,
//  so unknown PIDs can be correlated against real sensor behavior after the fact — the
//  same idea as the PID Scanner's Watch mode, but synchronized with everything else and
//  exportable for offline analysis. Intended for extending pids.json to other Kawasaki
//  models, not for everyday use.
//
//  Each session is streamed to disk as it's captured (rather than only held in memory
//  and exported at the end) so a crash or backgrounding out on the water doesn't lose
//  the run. Files land in Documents/ResearchLogs, which is exposed to the Files app via
//  UIFileSharingEnabled/LSSupportsOpeningDocumentsInPlace in Info.plist.

import Foundation
import Observation

struct ResearchLogRow: Identifiable {
    let id = UUID()
    let timestamp: Date
    let knownValues: [String]
    let gpsSpeedMPH: Double?
    let extraRawValues: [(pid: UInt8, raw: [UInt8])]
}

@Observable
final class ResearchLogger {
    private(set) var rows: [ResearchLogRow] = []
    private(set) var isLogging = false
    private(set) var currentFileURL: URL?

    private var task: Task<Void, Never>?
    private var knownChannelNames: [String] = []
    private var sessionStart: Date?
    private var fileHandle: FileHandle?

    static var sessionsDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("ResearchLogs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func start(obdEngine: OBDEngine, gpsManager: GPSSpeedManager, extraPIDs: [UInt8], interval: Duration = .seconds(1)) {
        rows = []
        knownChannelNames = PIDLibrary.all.filter(\.enabled).map(\.name)
        obdEngine.extraLoggedPIDs = extraPIDs
        obdEngine.isResearchLogging = true
        sessionStart = Date()
        openFile(extraPIDs: extraPIDs)
        isLogging = true
        task?.cancel()
        task = Task { [weak self] in
            while let self, !Task.isCancelled {
                self.sample(obdEngine: obdEngine, gpsManager: gpsManager, extraPIDs: extraPIDs)
                try? await Task.sleep(for: interval)
            }
        }
    }

    func stop(obdEngine: OBDEngine?) {
        task?.cancel()
        task = nil
        isLogging = false
        obdEngine?.extraLoggedPIDs = []
        obdEngine?.isResearchLogging = false
        closeFile()
    }

    func clear() {
        rows = []
        currentFileURL = nil
    }

    private func openFile(extraPIDs: [UInt8]) {
        closeFile()
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        let url = Self.sessionsDirectory.appendingPathComponent("research-\(formatter.string(from: sessionStart ?? Date())).csv")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        handle.write(Data((headerLine(extraPIDs: extraPIDs) + "\n").utf8))
        fileHandle = handle
        currentFileURL = url
    }

    private func closeFile() {
        try? fileHandle?.close()
        fileHandle = nil
    }

    private func headerLine(extraPIDs: [UInt8]) -> String {
        var header = ["time_s"]
        header.append(contentsOf: knownChannelNames)
        header.append("gps_mph")
        header.append(contentsOf: extraPIDs.map { "pid_0x\(String(format: "%02X", $0))_hex" })
        header.append(contentsOf: extraPIDs.map { "pid_0x\(String(format: "%02X", $0))_dec" })
        return header.joined(separator: ",")
    }

    /// Quotes a CSV field per RFC 4180 if it contains a character that would otherwise corrupt
    /// column alignment. Needed now that known-channel cells can hold a fault reason instead of
    /// just a numeric value — NegativeResponseError's text (e.g. "busy, repeat request") contains
    /// a literal comma, which would have silently shifted every column after it.
    private static func csvField(_ s: String) -> String {
        guard s.contains(",") || s.contains("\"") || s.contains("\n") else { return s }
        return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    private func rowLine(_ row: ResearchLogRow) -> String {
        var fields = [String(format: "%.2f", row.timestamp.timeIntervalSince(sessionStart ?? row.timestamp))]
        fields.append(contentsOf: row.knownValues.map(Self.csvField))
        fields.append(row.gpsSpeedMPH.map { String(format: "%.1f", $0) } ?? "")
        fields.append(contentsOf: row.extraRawValues.map { entry in
            entry.raw.map { String(format: "%02X", $0) }.joined(separator: " ")
        })
        fields.append(contentsOf: row.extraRawValues.map { entry in
            String(entry.raw.reduce(0) { ($0 << 8) | Int($1) })
        })
        return fields.joined(separator: ",")
    }

    /// A reading that failed to decode used to log as a blank cell — identical to "not polled
    /// yet," so a sensor that actually drops out (ECU refusal, timeout, bad formula) mid-ride was
    /// invisible in the export. Now the fault reason itself lands in the cell, so e.g. a burst of
    /// "FAULT: ECU refused: busy, repeat request" rows exactly when the ski bogs is visible.
    private func sample(obdEngine: OBDEngine, gpsManager: GPSSpeedManager, extraPIDs: [UInt8]) {
        let readingsByName = Dictionary(uniqueKeysWithValues: obdEngine.pidReadings.map { ($0.name, $0) })
        let known = knownChannelNames.map { name -> String in
            guard let reading = readingsByName[name] else { return "" }
            if reading.isFault {
                return "FAULT: \(reading.faultReason ?? "unknown")"
            }
            return reading.value
        }
        let extras = extraPIDs.map { pid in (pid: pid, raw: obdEngine.rawValuesByPID[pid] ?? []) }
        let row = ResearchLogRow(timestamp: Date(), knownValues: known, gpsSpeedMPH: gpsManager.speedMPH, extraRawValues: extras)
        rows.append(row)
        fileHandle?.write(Data((rowLine(row) + "\n").utf8))
    }

    func exportCSV() -> String {
        guard !rows.isEmpty, let first = rows.first else { return "No data logged." }
        var lines = [headerLine(extraPIDs: first.extraRawValues.map(\.pid))]
        lines.append(contentsOf: rows.map(rowLine))
        return lines.joined(separator: "\n")
    }
}
