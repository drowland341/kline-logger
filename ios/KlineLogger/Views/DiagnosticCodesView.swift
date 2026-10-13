//
//  DiagnosticCodesView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import SwiftUI

struct DiagnosticCodesView: View {
    var isDemo: Bool
    var connectedDeviceName: String
    var obdEngine: OBDEngine?
    var onEnd: () -> Void

    @State private var demoCodes: [DiagnosticTroubleCode] = []

    private var codes: [DiagnosticTroubleCode] {
        isDemo ? demoCodes : (obdEngine?.troubleCodes ?? [])
    }

    private var isReading: Bool {
        obdEngine?.isReadingCodes ?? false
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    ConnectionStatusBanner(isDemo: isDemo, connectedDeviceName: connectedDeviceName)

                    if !isDemo, obdEngine != nil {
                        readCodesControl
                    }

                    if codes.isEmpty {
                        emptyState
                    } else {
                        VStack(spacing: 12) {
                            ForEach(codes) { code in
                                DTCRow(code: code)
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("Trouble Codes")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(isDemo ? "End Demo" : "Disconnect", role: .destructive, action: onEnd)
                }
            }
        }
        .task {
            if isDemo {
                demoCodes = DemoData.troubleCodes
            }
        }
    }

    private var readCodesControl: some View {
        VStack(spacing: 8) {
            Button {
                Task { await obdEngine?.readTroubleCodes() }
            } label: {
                Label(isReading ? "Reading…" : "Read Codes", systemImage: "arrow.clockwise")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(isReading)

            Text("Experimental — unlike RPM/PID reading, Kawasaki's trouble-code service isn't confirmed for this ECU. The ECU may simply refuse this request.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            if let error = obdEngine?.troubleCodesError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var emptyState: some View {
        let icon = isDemo ? "checkmark.circle" : (isReading ? "hourglass" : "questionmark.circle")
        let title = isDemo ? "No trouble codes" : (isReading ? "Reading codes…" : "No codes read yet")
        let subtitle = isDemo
            ? "No fault codes are currently stored."
            : (isReading ? "Checking the ECU for trouble codes." : "Tap Read Codes above to check the ECU.")
        return VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(subtitle)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }
}

private struct DTCRow: View {
    let code: DiagnosticTroubleCode

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: code.isActive ? "exclamationmark.triangle.fill" : "clock.arrow.circlepath")
                .foregroundStyle(code.isActive ? .red : .orange)
                .font(.title3)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(code.code)
                        .font(.headline.monospaced())
                    Spacer()
                    Text(code.isActive ? "Active" : "Stored")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background((code.isActive ? Color.red : Color.orange).opacity(0.15), in: Capsule())
                        .foregroundStyle(code.isActive ? .red : .orange)
                }
                Text(code.description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 12))
    }
}

#Preview("Demo") {
    DiagnosticCodesView(isDemo: true, connectedDeviceName: "", onEnd: {})
}

#Preview("Connected, no protocol yet") {
    DiagnosticCodesView(isDemo: false, connectedDeviceName: "Veepeak OBDCheck BLE", onEnd: {})
}
