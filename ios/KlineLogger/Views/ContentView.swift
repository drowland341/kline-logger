//
//  ContentView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/4/26.
//

import SwiftUI

private enum ActiveTransport: Equatable {
    case none
    case wifi
    case bluetooth
}

struct ContentView: View {
    @State private var wifiManager = WiFiOBDManager()
    @State private var bluetoothManager = BluetoothManager()
    @State private var isDemoMode = false
    @State private var isBrowsingWithoutConnecting = false
    @State private var activeTransport: ActiveTransport = .none

    private var currentPhase: ConnectionPhase {
        switch activeTransport {
        case .none: return .idle
        case .wifi: return wifiManager.phase
        case .bluetooth: return bluetoothManager.phase
        }
    }

    var body: some View {
        Group {
            if isDemoMode {
                DashboardView(isDemo: true, connectedDeviceName: "Demo Adapter") {
                    isDemoMode = false
                }
            } else if isBrowsingWithoutConnecting {
                DashboardView(
                    isDemo: false,
                    connectedDeviceName: "",
                    onConnectWiFi: {
                        isBrowsingWithoutConnecting = false
                        activeTransport = .wifi
                        wifiManager.connect()
                    },
                    onConnectBluetooth: {
                        isBrowsingWithoutConnecting = false
                        activeTransport = .bluetooth
                        bluetoothManager.connect()
                    }
                ) {
                    isBrowsingWithoutConnecting = false
                }
            } else {
                switch currentPhase {
                case .idle, .failed:
                    HomeView(
                        errorMessage: currentPhase.errorMessage,
                        nearbyDeviceNames: bluetoothManager.nearbyDeviceNames,
                        technicalLog: wifiManager.engine.handshakeLog,
                        onConnectWiFi: {
                            activeTransport = .wifi
                            wifiManager.connect()
                        },
                        onConnectBluetooth: {
                            activeTransport = .bluetooth
                            bluetoothManager.connect()
                        },
                        onDemo: { isDemoMode = true },
                        onBrowse: { isBrowsingWithoutConnecting = true }
                    )
                case .scanning, .connecting:
                    ConnectingView(statusText: currentPhase.statusText) {
                        cancelActiveConnection()
                    }
                case .connected(let name):
                    DashboardView(
                        isDemo: false,
                        connectedDeviceName: name,
                        adapterInfo: activeTransport == .bluetooth
                            ? .bluetooth(bluetoothManager.discoveredServices)
                            : .wifi(wifiManager.engine.handshakeLog),
                        obdEngine: activeTransport == .wifi ? wifiManager.engine : (activeTransport == .bluetooth ? bluetoothManager.engine : nil)
                    ) {
                        disconnectActiveConnection()
                    }
                }
            }
        }
        .tint(.cyan)
        .animation(.default, value: isDemoMode)
        .animation(.default, value: isBrowsingWithoutConnecting)
    }

    private func cancelActiveConnection() {
        switch activeTransport {
        case .wifi: wifiManager.cancel()
        case .bluetooth: bluetoothManager.cancel()
        case .none: break
        }
        activeTransport = .none
    }

    private func disconnectActiveConnection() {
        switch activeTransport {
        case .wifi: wifiManager.disconnect()
        case .bluetooth: bluetoothManager.disconnect()
        case .none: break
        }
        activeTransport = .none
    }
}

#Preview {
    ContentView()
}
