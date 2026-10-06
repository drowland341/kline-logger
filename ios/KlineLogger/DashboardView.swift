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
    var wifiManager: WiFiOBDManager?
    var onEnd: () -> Void

    @State private var gpsManager = GPSSpeedManager()
    @AppStorage("researchLoggingEnabled") private var researchLoggingEnabled = false

    var body: some View {
        TabView {
            LivePIDsView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, adapterInfo: adapterInfo, wifiManager: wifiManager, gpsManager: gpsManager, onEnd: onEnd)
                .tabItem { Label("Live Data", systemImage: "waveform.path.ecg") }

            DiagnosticCodesView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, onEnd: onEnd)
                .tabItem { Label("Codes", systemImage: "exclamationmark.triangle") }

            PIDScannerView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, wifiManager: wifiManager, onEnd: onEnd)
                .tabItem { Label("PID Scan", systemImage: "magnifyingglass") }

            if researchLoggingEnabled, !isDemo, let wifiManager {
                ResearchModeView(wifiManager: wifiManager, gpsManager: gpsManager)
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
