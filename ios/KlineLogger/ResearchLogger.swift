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

    private var task: Task<Void, Never>?
    private var knownChannelNames: [String] = []

    func start(wifiManager: WiFiOBDManager, gpsManager: GPSSpeedManager, extraPIDs: [UInt8], interval: Duration = .seconds(1)) {
        rows = []
        knownChannelNames = PIDLibrary.all.filter(\.enabled).map(\.name)
        wifiManager.extraLoggedPIDs = extraPIDs
        isLogging = true
        task?.cancel()
        task = Task { [weak self] in
            while let self, !Task.isCancelled {
                self.sample(wifiManager: wifiManager, gpsManager: gpsManager, extraPIDs: extraPIDs)
                try? await Task.sleep(for: interval)
            }
        }
    }

    func stop(wifiManager: WiFiOBDManager?) {
        task?.cancel()
        task = nil
        isLogging = false
        wifiManager?.extraLoggedPIDs = []
    }

    func clear() {
        rows = []
    }

    private func sample(wifiManager: WiFiOBDManager, gpsManager: GPSSpeedManager, extraPIDs: [UInt8]) {
        let readingsByName = Dictionary(uniqueKeysWithValues: wifiManager.pidReadings.map { ($0.name, $0) })
        let known = knownChannelNames.map { name -> String in
            guard let reading = readingsByName[name], !reading.isFault else { return "" }
            return reading.value
        }
        let extras = extraPIDs.map { pid in (pid: pid, raw: wifiManager.rawValuesByPID[pid] ?? []) }
        rows.append(ResearchLogRow(timestamp: Date(), knownValues: known, gpsSpeedMPH: gpsManager.speedMPH, extraRawValues: extras))
    }

    func exportCSV() -> String {
        guard let t0 = rows.first?.timestamp else { return "No data logged." }

        var header = ["time_s"]
        header.append(contentsOf: knownChannelNames)
        header.append("gps_mph")
        if let first = rows.first {
            header.append(contentsOf: first.extraRawValues.map { "pid_0x\(String(format: "%02X", $0.pid))_hex" })
            header.append(contentsOf: first.extraRawValues.map { "pid_0x\(String(format: "%02X", $0.pid))_dec" })
        }

        var lines = [header.joined(separator: ",")]
        for row in rows {
            var fields = [String(format: "%.2f", row.timestamp.timeIntervalSince(t0))]
            fields.append(contentsOf: row.knownValues)
            fields.append(row.gpsSpeedMPH.map { String(format: "%.1f", $0) } ?? "")
            fields.append(contentsOf: row.extraRawValues.map { entry in
                entry.raw.map { String(format: "%02X", $0) }.joined(separator: " ")
            })
            fields.append(contentsOf: row.extraRawValues.map { entry in
                String(entry.raw.reduce(0) { ($0 << 8) | Int($1) })
            })
            lines.append(fields.joined(separator: ","))
        }
        return lines.joined(separator: "\n")
    }
}
