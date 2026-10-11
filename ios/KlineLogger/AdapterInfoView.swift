//
//  AdapterInfoView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import CoreBluetooth
import SwiftUI

enum AdapterInfo {
    case bluetooth([DiscoveredService])
    case wifi([String])
}

struct AdapterInfoView: View {
    var deviceName: String
    var info: AdapterInfo

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Device") {
                    LabeledContent("Name", value: deviceName)
                }

                switch info {
                case .bluetooth(let services):
                    ForEach(services) { service in
                        Section("Service \(service.uuid.uuidString)") {
                            if service.characteristicUUIDs.isEmpty {
                                Text("Discovering characteristics…")
                                    .foregroundStyle(.secondary)
                            } else {
                                ForEach(service.characteristicUUIDs, id: \.self) { characteristic in
                                    Text(characteristic)
                                        .font(.footnote.monospaced())
                                }
                            }
                        }
                    }
                case .wifi(let handshakeLog):
                    Section("AT Command Handshake") {
                        if handshakeLog.isEmpty {
                            Text("No handshake recorded.")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(handshakeLog, id: \.self) { line in
                                Text(line)
                                    .font(.footnote.monospaced())
                            }
                        }
                    }
                }
            }
            .navigationTitle("Adapter Info")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

#Preview("Bluetooth") {
    AdapterInfoView(
        deviceName: "Veepeak OBDCheck BLE",
        info: .bluetooth([
            DiscoveredService(id: CBUUID(string: "FFF0"), characteristicUUIDs: ["FFF1  [notify]", "FFF2  [write, writeNoResp]"]),
            DiscoveredService(id: CBUUID(string: "1800"), characteristicUUIDs: ["2A00  [read]"]),
        ])
    )
}

#Preview("Wi-Fi") {
    AdapterInfoView(
        deviceName: "OBDCheck Wi-Fi",
        info: .wifi([
            "ATZ → ELM327 v1.5",
            "ATE0 → OK",
            "ATL0 → OK",
            "ATSP0 → OK",
        ])
    )
}
