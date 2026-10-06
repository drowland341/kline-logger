//
//  HomeView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/4/26.
//

import SwiftUI

struct HomeView: View {
    var errorMessage: String?
    var nearbyDeviceNames: [String] = []
    var technicalLog: [String] = []
    var onConnectWiFi: () -> Void
    var onConnectBluetooth: () -> Void
    var onDemo: () -> Void

    @State private var isShowingSettings = false

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            VStack(spacing: 16) {
                Image(systemName: "gauge.with.dots.needle.50percent")
                    .font(.system(size: 72))
                    .foregroundStyle(.tint)
                    .symbolRenderingMode(.hierarchical)

                VStack(spacing: 4) {
                    Text("KlineLogger")
                        .font(.largeTitle.bold())
                    Text("Kawasaki ECU Diagnostics")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            if let errorMessage {
                VStack(spacing: 8) {
                    Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)

                    if !nearbyDeviceNames.isEmpty {
                        DisclosureGroup("Nearby Bluetooth devices (\(nearbyDeviceNames.count))") {
                            VStack(alignment: .leading, spacing: 4) {
                                ForEach(nearbyDeviceNames, id: \.self) { name in
                                    Text(name)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .padding(.top, 4)
                        }
                        .font(.footnote)
                        .padding(.top, 4)
                    }

                    if !technicalLog.isEmpty {
                        DisclosureGroup("Technical details (\(technicalLog.count))") {
                            VStack(alignment: .leading, spacing: 4) {
                                ForEach(Array(technicalLog.enumerated()), id: \.offset) { _, line in
                                    Text(line)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .padding(.top, 4)
                        }
                        .font(.footnote)
                        .padding(.top, 4)
                    }
                }
                .padding(.horizontal, 32)
            }

            Spacer()

            VStack(spacing: 12) {
                Button(action: onConnectWiFi) {
                    Label("Connect to Adapter", systemImage: "wifi")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

                Button(action: onDemo) {
                    Label("Try Demo Mode", systemImage: "play.circle")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                Button(action: onConnectBluetooth) {
                    Text("Use Bluetooth Instead")
                        .font(.footnote)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .topTrailing) {
            Button {
                isShowingSettings = true
            } label: {
                Image(systemName: "gearshape")
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .padding(12)
            }
        }
        .sheet(isPresented: $isShowingSettings) {
            SettingsView()
        }
    }
}

#Preview {
    HomeView(errorMessage: nil, onConnectWiFi: {}, onConnectBluetooth: {}, onDemo: {})
}

#Preview("With Error") {
    HomeView(
        errorMessage: "Timed out reaching the adapter's Wi-Fi network. Make sure your phone is joined to the adapter's Wi-Fi (usually named something like \"OBDCHECK\") before connecting.",
        onConnectWiFi: {},
        onConnectBluetooth: {},
        onDemo: {}
    )
}

#Preview("With Error + Nearby Devices") {
    HomeView(
        errorMessage: "Couldn't find a Veepeak adapter nearby. Make sure it's powered on (ignition on) and close to your phone.",
        nearbyDeviceNames: ["OBDII", "JBL Flip 5", "Unknown"],
        onConnectWiFi: {},
        onConnectBluetooth: {},
        onDemo: {}
    )
}

#Preview("With Error + Technical Log") {
    HomeView(
        errorMessage: "No response from the ECU after trying 3 protocol variants and both known ECU addresses.",
        technicalLog: [
            "ATZ → ELM327 v1.5",
            "ATE0 → OK",
            "ATL0 → OK",
            "ATH0 → OK",
            "StartCommunication via ISO 14230-4 KWP (fast init), ECU 0x11 → NO DATA",
            "StartCommunication via ISO 14230-4 KWP (fast init), ECU 0x28 → NO DATA",
        ],
        onConnectWiFi: {},
        onConnectBluetooth: {},
        onDemo: {}
    )
}
