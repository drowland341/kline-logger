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

    var body: some View {
        HStack {
            Circle()
                .fill(isDemo ? Color.orange : Color.green)
                .frame(width: 10, height: 10)
            Text(isDemo ? "Demo Mode — Simulated Data" : "Connected to \(connectedDeviceName)")
                .font(.subheadline.weight(.medium))
            Spacer()
        }
        .padding()
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
