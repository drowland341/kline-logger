//
//  ConnectionStatusBanner.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import SwiftUI

struct ConnectionStatusBanner: View {
    var isDemo: Bool
    var connectedDeviceName: String
    /// Landscape phones are short on vertical room, and that room matters a lot
    /// more to the gauges below than it does to this banner — so compact trims
    /// the padding and font instead of taking the same space it gets in portrait.
    var compact: Bool = false

    var body: some View {
        HStack {
            Circle()
                .fill(isDemo ? Color.orange : Color.green)
                .frame(width: compact ? 7 : 10, height: compact ? 7 : 10)
            Text(isDemo ? "Demo Mode — Simulated Data" : "Connected to \(connectedDeviceName)")
                .font(compact ? .caption.weight(.medium) : .subheadline.weight(.medium))
            Spacer()
        }
        .padding(.horizontal, compact ? 12 : 16)
        .padding(.vertical, compact ? 6 : 16)
        .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 12))
    }
}

#Preview {
    VStack {
        ConnectionStatusBanner(isDemo: true, connectedDeviceName: "")
        ConnectionStatusBanner(isDemo: false, connectedDeviceName: "Veepeak OBDCheck BLE")
    }
    .padding()
}
