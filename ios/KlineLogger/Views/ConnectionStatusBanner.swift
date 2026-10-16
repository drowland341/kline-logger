//
//  ConnectionStatusBanner.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import SwiftUI

struct ConnectionStatusBanner: View {
    var isDemo: Bool
    /// False when browsing the dashboard without an adapter connected — GPS Speed still
    /// works in that state, but ECU-backed readings (RPM, PID cards, Codes, PID Scanner)
    /// fall back to placeholders. Ignored when `isDemo` is true.
    var isConnected: Bool = true
    var connectedDeviceName: String
    /// Landscape phones are short on vertical room, and that room matters a lot
    /// more to the gauges below than it does to this banner — so compact trims
    /// the padding and font instead of taking the same space it gets in portrait.
    var compact: Bool = false

    var body: some View {
        HStack {
            Circle()
                .fill(isDemo ? Color.orange : (isConnected ? Color.green : Color.gray))
                .frame(width: compact ? 7 : 10, height: compact ? 7 : 10)
            Text(isDemo ? "Demo Mode — Simulated Data" : (isConnected ? "Connected to \(connectedDeviceName)" : "Not Connected — Showing Placeholder Data"))
                .font(compact ? .caption.weight(.medium) : .subheadline.weight(.medium))
            Spacer()
        }
        .padding(.horizontal, compact ? 12 : 16)
        .padding(.vertical, compact ? 6 : 16)
        .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Toolbar menu offering both transports, so any Dashboard tab can start a real connection
/// without backing out to the Home screen. Shown only while browsing without an adapter.
struct ConnectMenu: View {
    var onConnectWiFi: () -> Void
    var onConnectBluetooth: () -> Void

    var body: some View {
        Menu {
            Button("Wi-Fi", systemImage: "wifi", action: onConnectWiFi)
            Button("Bluetooth", systemImage: "antenna.radiowaves.left.and.right", action: onConnectBluetooth)
        } label: {
            Label("Connect", systemImage: "wifi")
        }
    }
}

#Preview {
    VStack {
        ConnectionStatusBanner(isDemo: true, connectedDeviceName: "")
        ConnectionStatusBanner(isDemo: false, connectedDeviceName: "Veepeak OBDCheck BLE")
        ConnectionStatusBanner(isDemo: false, isConnected: false, connectedDeviceName: "")
    }
    .padding()
}
