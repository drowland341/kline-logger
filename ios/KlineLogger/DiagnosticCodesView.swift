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
    var onEnd: () -> Void

    @State private var codes: [DiagnosticTroubleCode] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    ConnectionStatusBanner(isDemo: isDemo, connectedDeviceName: connectedDeviceName)

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
                codes = DemoData.troubleCodes
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: isDemo ? "checkmark.circle" : "hourglass")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(isDemo ? "No trouble codes" : "Trouble codes coming soon")
                .font(.headline)
            Text(isDemo ? "No fault codes are currently stored." : "Reading diagnostic trouble codes requires ECU protocol support, which is still in progress.")
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
