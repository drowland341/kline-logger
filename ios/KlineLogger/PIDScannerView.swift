//
//  PIDScannerView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import SwiftUI

struct PIDScannerView: View {
    var isDemo: Bool
    var connectedDeviceName: String
    var wifiManager: WiFiOBDManager?
    var onEnd: () -> Void

    @State private var demoResults: [ScannedPID] = []
    @State private var isDemoScanning = false
    @State private var scanTask: Task<Void, Never>?
    @State private var selectedPIDs: Set<UInt8> = []

    private var results: [ScannedPID] {
        isDemo ? demoResults : (wifiManager?.scanResults ?? [])
    }

    private var isScanning: Bool {
        isDemo ? isDemoScanning : (wifiManager?.isScanningPIDs ?? false)
    }

    private var isWatching: Bool {
        wifiManager?.isWatching ?? false
    }

    private var totalCandidates: Double {
        isDemo ? Double(DemoData.scanCandidates.count) : 256
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    ConnectionStatusBanner(isDemo: isDemo, connectedDeviceName: connectedDeviceName)

                    if isDemo || wifiManager != nil {
                        if isWatching {
                            watchPanel
                        } else {
                            scanControl

                            if !selectedPIDs.isEmpty {
                                watchSelectedButton
                            }

                            if !results.isEmpty {
                                VStack(spacing: 8) {
                                    ForEach(results) { result in
                                        ScanResultRow(
                                            result: result,
                                            isSelectable: !isDemo && result.didRespond,
                                            isSelected: selectedPIDs.contains(address(from: result.address) ?? 0xFF)
                                        ) {
                                            toggleSelection(result.address)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        notAvailableState
                    }
                }
                .padding()
            }
            .navigationTitle("PID Scanner")
            .toolbar {
                if !isDemo, !results.isEmpty {
                    ToolbarItem(placement: .topBarLeading) {
                        ShareLink(item: exportText()) {
                            Label("Export", systemImage: "square.and.arrow.up")
                        }
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(isDemo ? "End Demo" : "Disconnect", role: .destructive, action: onEnd)
                }
            }
        }
        .onDisappear {
            scanTask?.cancel()
        }
    }

    private var notAvailableState: some View {
        VStack(spacing: 8) {
            Image(systemName: "hourglass")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text("PID scanning coming soon")
                .font(.headline)
            Text("Scanning for responsive PIDs requires ECU protocol support, which is still in progress.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    private var scanControl: some View {
        VStack(spacing: 12) {
            Button(action: startScan) {
                Label(isScanning ? "Scanning…" : "Start Scan", systemImage: "magnifyingglass")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(isScanning)

            if isScanning {
                ProgressView(value: Double(results.count), total: totalCandidates)
            } else if !isDemo {
                Text("Checks every local identifier from 0x00 to 0xFF against the ECU (about 15-20 seconds). Tap a responded row to select it, then Watch to see its value live and figure out what it measures.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var watchSelectedButton: some View {
        Button {
            wifiManager?.startWatching(pids: Array(selectedPIDs).sorted())
        } label: {
            Label("Watch Selected (\(selectedPIDs.count))", systemImage: "eye")
                .font(.headline)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
    }

    private var watchPanel: some View {
        VStack(spacing: 12) {
            Text("Watching \(wifiManager?.watchSamples.count ?? 0) PID(s) — wiggle the sensor you're trying to identify and watch for the one that moves.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 8) {
                ForEach(wifiManager?.watchSamples ?? []) { sample in
                    WatchSampleRow(sample: sample)
                }
            }

            Button(role: .destructive) {
                wifiManager?.stopWatching()
            } label: {
                Label("Stop Watching", systemImage: "eye.slash")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
    }

    private func toggleSelection(_ addressString: String) {
        guard let value = address(from: addressString) else { return }
        if selectedPIDs.contains(value) {
            selectedPIDs.remove(value)
        } else {
            selectedPIDs.insert(value)
        }
    }

    private func address(from hexString: String) -> UInt8? {
        UInt8(hexString.replacingOccurrences(of: "0x", with: ""), radix: 16)
    }

    private func startScan() {
        if let wifiManager {
            selectedPIDs = []
            wifiManager.startScan()
            return
        }
        demoResults = []
        isDemoScanning = true
        scanTask?.cancel()
        scanTask = Task {
            for candidate in DemoData.scanCandidates {
                try? await Task.sleep(for: .milliseconds(250))
                guard !Task.isCancelled else { return }
                demoResults.append(ScannedPID(address: candidate.address, name: candidate.name, didRespond: candidate.name != nil))
            }
            isDemoScanning = false
        }
    }

    private func exportText() -> String {
        var lines = ["KlineLogger PID Scan Report", "Device: \(connectedDeviceName)", ""]
        for result in results {
            if result.didRespond {
                lines.append("\(result.address)  Responded  \(result.name ?? "")  raw=\(result.rawValue ?? "")")
            } else {
                lines.append("\(result.address)  No response")
            }
        }
        if let samples = wifiManager?.watchSamples, !samples.isEmpty {
            lines.append("")
            lines.append("Watch Session")
            for sample in samples {
                lines.append("\(sample.address)  \(sample.name ?? "")  raw=\(sample.rawValue)  low=\(sample.low) (0x\(String(format: "%02X", sample.low)))  high=\(sample.high) (0x\(String(format: "%02X", sample.high)))")
            }
        }
        return lines.joined(separator: "\n")
    }
}

private struct ScanResultRow: View {
    let result: ScannedPID
    var isSelectable: Bool = false
    var isSelected: Bool = false
    var onTap: () -> Void = {}

    var body: some View {
        Button(action: onTap) {
            HStack {
                if isSelectable {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(isSelected ? Color.accentColor : .secondary)
                } else {
                    Image(systemName: result.didRespond ? "checkmark.circle.fill" : "xmark.circle")
                        .foregroundStyle(result.didRespond ? .green : .secondary)
                }

                Text(result.address)
                    .font(.subheadline.monospaced())
                    .foregroundStyle(.primary)

                if let name = result.name {
                    Text(name)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer()

                if result.didRespond, let rawValue = result.rawValue {
                    Text(rawValue)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                } else {
                    Text(result.didRespond ? "Responded" : "No response")
                        .font(.caption)
                        .foregroundStyle(result.didRespond ? .green : .secondary)
                }
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 12)
            .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .disabled(!isSelectable)
    }
}

private struct WatchSampleRow: View {
    let sample: WatchedPIDSample

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(sample.address)
                    .font(.subheadline.monospaced())
                if let name = sample.name {
                    Text(name)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text(sample.rawValue)
                    .font(.body.monospaced().weight(.semibold))
                Text("low \(sample.low) · high \(sample.high)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 10))
    }
}

#Preview("Demo") {
    PIDScannerView(isDemo: true, connectedDeviceName: "", onEnd: {})
}

#Preview("Connected, no protocol yet") {
    PIDScannerView(isDemo: false, connectedDeviceName: "Veepeak OBDCheck BLE", onEnd: {})
}
