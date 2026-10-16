//
//  SettingsView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//

import SwiftUI

struct SettingsView: View {
    @AppStorage("researchLoggingEnabled") private var researchLoggingEnabled = false
    @AppStorage("temperatureUnit") private var temperatureUnit: TemperatureUnit = .fahrenheit
    @AppStorage("pressureUnit") private var pressureUnit: PressureUnit = .inHg
    @AppStorage("speedUnit") private var speedUnit: SpeedUnit = .mph
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 16) {
                        Image("AppLogo")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))

                        VStack(alignment: .leading, spacing: 2) {
                            Text("KlineLogger")
                                .font(.headline)
                            Text("Kawasaki ECU Diagnostics")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section {
                    NavigationLink {
                        UnitsSettingsView()
                    } label: {
                        HStack {
                            Text("Units")
                            Spacer()
                            Text("\(temperatureUnit.symbol) · \(pressureUnit.symbol) · \(speedUnit.symbol)")
                                .foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text("Controls how temperature, pressure, and GPS speed are displayed on Live Data. Raw ECU values and Research Mode CSV exports always stay in their native units so logs are comparable across sessions.")
                }

                Section {
                    Toggle("Research Logging", isOn: $researchLoggingEnabled)
                } footer: {
                    Text("Adds a Research tab once connected that logs every known sensor reading alongside raw bytes from PIDs you choose, all timestamped together, exportable as CSV. Useful for extending the PID list to other Kawasaki models. Most people won't need this.")
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

#Preview {
    SettingsView()
}
