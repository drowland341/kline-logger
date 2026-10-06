//
//  ResearchModeView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//

import SwiftUI

struct ResearchModeView: View {
    var wifiManager: WiFiOBDManager
    var gpsManager: GPSSpeedManager

    @State private var researchLogger = ResearchLogger()
    @State private var selectedPIDs: Set<UInt8> = []

    private var respondedPIDs: [(pid: UInt8, result: ScannedPID)] {
        wifiManager.scanResults.compactMap { result in
            guard result.didRespond,
                  let pid = UInt8(result.address.replacingOccurrences(of: "0x", with: ""), radix: 16) else { return nil }
            return (pid, result)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    Text("Logs every known channel plus GPS speed and the PIDs you pick below, once per second, all timestamped together. Move/rev/turn things while logging, then export and look for which PID's value tracks a known action.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if respondedPIDs.isEmpty {
                        Text("Run a PID scan first (PID Scan tab) to choose which extra PIDs to log here.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 20)
                    } else if !researchLogger.isLogging {
                        VStack(spacing: 8) {
                            ForEach(respondedPIDs, id: \.pid) { entry in
                                selectableRow(pid: entry.pid, result: entry.result)
                            }
                        }
                    }

                    controlSection
                }
                .padding()
            }
            .navigationTitle("Research Log")
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

    private var controlSection: some View {
        VStack(spacing: 12) {
            if researchLogger.isLogging {
                Text("\(researchLogger.rows.count) rows logged")
                    .font(.subheadline.monospacedDigit())
                Button(role: .destructive) {
                    researchLogger.stop(wifiManager: wifiManager)
                } label: {
                    Label("Stop Logging", systemImage: "stop.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            } else {
                Button {
                    researchLogger.start(wifiManager: wifiManager, gpsManager: gpsManager, extraPIDs: Array(selectedPIDs).sorted())
                } label: {
                    Label("Start Logging", systemImage: "record.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(respondedPIDs.isEmpty)
            }

            if !researchLogger.rows.isEmpty, !researchLogger.isLogging {
                ShareLink(item: researchLogger.exportCSV()) {
                    Label("Export CSV (\(researchLogger.rows.count) rows)", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                Button(role: .destructive) {
                    researchLogger.clear()
                } label: {
                    Text("Clear Log")
                }
            }
        }
    }
}
