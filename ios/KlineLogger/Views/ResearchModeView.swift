//
//  ResearchModeView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//

import SwiftUI

struct ResearchModeView: View {
    var obdEngine: OBDEngine
    var gpsManager: GPSSpeedManager

    @State private var researchLogger = ResearchLogger()
    @State private var selectedPIDs: Set<UInt8> = []

    private var respondedPIDs: [(pid: UInt8, result: ScannedPID)] {
        obdEngine.scanResults.compactMap { result in
            guard result.didRespond,
                  let pid = UInt8(result.address.replacingOccurrences(of: "0x", with: ""), radix: 16) else { return nil }
            return (pid, result)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    Text("Logs every known channel plus GPS speed, once per second, all timestamped together.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    fullLogSection

                    if !researchLogger.isLogging {
                        Divider()

                        Text("Advanced: log a hand-picked subset instead of everything")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        if respondedPIDs.isEmpty {
                            Text("Run the full scan above (or a scan from the PID Scan tab) to choose individual PIDs here.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .padding(.vertical, 12)
                        } else {
                            VStack(spacing: 8) {
                                ForEach(respondedPIDs, id: \.pid) { entry in
                                    selectableRow(pid: entry.pid, result: entry.result)
                                }
                            }
                            manualStartButton
                        }
                    }

                    exportSection
                }
                .padding()
            }
            .navigationTitle("Research Log")
        }
    }

    private var fullLogSection: some View {
        VStack(spacing: 8) {
            if researchLogger.isLogging {
                Text("\(researchLogger.rows.count) rows logged")
                    .font(.subheadline.monospacedDigit())
                if let url = researchLogger.currentFileURL {
                    Text("Saving to \(url.lastPathComponent)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Button(role: .destructive) {
                    researchLogger.stop(obdEngine: obdEngine)
                } label: {
                    Label("Stop Logging", systemImage: "stop.circle")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            } else {
                Button(action: startFullLog) {
                    Label(obdEngine.isScanningPIDs ? "Scanning…" : "Start Full Log", systemImage: "record.circle")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(obdEngine.isScanningPIDs)

                if obdEngine.isScanningPIDs {
                    ProgressView(value: Double(obdEngine.scanResults.count), total: 256)
                    Text("Checking all 256 channels for a response — about 15–20 seconds. Logging starts automatically the moment this finishes.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                } else {
                    Text("One tap: scans every channel (0x00–0xFF), then logs everything that responded — alongside RPM, throttle, pressure, temps, and GPS speed — once per second until you stop it. Move/rev/turn things while it runs, then export and look for which PID tracks a known action.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
        }
    }

    private func startFullLog() {
        obdEngine.startScan { [obdEngine, gpsManager, researchLogger] in
            let allResponding = obdEngine.scanResults
                .filter(\.didRespond)
                .compactMap { UInt8($0.address.replacingOccurrences(of: "0x", with: ""), radix: 16) }
                .sorted()
            researchLogger.start(obdEngine: obdEngine, gpsManager: gpsManager, extraPIDs: allResponding)
        }
    }

    private func selectableRow(pid: UInt8, result: ScannedPID) -> some View {
        let isSelected = selectedPIDs.contains(pid)
        return Button {
            if isSelected { selectedPIDs.remove(pid) } else { selectedPIDs.insert(pid) }
        } label: {
            HStack {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? Color.accentColor : .secondary)
                Text(result.address)
                    .font(.subheadline.monospaced())
                if let name = result.name {
                    Text(name)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 10)
            .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }

    private var manualStartButton: some View {
        Button {
            researchLogger.start(obdEngine: obdEngine, gpsManager: gpsManager, extraPIDs: Array(selectedPIDs).sorted())
        } label: {
            Label("Log Selected Only (\(selectedPIDs.count))", systemImage: "record.circle")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
    }

    private var exportSection: some View {
        Group {
            if !researchLogger.rows.isEmpty, !researchLogger.isLogging {
                if let url = researchLogger.currentFileURL {
                    ShareLink(item: url) {
                        Label("Share CSV (\(researchLogger.rows.count) rows)", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                } else {
                    ShareLink(item: researchLogger.exportCSV()) {
                        Label("Export CSV (\(researchLogger.rows.count) rows)", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }

                Button(role: .destructive) {
                    researchLogger.clear()
                } label: {
                    Text("Clear Log")
                }
            }
        }
    }
}
