//
//  SettingsView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//

import SwiftUI

struct SettingsView: View {
    @AppStorage("researchLoggingEnabled") private var researchLoggingEnabled = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
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
