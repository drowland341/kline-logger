//
//  DashboardView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/4/26.
//

import SwiftUI

struct DashboardView: View {
    var isDemo: Bool
    var connectedDeviceName: String
    var adapterInfo: AdapterInfo?
    var obdEngine: OBDEngine?
    /// Lets any tab start a real connection directly, without backing out to the Home
    /// screen first. No-ops by default since they're irrelevant once actually connected
    /// or in demo mode (the tabs only ever show the Connect menu when neither is true).
    var onConnectWiFi: () -> Void = {}
    var onConnectBluetooth: () -> Void = {}
    var onEnd: () -> Void

    @State private var gpsManager = GPSSpeedManager()
    @AppStorage("researchLoggingEnabled") private var researchLoggingEnabled = false

    var body: some View {
        TabView {
            LivePIDsView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, adapterInfo: adapterInfo, obdEngine: obdEngine, gpsManager: gpsManager, onConnectWiFi: onConnectWiFi, onConnectBluetooth: onConnectBluetooth, onEnd: onEnd)
                .tabItem { Label("Live Data", systemImage: "waveform.path.ecg") }

            DiagnosticCodesView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, obdEngine: obdEngine, onConnectWiFi: onConnectWiFi, onConnectBluetooth: onConnectBluetooth, onEnd: onEnd)
                .tabItem { Label("Codes", systemImage: "exclamationmark.triangle") }

            PIDScannerView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, obdEngine: obdEngine, onConnectWiFi: onConnectWiFi, onConnectBluetooth: onConnectBluetooth, onEnd: onEnd)
                .tabItem { Label("PID Scan", systemImage: "magnifyingglass") }

            if researchLoggingEnabled, !isDemo, let obdEngine {
                ResearchModeView(obdEngine: obdEngine, gpsManager: gpsManager)
                    .tabItem { Label("Research", systemImage: "flask") }
            }
        }
        .task {
            gpsManager.start()
        }
        .onDisappear {
            gpsManager.stop()
        }
    }
}

#Preview("Demo") {
    DashboardView(isDemo: true, connectedDeviceName: "Demo Adapter", onEnd: {})
}

#Preview("Connected") {
    DashboardView(isDemo: false, connectedDeviceName: "Veepeak OBDCheck BLE", onEnd: {})
}
