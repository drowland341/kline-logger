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
    var obdEngine: OBDEngine?
    var gpsManager: GPSSpeedManager = GPSSpeedManager()
    var onConnectWiFi: () -> Void = {}
    var onConnectBluetooth: () -> Void = {}
    var onEnd: () -> Void

    @State private var demoReadings: [PIDReading] = []
    @State private var demoSpeedMPH: Double = 0
    @State private var sweepTask: Task<Void, Never>?
    @State private var jitterTask: Task<Void, Never>?
    @State private var isShowingAdapterInfo = false
    @State private var isShowingSettings = false
    @State private var isLandscape = false
    @AppStorage("speedUnit") private var speedUnit: SpeedUnit = .mph

    /// True once there's a live data source for ECU-backed readings (RPM, PID cards) — demo
    /// mode counts as "connected" for display purposes. False while browsing the dashboard
    /// without an adapter, in which case only GPS Speed has anything to show.
    private var isConnected: Bool {
        isDemo || obdEngine != nil
    }

    private var readings: [PIDReading] {
        if isDemo { return demoReadings }
        if let obdEngine { return obdEngine.pidReadings }
        return Self.placeholderReadings
    }

    /// Shown in place of live readings while browsing the dashboard without an adapter —
    /// same channels, cards, and layout a real connection would show, just zeroed out
    /// instead of hidden behind a blocking "not connected" screen.
    private static let placeholderReadings: [PIDReading] = PIDLibrary.all.filter(\.enabled).map { def in
        PIDReading(name: def.name, shortCode: String(format: "0x%02X", def.pid), value: "0", unit: def.units, icon: def.icon, isFault: false, faultReason: nil)
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
            GeometryReader { outerGeo in
                let currentlyLandscape = outerGeo.size.width > outerGeo.size.height
                ScrollView {
                    VStack(spacing: 12) {
                        ConnectionStatusBanner(isDemo: isDemo, isConnected: isConnected, connectedDeviceName: connectedDeviceName, compact: currentlyLandscape)

                        gaugesSection(containerSize: outerGeo.size)

                        if !isDemo, obdEngine != nil {
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
                .onChange(of: currentlyLandscape, initial: true) { _, newValue in isLandscape = newValue }
            }
            .navigationTitle("Live Data")
            .toolbar {
                if !isDemo, adapterInfo != nil {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Adapter Info") { isShowingAdapterInfo = true }
                    }
                }
                if !isConnected {
                    ToolbarItem(placement: .topBarLeading) {
                        ConnectMenu(onConnectWiFi: onConnectWiFi, onConnectBluetooth: onConnectBluetooth)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isShowingSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(isDemo ? "End Demo" : (obdEngine != nil ? "Disconnect" : "Exit"), role: isConnected ? .destructive : nil, action: onEnd)
                }
            }
            .sheet(isPresented: $isShowingSettings) {
                SettingsView()
            }
            .sheet(isPresented: $isShowingAdapterInfo) {
                if let adapterInfo {
                    AdapterInfoView(deviceName: connectedDeviceName, info: adapterInfo)
                }
            }
        }
        // Landscape phones are short on height, and the tab bar is the single
        // biggest fixed cost in that budget — hiding it while rotated gives the
        // gauges meaningfully more room, at the cost of needing to rotate back
        // to portrait to switch to Codes/PID Scan.
        .toolbar(isLandscape ? .hidden : .visible, for: .tabBar)
        .task {
            if isDemo {
                demoReadings = DemoData.livePIDs
                sweepTask = Task { await runSweep() }
                jitterTask = Task { await runJitter() }
            }
        }
        .onDisappear {
            sweepTask?.cancel()
            jitterTask?.cancel()
        }
    }

    private var rpmGauge: some View {
        RadialGaugeView(
            value: rpmValue,
            range: rpmRange,
            title: "Engine RPM",
            unit: "RPM",
            valueText: String(Int(rpmValue)),
            tickCount: 16,
            tickLabelScale: 1000
        )
    }

    private var speedGauge: some View {
        let speedMPH: Double? = isDemo ? demoSpeedMPH : gpsManager.speedMPH
        let displaySpeed = speedMPH.map { UnitDisplay.speed(mphValue: $0, unit: speedUnit) }
        return RadialGaugeView(
            value: displaySpeed ?? 0,
            range: 0...UnitDisplay.speed(mphValue: 70, unit: speedUnit),
            title: "GPS Speed",
            unit: speedUnit.symbol,
            valueText: displaySpeed.map { String(format: "%.1f", $0) } ?? "—",
            tickCount: 14,
            colorZones: GaugeColorStop.flat()
        )
    }

    /// In portrait, the gauges stack vertically and use the full screen width, making them bigger
    /// than a side-by-side layout would allow. In landscape, they lay out side by side instead.
    /// Each gauge is always drawn upright — this is a plain responsive re-layout (VStack vs.
    /// HStack), not a rotation of the gauge's content.
    private func gaugesSection(containerSize: CGSize) -> some View {
        let horizontalInset: CGFloat = 32
        let spacing: CGFloat = 16
        let availableWidth = max(containerSize.width - horizontalInset, 1)
        let isLandscape = containerSize.width > containerSize.height

        return Group {
            if isLandscape {
                // Only has to clear the compact banner above it now (padding + a
                // caption-sized line), not the taller portrait banner — leaving
                // much more of a short landscape screen for the gauges themselves.
                let availableHeight = max(containerSize.height - 60, 160)
                // Sized by whichever dimension is tighter, so the pair grows to fill the
                // full width when there's horizontal room instead of being stuck at
                // whatever size fits the screen's height alone.
                let diameter = min(availableHeight, (availableWidth - spacing) / 2)
                HStack(spacing: spacing) {
                    rpmGauge
                        .frame(width: diameter, height: diameter)
                    speedGauge
                        .frame(width: diameter, height: diameter)
                }
                .frame(height: availableHeight)
                .frame(maxWidth: .infinity)
            } else {
                VStack(spacing: spacing) {
                    rpmGauge
                        .frame(maxWidth: .infinity)
                    speedGauge
                        .frame(maxWidth: .infinity)
                }
                .frame(width: availableWidth)
            }
        }
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
                Text(gpsManager.speedMPH.map { String(format: "%.1f \(speedUnit.symbol)", UnitDisplay.speed(mphValue: $0, unit: speedUnit)) } ?? "no GPS fix")
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
        guard let data = obdEngine?.rawValuesByPID[pid] else { return "—" }
        let hex = data.map { String(format: "%02X", $0) }.joined(separator: " ")
        let decimal = data.reduce(0) { ($0 << 8) | Int($1) }
        return "\(hex)  (\(decimal))"
    }

    /// Only reachable while genuinely connected but still waiting on the first sweep —
    /// browsing without an adapter shows `placeholderReadings` instead of this.
    private var emptyState: some View {
        VStack(spacing: 8) {
            ProgressView()
            Text("Reading the ECU…")
                .font(.headline)
            Text("Waiting on the first full sweep of PIDs.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    /// Drives the RPM and GPS Speed gauges through a continuous triangle-wave
    /// sweep — up from the bottom of each gauge's range to the top, then back
    /// down, forever — so the demo shows off the full color ramp instead of
    /// idling near one value.
    private func runSweep() async {
        let cyclePeriod = 10.0
        let start = Date()
        while !Task.isCancelled {
            let elapsed = Date().timeIntervalSince(start)
            let t = (elapsed / cyclePeriod).truncatingRemainder(dividingBy: 1)
            // A sine ease rather than a linear ramp, so the sweep glides through
            // the top and bottom of the range instead of reversing direction
            // sharply — reads as fluid motion rather than a mechanical back-and-forth.
            let phase = (1 - cos(2 * Double.pi * t)) / 2

            let rpm = rpmRange.lowerBound + phase * (rpmRange.upperBound - rpmRange.lowerBound)
            demoReadings = demoReadings.map { reading in
                guard reading.name == rpmName else { return reading }
                return PIDReading(name: reading.name, shortCode: reading.shortCode, value: String(format: "%.0f", rpm), unit: reading.unit, icon: reading.icon, isFault: reading.isFault, faultReason: reading.faultReason)
            }
            demoSpeedMPH = phase * 70

            try? await Task.sleep(for: .seconds(1.0 / 60.0))
        }
    }

    /// Small random jitter for the non-gauge PID cards, so they still feel
    /// "live" without competing with the sweep driving the two gauges.
    private func runJitter() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            demoReadings = demoReadings.map { reading in
                guard reading.name != rpmName, !reading.isFault, let base = Double(reading.value) else { return reading }
                let jittered = base * Double.random(in: 0.96...1.04)
                return PIDReading(name: reading.name, shortCode: reading.shortCode, value: String(format: "%.1f", jittered), unit: reading.unit, icon: reading.icon, isFault: reading.isFault, faultReason: reading.faultReason)
            }
        }
    }
}

private struct PIDCard: View {
    let reading: PIDReading
    @AppStorage("temperatureUnit") private var temperatureUnit: TemperatureUnit = .fahrenheit
    @AppStorage("pressureUnit") private var pressureUnit: PressureUnit = .inHg

    private var displayed: (value: String, unit: String) {
        UnitDisplay.convert(value: reading.value, unit: reading.unit, temperature: temperatureUnit, pressure: pressureUnit)
    }

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
                Text("\(displayed.value) \(displayed.unit)")
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

#Preview("Browsing Without Connecting") {
    LivePIDsView(isDemo: false, connectedDeviceName: "", onEnd: {})
}
