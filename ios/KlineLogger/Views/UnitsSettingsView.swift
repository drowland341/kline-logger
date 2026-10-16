//
//  UnitsSettingsView.swift
//  KlineLogger
//
//  Per-category unit picker, reached from Settings. Temperature, pressure, and speed are
//  stored as independent preferences (see UnitSystem.swift) so they can be mixed and
//  matched — e.g. Fahrenheit and mph but kPa for pressure.
//

import SwiftUI

struct UnitsSettingsView: View {
    @AppStorage("temperatureUnit") private var temperatureUnit: TemperatureUnit = .fahrenheit
    @AppStorage("pressureUnit") private var pressureUnit: PressureUnit = .inHg
    @AppStorage("speedUnit") private var speedUnit: SpeedUnit = .mph

    var body: some View {
        List {
            Section("Temperature") {
                Picker("Temperature", selection: $temperatureUnit) {
                    ForEach(TemperatureUnit.allCases) { unit in
                        Text(unit.symbol).tag(unit)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }

            Section("Pressure") {
                Picker("Pressure", selection: $pressureUnit) {
                    ForEach(PressureUnit.allCases) { unit in
                        Text(unit.symbol).tag(unit)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }

            Section {
                Picker("GPS Speed", selection: $speedUnit) {
                    ForEach(SpeedUnit.allCases) { unit in
                        Text(unit.symbol).tag(unit)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            } header: {
                Text("GPS Speed")
            } footer: {
                Text("Raw ECU values and Research Mode CSV exports always stay in their native units so logs are comparable across sessions — this only changes what's displayed on Live Data.")
            }
        }
        .navigationTitle("Units")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack {
        UnitsSettingsView()
    }
}
