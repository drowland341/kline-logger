//
//  LivePIDsView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import SwiftUI

private let rpmName = "Engine RPM"

struct LivePIDsView: View {
    var isDemo: Bool
    var connectedDeviceName: String
    var adapterInfo: AdapterInfo?
    var wifiManager: WiFiOBDManager?
    var gpsManager: GPSSpeedManager = GPSSpeedManager()
    var onEnd: () -> Void

    @State private var demoReadings: [PIDReading] = []
    @State private var simulationTask: Task<Void, Never>?
    @State private var isShowingAdapterInfo = false

    private var readings: [PIDReading] {
        isDemo ? demoReadings : (wifiManager?.pidReadings ?? [])
    }

    private var cardReadings: [PIDReading] {
        readings.filter { $0.name != rpmName }
    }

    private var rpmValue: Double {
        guard let reading = readings.first(where: { $0.name == rpmName }), !reading.isFault else { return 0 }
        return Double(reading.value) ?? 0
    }

    private var rpmRange: ClosedRange<Double> {
        let def = PIDLibrary.all.first { $0.pid == 0x09 }
        let lower = def?.gaugeMin ?? 0
        let upper = def?.gaugeMax ?? 8000
        return lower...max(upper, lower + 1)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    ConnectionStatusBanner(isDemo: isDemo, connectedDeviceName: connectedDeviceName)

                    gaugesRow

                    if !isDemo, wifiManager != nil {
                        speedComparisonCard
                    }

                    if cardReadings.isEmpty {
                        emptyState
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 12)], spacing: 12) {
                            ForEach(cardReadings) { reading in
                                PIDCard(reading: reading)
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("Live Data")
            .toolbar {
                if !isDemo, adapterInfo != nil {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Adapter Info") { isShowingAdapterInfo = true }
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(isDemo ? "End Demo" : "Disconnect", role: .destructive, action: onEnd)
                }
            }
            .sheet(isPresented: $isShowingAdapterInfo) {
                if let adapterInfo {
                    AdapterInfoView(deviceName: connectedDeviceName, info: adapterInfo)
                }
            }
        }
        .task {
            if isDemo {
                demoReadings = DemoData.livePIDs
                simulationTask = Task { await runSimulation() }
            }
        }
        .onDisappear {
            simulationTask?.cancel()
        }
    }

    private var gaugesRow: some View {
        HStack(spacing: 16) {
            RadialGaugeView(
                value: rpmValue,
                range: rpmRange,
                title: "Engine RPM",
                unit: "RPM",
                valueText: String(Int(rpmValue))
            )

            RadialGaugeView(
                value: gpsManager.speedMPH ?? 0,
                range: 0...70,
                title: "GPS Speed",
                unit: "mph",
                valueText: gpsManager.speedMPH.map { String(format: "%.1f", $0) } ?? "—"
            )
        }
        .frame(height: 170)
    }

    private var speedComparisonCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Speed Sensor Comparison")
                .font(.subheadline.weight(.semibold))

            HStack {
                Text("GPS")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(gpsManager.speedMPH.map { String(format: "%.1f mph", $0) } ?? "no GPS fix")
                    .font(.body.monospacedDigit())
            }

            Divider()

            ForEach([UInt8(0x0D), UInt8(0x6B)], id: \.self) { pid in
                HStack {
                    Text("PID 0x\(String(format: "%02X", pid)) (candidate)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(rawValueText(for: pid))
                        .font(.body.monospacedDigit())
                }
            }

            Text("Unconfirmed — ride at a few different speeds and see which candidate's raw value tracks GPS speed. Once it's confirmed, tell me the (GPS, raw) pairs and I'll fit a formula.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 12))
    }

    private func rawValueText(for pid: UInt8) -> String {
        guard let data = wifiManager?.rawValuesByPID[pid] else { return "—" }
        let hex = data.map { String(format: "%02X", $0) }.joined(separator: " ")
        let decimal = data.reduce(0) { ($0 << 8) | Int($1) }
        return "\(hex)  (\(decimal))"
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            if wifiManager != nil {
                ProgressView()
                Text("Reading the ECU…")
                    .font(.headline)
                Text("Waiting on the first full sweep of PIDs.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Image(systemName: "hourglass")
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
                Text("Live data coming soon")
                    .font(.headline)
                Text("ECU protocol support is still in progress. Once it's wired up, every PID reading will appear here.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    private func runSimulation() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            demoReadings = demoReadings.map { reading in
                guard !reading.isFault, let base = Double(reading.value) else { return reading }
                let jittered = base * Double.random(in: 0.96...1.04)
                let formatted = reading.unit == "RPM" ? String(format: "%.0f", jittered) : String(format: "%.1f", jittered)
                return PIDReading(name: reading.name, shortCode: reading.shortCode, value: formatted, unit: reading.unit, icon: reading.icon, isFault: reading.isFault, faultReason: reading.faultReason)
            }
        }
    }
}

private struct PIDCard: View {
    let reading: PIDReading

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: reading.isFault ? "exclamationmark.triangle.fill" : reading.icon)
                    .foregroundStyle(reading.isFault ? Color.red : Color.accentColor)
                Spacer()
                Text(reading.shortCode)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
            }

            Text(reading.name)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            if reading.isFault {
                Text(reading.faultReason ?? "Out of range")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.red)
                    .lineLimit(2)
            } else {
                Text("\(reading.value) \(reading.unit)")
                    .font(.title3.monospacedDigit().weight(.semibold))
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(reading.isFault ? Color.red.opacity(0.6) : Color.clear, lineWidth: 1.5)
        )
    }
}

#Preview("Demo") {
    LivePIDsView(isDemo: true, connectedDeviceName: "", onEnd: {})
}

#Preview("Connected, no protocol yet") {
    LivePIDsView(isDemo: false, connectedDeviceName: "Veepeak OBDCheck BLE", onEnd: {})
}
