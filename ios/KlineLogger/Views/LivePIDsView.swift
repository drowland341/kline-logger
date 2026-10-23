//
//  LivePIDsView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import SwiftUI
import PhotosUI

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
    @AppStorage("temperatureUnit") private var temperatureUnit: TemperatureUnit = .fahrenheit
    @AppStorage("pressureUnit") private var pressureUnit: PressureUnit = .inHg

    // Dash style (Classic/Sport/LCD/Digital) — Sport/LCD/Digital are self-contained panels fed by
    // `cluster()`; Classic keeps using the gauges/card-grid layout below exactly as before.
    @AppStorage("dashStyle") private var dashStyle: DashStyle = .classic
    @AppStorage("vehicleFlip") private var vehicleFlip = false
    @State private var isFullscreen = false
    @State private var fullscreenControlsVisible = true
    @State private var vehicleImage: UIImage?
    @State private var isShowingPhotosPicker = false
    @State private var photosPickerItem: PhotosPickerItem?
    @State private var isImportingVehicle = false
    @State private var vehicleImportError: String?

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

    /// `cardReadings` narrowed to the 6 real pids.json channels Sport/LCD/Digital also show
    /// (Throttle, Coolant, MAP, IAT, Oil, Battery) — used for Classic's full-screen tile grid,
    /// which has no scroll view to fall back on if demo-only extras (O2 Sensor, Fuel Level,
    /// Vehicle Speed — not configured channels) pad it out further than it needs to be.
    private var essentialCardReadings: [PIDReading] {
        let essentialPIDs = Set(DashChannels.all.map(\.pid))
        return cardReadings.filter { reading in essentialPIDs.contains { String(format: "0x%02X", $0) == reading.shortCode } }
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

    /// Yellow at 7800 rpm, red at 8400 rpm, scaled into `rpmRange`'s 0...1
    /// progress space. Paired with `useZoneColor` on the gauge below so the
    /// whole arc snaps to the new zone's color the instant RPM crosses one
    /// of these thresholds, rather than sweeping through a gradient.
    private var rpmColorZones: [GaugeColorStop] {
        let lower = rpmRange.lowerBound
        let span = rpmRange.upperBound - lower
        guard span > 0 else { return GaugeColorStop.standard }
        return [
            GaugeColorStop(location: 0, color: .accentColor),
            GaugeColorStop(location: (7800 - lower) / span, color: .yellow),
            GaugeColorStop(location: (8400 - lower) / span, color: .red),
        ]
    }

    // MARK: - Sport/LCD/Digital cluster data

    /// Raw (native-unit) value for a channel, looked up by PID — like a real connection's
    /// `rawValuesByPID`, this is how Sport/LCD/Digital find a channel regardless of its display name.
    private func doubleValue(forPID pid: UInt8) -> Double? {
        let code = String(format: "0x%02X", pid)
        guard let reading = readings.first(where: { $0.shortCode == code }), !reading.isFault else { return nil }
        return Double(reading.value)
    }

    private func nativeUnits(forPID pid: UInt8) -> String {
        PIDLibrary.all.first(where: { $0.pid == pid })?.units ?? ""
    }

    private func convertedValue(forPID pid: UInt8) -> Double? {
        doubleValue(forPID: pid).map { UnitDisplay.convertDouble($0, nativeUnit: nativeUnits(forPID: pid), temperature: temperatureUnit, pressure: pressureUnit) }
    }

    private func displayUnits(forPID pid: UInt8) -> String {
        UnitDisplay.displayUnit(nativeUnit: nativeUnits(forPID: pid), temperature: temperatureUnit, pressure: pressureUnit)
    }

    /// `spec`'s range and thresholds, written in the channel's native units (°C, kPa), converted
    /// to the user's display units — like Android's `shown(spec)`.
    private func convertedSpec(_ spec: GaugeSpec) -> GaugeSpec {
        let native = nativeUnits(forPID: spec.pid)
        func c(_ v: Double) -> Double { UnitDisplay.convertDouble(v, nativeUnit: native, temperature: temperatureUnit, pressure: pressureUnit) }
        var converted = spec
        converted.min = c(spec.min)
        converted.max = c(spec.max)
        converted.thresholds = Thresholds(
            warnHigh: spec.thresholds.warnHigh.map(c), critHigh: spec.thresholds.critHigh.map(c),
            warnLow: spec.thresholds.warnLow.map(c), critLow: spec.thresholds.critLow.map(c)
        )
        return converted
    }

    /// Mirrors Android's `caption(spec)`: why a channel has no value to show.
    private func captionText(forPID pid: UInt8) -> String? {
        guard let def = PIDLibrary.all.first(where: { $0.pid == pid }) else {
            return String(format: "PID 0x%02X not in pids.json", pid)
        }
        let code = String(format: "0x%02X", pid)
        if let reading = readings.first(where: { $0.shortCode == code }), reading.isFault {
            return reading.faultReason
        }
        return def.enabled ? nil : String(format: "Unconfirmed PID 0x%02X", pid)
    }

    private func readout(for spec: GaugeSpec) -> SportReadout {
        let shown = convertedSpec(spec)
        let value = convertedValue(forPID: spec.pid)
        let check = shown.thresholds.check(value)
        return SportReadout(
            label: spec.label,
            text: value.map { formatValue($0, shown.decimals, step: shown.step) } ?? "—",
            units: displayUnits(forPID: spec.pid),
            level: check.level,
            fraction: value.map { shown.fraction($0) },
            note: value == nil ? captionText(forPID: spec.pid) : nil
        )
    }

    /// Everything the Sport/LCD/Digital styles show, built fresh from the current readings/GPS —
    /// the dashboard-screen equivalent of Android's inline `cluster()`.
    private func cluster() -> ClusterData {
        let rpmSpec = convertedSpec(DashChannels.rpm)
        let rpm = convertedValue(forPID: DashChannels.rpm.pid)
        let speedMPH: Double? = isDemo ? demoSpeedMPH : gpsManager.speedMPH
        let speed = speedMPH.map { UnitDisplay.speed(mphValue: $0, unit: speedUnit) }
        // pid 0xFF is a placeholder — GPS speed isn't one of pids.json's ECU channels, so there's
        // nothing to look up by PID for it; the spec only needs a range here.
        let speedSpec = GaugeSpec(pid: 0xFF, label: "SPEED", min: 0, max: UnitDisplay.speed(mphValue: 70, unit: speedUnit), majorTick: speedUnit == .kmh ? 20 : 10)
        let iat = convertedValue(forPID: DashChannels.iat.pid)
        return ClusterData(
            speedSpec: speedSpec,
            speed: speed,
            speedText: speed.map { String(format: "%.0f", $0) } ?? "—",
            speedUnits: speedUnit.symbol,
            rpmSpec: rpmSpec,
            rpm: rpm,
            rpmText: rpm.map { formatValue($0, 0, step: rpmSpec.step).replacingOccurrences(of: ",", with: "") } ?? "—",
            rpmUnits: "RPM",
            throttle: readout(for: DashChannels.throttle),
            coolant: readout(for: DashChannels.coolant),
            oil: readout(for: DashChannels.oil),
            battery: readout(for: DashChannels.battery),
            map: readout(for: DashChannels.map),
            footer: "IAT " + (iat.map { "\(Int($0.rounded()))\(displayUnits(forPID: DashChannels.iat.pid))" } ?? "—")
        )
    }

    @ViewBuilder
    private func dashboardContent(edgeToEdge: Bool) -> some View {
        let data = cluster()
        switch dashStyle {
        case .classic:
            EmptyView()
        case .sport:
            SportDashboard(
                speedSpec: data.speedSpec, speed: data.speed, speedText: data.speedText, speedUnits: data.speedUnits,
                rpmSpec: data.rpmSpec, rpm: data.rpm, rpmText: data.rpmText, rpmUnits: data.rpmUnits,
                ring: data.throttle, side: [data.coolant, data.oil, data.battery, data.map], footer: data.footer,
                edgeToEdge: edgeToEdge
            )
        case .lcd:
            LcdDashboard(data: data, edgeToEdge: edgeToEdge)
        case .digital:
            DigitalDashboard(data: data, edgeToEdge: edgeToEdge, vehicleImage: vehicleImage.map { Image(uiImage: $0) }, flipVehicle: vehicleFlip)
        }
    }

    private var stylePickerRow: some View {
        HStack(spacing: 8) {
            Picker("Gauge Style", selection: $dashStyle) {
                ForEach(DashStyle.allCases, id: \.self) { style in
                    Text(style.label).tag(style)
                }
            }
            .pickerStyle(.segmented)

            if dashStyle == .digital {
                vehicleMenu
            }

            Button {
                isFullscreen = true
            } label: {
                Image(systemName: "arrow.up.left.and.arrow.down.right")
            }
            .buttonStyle(.bordered)
        }
    }

    private var vehicleMenu: some View {
        Menu {
            // A `PhotosPicker` embedded directly as a Menu row doesn't reliably present its sheet
            // (the Menu's own dismissal appears to beat it to the tap) — a plain Button that flips
            // `isShowingPhotosPicker`, paired with the `.photosPicker(isPresented:)` modifier below,
            // is the documented, reliable way to trigger it from inside another control.
            Button {
                isShowingPhotosPicker = true
            } label: {
                Label("Choose Picture…", systemImage: "photo")
            }
            Button {
                vehicleFlip.toggle()
            } label: {
                Label(vehicleFlip ? "Flip Back" : "Flip Left/Right", systemImage: "arrow.left.and.right")
            }
            Button(role: .destructive) {
                VehicleImageStore.clear()
                vehicleImage = nil
            } label: {
                Label("Use Built-in Drawing", systemImage: "arrow.uturn.backward")
            }
            .disabled(vehicleImage == nil)
        } label: {
            if isImportingVehicle {
                ProgressView()
            } else {
                Image(systemName: "photo.on.rectangle")
            }
        }
        .buttonStyle(.bordered)
        .disabled(isImportingVehicle)
        .photosPicker(isPresented: $isShowingPhotosPicker, selection: $photosPickerItem, matching: .images)
    }

    var body: some View {
        // Full screen is a direct content swap, not a `.fullScreenCover` — a modal cover's
        // separate presentation context is where the overlay's Picker/Button stopped receiving
        // taps (background taps still worked; only that cover's own controls didn't), so this
        // sidesteps whatever that was rather than chasing it further. The demo sweep/jitter tasks
        // and vehicle-picture state live on this wrapper (not inside either branch) so toggling
        // full screen doesn't tear them down and restart them.
        Group {
            if isFullscreen {
                fullscreenDashboard
            } else {
                normalContent
            }
        }
        .task {
            vehicleImage = VehicleImageStore.load()
            if isDemo {
                demoReadings = DemoData.livePIDs
                sweepTask = Task { await runSweep() }
                jitterTask = Task { await runJitter() }
            }
        }
        .onChange(of: photosPickerItem) { _, newItem in
            guard let newItem else { return }
            isImportingVehicle = true
            Task {
                defer {
                    isImportingVehicle = false
                    photosPickerItem = nil
                }
                do {
                    guard let data = try await newItem.loadTransferable(type: Data.self) else { return }
                    vehicleImage = try await VehicleImageStore.import(data: data)
                } catch {
                    vehicleImportError = error.localizedDescription
                }
            }
        }
        .alert("Couldn't Use That Picture", isPresented: Binding(get: { vehicleImportError != nil }, set: { if !$0 { vehicleImportError = nil } })) {
            Button("OK") { vehicleImportError = nil }
        } message: {
            Text(vehicleImportError ?? "")
        }
        .onDisappear {
            sweepTask?.cancel()
            jitterTask?.cancel()
        }
    }

    private var normalContent: some View {
        NavigationStack {
            GeometryReader { outerGeo in
                let currentlyLandscape = outerGeo.size.width > outerGeo.size.height
                ScrollView {
                    VStack(spacing: 12) {
                        ConnectionStatusBanner(isDemo: isDemo, isConnected: isConnected, connectedDeviceName: connectedDeviceName, compact: currentlyLandscape)

                        stylePickerRow

                        if dashStyle == .classic {
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
                        } else {
                            // Sport/LCD/Digital draw every channel themselves, like Classic's tach
                            // + card grid combined — no separate grid underneath for these styles.
                            dashboardContent(edgeToEdge: false)
                                .frame(height: max(outerGeo.size.height - 220, 320))
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
    }

    private var rpmGauge: some View {
        RadialGaugeView(
            value: rpmValue,
            range: rpmRange,
            title: "Engine RPM",
            unit: "RPM",
            valueText: String(Int(rpmValue)),
            tickCount: 16,
            tickLabelScale: 1000,
            colorZones: rpmColorZones,
            useZoneColor: true
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

    /// The active dash style, edge to edge, with an auto-hiding style picker + exit button —
    /// mirrors Android's `FullscreenFrame`. Classic shows just the RPM/Speed gauges plus compact
    /// digit+label tiles (not full `PIDCard`s) for the other channels — full `PIDCard`s plus two
    /// big gauges don't fit a landscape phone screen without scrolling, and there's no scroll view
    /// here (full screen is meant to read at a glance on a handlebar mount).
    private var fullscreenDashboard: some View {
        ZStack {
            // Only the background and the gauges themselves go fully edge to edge. The controls
            // overlay below deliberately does NOT ignore the safe area — in portrait, a device's
            // top notch/Dynamic Island sits right where that overlay would otherwise be drawn,
            // making its style picker and Exit button sit underneath the cutout and become
            // unreachable.
            (dashStyle == .classic ? Color(.systemBackground) : Color.black)
                .ignoresSafeArea()

            // A dedicated invisible layer owns the tap-to-reveal-controls gesture — not the
            // dashboard content itself. The Sport/LCD/Digital dashboards are `Canvas`-drawn, and
            // a `Canvas` given its own gesture apparently gets composited/hit-tested ahead of
            // ordinary SwiftUI controls regardless of ZStack order: with the gesture living on
            // the dashboard layer, every tap on the controls overlay below (Exit Full Screen, the
            // style picker) was swallowed here instead of reaching them, no matter the gesture
            // type tried. Making the dashboard non-interactive and catching taps on a plain
            // `Color.clear` instead sidesteps that entirely.
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation { fullscreenControlsVisible.toggle() }
                }

            GeometryReader { geo in
                Group {
                    if dashStyle == .classic {
                        // RPM and Speed side by side at an explicit diameter (not stacked
                        // full-width the way `gaugesSection` lays them out for the normal
                        // scrollable view, and not just height-capped either — `RadialGaugeView`
                        // is aspectRatio(1, .fit), so a height cap alone still lets it grow wider
                        // than its half of the HStack and overflow past the screen edges).
                        // Smaller diameter (0.42 rather than 0.5) and tighter spacing leave
                        // guaranteed room for the tile row below, instead of the gauges claiming
                        // enough height that the tiles ran off the bottom of the screen.
                        let diameter = Swift.min(geo.size.height * 0.42, (geo.size.width - 32 - 12) / 2)
                        let gaugesWidth = diameter * 2 + 12
                        VStack(spacing: 6) {
                            HStack(spacing: 12) {
                                rpmGauge.frame(width: diameter, height: diameter)
                                speedGauge.frame(width: diameter, height: diameter)
                            }
                            .frame(maxWidth: .infinity)

                            if !essentialCardReadings.isEmpty {
                                // Centered under the gauges (matching their combined width)
                                // rather than stretched across the full screen width.
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 80), spacing: 6)], spacing: 6) {
                                    ForEach(essentialCardReadings) { reading in
                                        CompactReadoutTile(reading: reading)
                                    }
                                }
                                .frame(width: gaugesWidth)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal)
                        .padding(.top, 8)
                    } else {
                        dashboardContent(edgeToEdge: true)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .allowsHitTesting(false)

            if !isConnected {
                VStack {
                    HStack(spacing: 8) {
                        Circle().fill(.red).frame(width: 8, height: 8)
                        Text(isDemo ? "Demo" : "Disconnected")
                            .font(.caption.weight(.semibold))
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.75), in: Capsule())
                    .foregroundStyle(.white)
                    Spacer()
                }
                .padding(.top, 12)
                .allowsHitTesting(false)
            }

            if fullscreenControlsVisible {
                VStack {
                    HStack {
                        Spacer()
                        HStack(spacing: 8) {
                            Picker("Gauge Style", selection: $dashStyle) {
                                ForEach(DashStyle.allCases, id: \.self) { style in
                                    Text(style.label).tag(style)
                                }
                            }
                            .pickerStyle(.segmented)
                            .frame(width: 280)
                            Button("Exit Full Screen") { isFullscreen = false }
                                .buttonStyle(.borderedProminent)
                                .fixedSize()
                        }
                        .padding(8)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    }
                    Spacer()
                }
                .padding(12)
                // No `.transition`/`.zIndex` here — with both, taps on this Picker/Button were
                // reliably swallowed (reproducibly, across many test runs) even though the
                // overlay was visually on top and correctly positioned. Removing them is what
                // actually fixed it; a plain insert/remove without a custom transition still
                // animates implicitly and was indistinguishable from the transitioned version
                // visually, so nothing is lost by leaving it out.
            }
        }
        .task(id: fullscreenControlsVisible) {
            guard fullscreenControlsVisible else { return }
            try? await Task.sleep(for: .seconds(4))
            withAnimation { fullscreenControlsVisible = false }
        }
        .onAppear { fullscreenControlsVisible = true }
        // Always hidden here, regardless of orientation — unlike `normalContent`'s landscape-only
        // hide, full screen should never show the tab bar (it was left showing in portrait, eating
        // a chunk of the screen this mode is supposed to reclaim entirely).
        .toolbar(.hidden, for: .tabBar)
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

/// A minimal digit + label readout — no icon, no padding-heavy card chrome — for Classic's
/// full-screen view, where a whole grid of `PIDCard`s plus the RPM/Speed gauges doesn't fit a
/// landscape phone screen without scrolling (and full screen has no scroll view to fall back on).
private struct CompactReadoutTile: View {
    let reading: PIDReading
    @AppStorage("temperatureUnit") private var temperatureUnit: TemperatureUnit = .fahrenheit
    @AppStorage("pressureUnit") private var pressureUnit: PressureUnit = .inHg

    private var displayed: (value: String, unit: String) {
        UnitDisplay.convert(value: reading.value, unit: reading.unit, temperature: temperatureUnit, pressure: pressureUnit)
    }

    var body: some View {
        VStack(spacing: 2) {
            Text(reading.isFault ? "—" : "\(displayed.value)\(displayed.unit)")
                .font(.system(.title3, design: .rounded, weight: .semibold))
                .foregroundStyle(reading.isFault ? .red : .primary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(reading.name)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 8))
    }
}

#Preview("Demo") {
    LivePIDsView(isDemo: true, connectedDeviceName: "", onEnd: {})
}

#Preview("Browsing Without Connecting") {
    LivePIDsView(isDemo: false, connectedDeviceName: "", onEnd: {})
}
