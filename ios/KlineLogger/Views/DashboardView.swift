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
    var onEnd: () -> Void

    @State private var gpsManager = GPSSpeedManager()
    @AppStorage("researchLoggingEnabled") private var researchLoggingEnabled = false

    var body: some View {
        TabView {
            LivePIDsView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, adapterInfo: adapterInfo, obdEngine: obdEngine, gpsManager: gpsManager, onEnd: onEnd)
                .tabItem { Label("Live Data", systemImage: "waveform.path.ecg") }

            DiagnosticCodesView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, obdEngine: obdEngine, onEnd: onEnd)
                .tabItem { Label("Codes", systemImage: "exclamationmark.triangle") }

            PIDScannerView(isDemo: isDemo, connectedDeviceName: connectedDeviceName, obdEngine: obdEngine, onEnd: onEnd)
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
