//
//  SportDashboardView.swift
//  KlineLogger
//
//  The "Sport" dash style: a black glass cluster with twin mirrored pods (speed and RPM), each a
//  tall bezel arc with ticks, a needle, lit segments and a perspective grid floor behind a big
//  yellow readout. Ported from the Android app's SportGauges.kt.
//

import SwiftUI

private enum Sport {
    static let panel = Color(hex: 0x040506)
    static let tunnel = Color(hex: 0x2C3238)
    static let bezel = Color(hex: 0xF1F4F7)
    static let bezelShade = Color(hex: 0x6F7781)
    static let tick = Color(hex: 0xDCE1E7)
    static let tickFaint = Color(hex: 0x59616B)
    static let yellow = Color(hex: 0xFFE135)
    static let amber = Color(hex: 0xFFB000)
    static let amberDeep = Color(hex: 0xFF7A00)
    static let grid = Color(hex: 0xFFC21A)
    static let red = Color(hex: 0xFF3B30)
    static let unlit = Color(hex: 0x231A0A)
    static let track = Color(hex: 0x1A1E23)
    static let label = Color(hex: 0xC9CFD6)
    static let edge = Color(hex: 0x1C2026)
}

private let sportStart: CGFloat = 115      // bottom of the arc; the value sweeps up to start + sweep at the top
private let sportSweep: CGFloat = 130
private let sportSegments = 30

private func sportTint(_ level: Level, normal: Color) -> Color {
    switch level {
    case .normal: return normal
    case .warning: return Sport.amberDeep
    case .critical: return Sport.red
    }
}

/// One cluster pod: the bezel arc on the left with the readout to its right, or the mirror image
/// when `mirrored` (so two pods face each other like a twin-screen dash).
struct SportPod: View {
    var spec: GaugeSpec
    var value: Double?
    var readout: String
    var units: String
    var mirrored: Bool

    var body: some View {
        Canvas { context, size in
            draw(context: &context, size: size)
        }
        .accessibilityLabel("\(spec.label) \(readout) \(units)")
        .animation(.easeOut(duration: 0.22), value: value)
    }

    private func draw(context: inout GraphicsContext, size: CGSize) {
        let fraction = spec.fraction(value ?? spec.min)
        let w = size.width
        let h = size.height
        let r = Swift.min(h * 0.46, w * 0.6)
        let c = CGPoint(x: r * 1.12, y: h * 0.5)   // arc centre, unmirrored
        func mx(_ x: CGFloat) -> CGFloat { mirrored ? w - x : x }

        let t = spec.thresholds
        let warnF = t.warnHigh.map { spec.fraction($0) }
        let critF = t.critHigh.map { spec.fraction($0) }
        let tint = sportTint(t.check(value).level, normal: Sport.yellow)

        context.drawMirrored(around: w / 2, mirrored: mirrored) { ctx in
            // Tunnel: a soft grey glow behind the readout, fading to black.
            ctx.fill(
                Path(CGRect(origin: .zero, size: size)),
                with: .radialGradient(
                    Gradient(colors: [Sport.tunnel, Sport.panel.opacity(0)]),
                    center: CGPoint(x: c.x + r * 0.6, y: c.y - r * 0.25), startRadius: 0, endRadius: r * 1.15
                )
            )

            // Perspective grid floor inside the arc.
            ctx.drawLayer { inner in
                inner.clip(to: Path(ellipseIn: CGRect(x: c.x - r * 0.83, y: c.y - r * 0.83, width: r * 1.66, height: r * 1.66)))
                let horizon = c.y + r * 0.08
                let bottom = c.y + r * 0.85
                let vp = CGPoint(x: c.x + r * 0.55, y: horizon - r * 0.4)
                let fade = GraphicsContext.Shading.linearGradient(
                    Gradient(colors: [Sport.grid.opacity(0), Sport.grid.opacity(0.6)]),
                    startPoint: CGPoint(x: c.x, y: horizon), endPoint: CGPoint(x: c.x, y: bottom)
                )
                for k in -12...12 {
                    let bx = vp.x + CGFloat(k) * r * 0.2
                    let s = (bottom - horizon) / (bottom - vp.y)
                    var line = Path()
                    line.move(to: CGPoint(x: bx + (vp.x - bx) * s, y: horizon))
                    line.addLine(to: CGPoint(x: bx, y: bottom))
                    inner.stroke(line, with: fade, lineWidth: 1.2)
                }
                for j in 1...9 {
                    let f = CGFloat(j) / 9
                    let y = horizon + (bottom - horizon) * f * f
                    var line = Path()
                    line.move(to: CGPoint(x: c.x - r, y: y))
                    line.addLine(to: CGPoint(x: c.x + r, y: y))
                    inner.stroke(line, with: .color(Sport.grid.opacity(0.06 + 0.5 * Double(f))), lineWidth: 1.2)
                }
            }

            // Lit segments: amber, deepening toward the warning zone, red past the redline.
            let seg = sportSweep / CGFloat(sportSegments)
            let gap: CGFloat = 1.8
            for i in 0..<sportSegments {
                let f0 = Double(i) / Double(sportSegments)
                let zone: Color
                if let critF, f0 >= critF { zone = Sport.red }
                else if let warnF, f0 >= warnF { zone = Sport.amberDeep }
                else { zone = lerpColor(Sport.amberDeep, Sport.amber, f0) }
                let lit = fraction * Double(sportSegments) > Double(i) + 0.001
                let color: Color
                if lit { color = zone }
                else if let critF, f0 >= critF { color = Sport.red.opacity(0.22) }
                else { color = Sport.unlit }
                ctx.fill(ringSegment(c, inner: r * 0.70, outer: r * 0.82, start: sportStart + CGFloat(i) * seg + gap / 2, sweep: seg - gap), with: .color(color))
            }

            // Tick band between a thin inner rail and the bezel.
            let rail = CGSize(width: r * 1.72, height: r * 1.72)
            ctx.stroke(
                Path { $0.addArc(center: c, radius: rail.width / 2, startAngle: .degrees(sportStart), endAngle: .degrees(sportStart + sportSweep), clockwise: false) },
                with: .color(Sport.tickFaint), lineWidth: 1
            )
            let major = spec.majorTick > 0 ? spec.majorTick : (spec.max - spec.min) / 7
            let minor = major / 5
            var v = spec.min
            var n = 0
            while v <= spec.max + 1e-6 {
                let a = sportStart + sportSweep * CGFloat(spec.fraction(v))
                let isMajor = n % 5 == 0
                let innerR = isMajor ? r * 0.86 : r * 0.90
                var tickPath = Path()
                tickPath.move(to: polar(c, innerR, a))
                tickPath.addLine(to: polar(c, r * 0.96, a))
                ctx.stroke(tickPath, with: .color(isMajor ? Sport.tick : Sport.tick.opacity(0.7)), style: StrokeStyle(lineWidth: isMajor ? 3 : 1.6, lineCap: .round))
                v += minor
                n += 1
            }

            // Glossy bezel with a dark lip outside it.
            ctx.stroke(
                Path { $0.addArc(center: c, radius: r * 1.04, startAngle: .degrees(sportStart - 5), endAngle: .degrees(sportStart + sportSweep + 5), clockwise: false) },
                with: .color(Sport.edge), style: StrokeStyle(lineWidth: 6, lineCap: .round)
            )
            ctx.stroke(
                Path { $0.addArc(center: c, radius: r, startAngle: .degrees(sportStart - 5), endAngle: .degrees(sportStart + sportSweep + 5), clockwise: false) },
                with: .linearGradient(Gradient(colors: [Sport.bezelShade, Sport.bezel, Sport.bezelShade]), startPoint: CGPoint(x: c.x, y: c.y - r), endPoint: CGPoint(x: c.x, y: c.y + r)),
                style: StrokeStyle(lineWidth: 4, lineCap: .round)
            )

            // Needle, with a soft glow under it.
            let a = sportStart + sportSweep * CGFloat(fraction)
            var glow = Path(); glow.move(to: polar(c, r * 0.56, a)); glow.addLine(to: polar(c, r * 0.99, a))
            ctx.stroke(glow, with: .color(tint.opacity(0.25)), style: StrokeStyle(lineWidth: 12, lineCap: .round))
            var needle = Path(); needle.move(to: polar(c, r * 0.58, a)); needle.addLine(to: polar(c, r * 0.98, a))
            ctx.stroke(needle, with: .color(tint), style: StrokeStyle(lineWidth: 5, lineCap: .round))
        }

        // Text is drawn unmirrored, at mirrored positions.
        let labelPx = r * 0.11
        var v = spec.min
        while v <= spec.max + 1e-6 {
            let p = polar(c, r * 1.13, sportStart + sportSweep * CGFloat(spec.fraction(v)))
            let label = formatValue(v / spec.tickDivisor, 0).replacingOccurrences(of: ",", with: "")
            context.drawFitted(label, at: CGPoint(x: mx(p.x), y: p.y), size: labelPx, color: Sport.label, anchor: .center, weight: .medium, maxWidth: r * 0.4)
            v += spec.majorTick > 0 ? spec.majorTick : (spec.max - spec.min) / 7
        }
        let left = c.x - r * 0.5
        let right = w - 8
        let cx = (left + right) / 2
        context.drawFitted(readout, at: CGPoint(x: mx(cx), y: c.y - r * 0.12), size: h * 0.36, color: tint, anchor: .center, maxWidth: (right - left) * 0.95)
        context.drawFitted(units, at: CGPoint(x: mx(cx), y: c.y + r * 0.3), size: h * 0.07, color: Sport.label, anchor: .center, weight: .medium, maxWidth: (right - left) * 0.9)
    }
}

/// A round mini-gauge, like the small ring on a twin-screen dash: a 270-degree track with the value inside.
struct SportRing: View {
    var readout: SportReadout

    var body: some View {
        Canvas { context, size in
            draw(context: &context, size: size)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel("\(readout.label) \(readout.text) \(readout.units)")
        .animation(.easeOut(duration: 0.22), value: readout.fraction)
    }

    private func draw(context: inout GraphicsContext, size: CGSize) {
        let animated = readout.fraction ?? 0
        let stroke = size.width.isFinite ? Swift.min(size.width, size.height) * 0.07 : 0
        let radius = Swift.min(size.width, size.height) / 2 - stroke
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let tint = sportTint(readout.level, normal: Sport.yellow)

        context.fill(
            Path(ellipseIn: CGRect(origin: CGPoint(x: c.x - radius * 0.8, y: c.y - radius * 0.8), size: CGSize(width: radius * 1.6, height: radius * 1.6))),
            with: .radialGradient(Gradient(colors: [Sport.tunnel, Sport.panel]), center: c, startRadius: 0, endRadius: radius)
        )
        context.stroke(
            Path { $0.addArc(center: c, radius: radius, startAngle: .degrees(135), endAngle: .degrees(405), clockwise: false) },
            with: .color(Sport.track), style: StrokeStyle(lineWidth: stroke, lineCap: .round)
        )
        if readout.fraction != nil, animated > 0.002 {
            context.stroke(
                Path { $0.addArc(center: c, radius: radius, startAngle: .degrees(135), endAngle: .degrees(135 + 270 * animated), clockwise: false) },
                with: .conicGradient(Gradient(colors: [Sport.amberDeep, Sport.amber, Sport.amberDeep]), center: c, angle: .degrees(0)),
                style: StrokeStyle(lineWidth: stroke, lineCap: .round)
            )
        }
        for i in 0...10 {
            let a = 135.0 + 27.0 * Double(i)
            var tick = Path(); tick.move(to: polar(c, radius * 0.72, a)); tick.addLine(to: polar(c, radius * 0.8, a))
            context.stroke(tick, with: .color(Sport.tick.opacity(0.8)), lineWidth: 1.5)
        }
        context.drawFitted(readout.text, at: CGPoint(x: c.x, y: c.y - radius * 0.08), size: radius * 0.62, color: tint, anchor: .center, maxWidth: radius * 1.2)
        context.drawFitted(readout.units, at: CGPoint(x: c.x, y: c.y + radius * 0.38), size: radius * 0.2, color: Sport.label, anchor: .center, weight: .medium, maxWidth: radius)
        context.drawFitted(readout.label, at: CGPoint(x: c.x, y: c.y + radius * 0.86), size: radius * 0.17, color: Sport.label, anchor: .center, weight: .medium, maxWidth: radius * 1.2)
    }
}

/// A compact side readout: label and value on one line, a thin segmented bar under it.
struct SportReadoutRow: View {
    var readout: SportReadout

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .lastTextBaseline) {
                Text(readout.label)
                    .font(.system(size: 14, design: .rounded))
                    .tracking(1)
                    .foregroundStyle(Sport.label)
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(readout.text)
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(sportTint(readout.level, normal: .white))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .layoutPriority(1)
                Text(readout.units)
                    .font(.system(size: 13, design: .rounded))
                    .foregroundStyle(Sport.label)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .fixedSize(horizontal: true, vertical: false)
            }
            if let note = readout.note {
                Text(note)
                    .font(.system(size: 11))
                    .foregroundStyle(Sport.tickFaint)
                    .lineLimit(1)
                    .truncationMode(.tail)
            } else {
                Canvas { context, size in
                    let n = 16
                    let gap: CGFloat = 2
                    let segW = (size.width - gap * CGFloat(n - 1)) / CGFloat(n)
                    let lit = (readout.fraction ?? 0) * Double(n)
                    for i in 0..<n {
                        let color = Double(i) < lit ? sportTint(readout.level, normal: lerpColor(Sport.amberDeep, Sport.amber, Double(i) / Double(n))) : Sport.track
                        context.fill(Path(CGRect(x: CGFloat(i) * (segW + gap), y: 0, width: segW, height: size.height)), with: .color(color))
                    }
                }
                .frame(height: 5)
            }
        }
        .animation(.easeOut(duration: 0.22), value: readout.fraction)
    }
}

/**
 * The whole Sport cluster. Landscape: ring and side readouts flank twin pods (speed left, RPM right).
 * Portrait: the pods on top, the ring and readouts under them.
 */
struct SportDashboard: View {
    var speedSpec: GaugeSpec
    var speed: Double?
    var speedText: String
    var speedUnits: String
    var rpmSpec: GaugeSpec
    var rpm: Double?
    var rpmText: String
    var rpmUnits: String
    var ring: SportReadout
    var side: [SportReadout]
    var footer: String
    var edgeToEdge: Bool = false   // full screen: no rounded card edge, just the black panel

    var body: some View {
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            let split = (side.count + 1) / 2

            Group {
                if landscape {
                    HStack(spacing: 12) {
                        VStack {
                            SportRing(readout: ring)
                                .frame(maxWidth: geo.size.width * 0.9)
                            ForEach(Array(side.prefix(split - 1).enumerated()), id: \.offset) { _, r in
                                SportReadoutRow(readout: r)
                            }
                            Spacer(minLength: 0)
                        }
                        .frame(maxHeight: .infinity)
                        .frame(width: geo.size.width * 0.22)

                        pods
                            .frame(maxWidth: .infinity, maxHeight: .infinity)

                        VStack(spacing: 10) {
                            ForEach(Array(side.dropFirst(split - 1).enumerated()), id: \.offset) { _, r in
                                SportReadoutRow(readout: r)
                            }
                            Spacer(minLength: 0)
                            footerRow
                        }
                        .frame(width: geo.size.width * 0.22)
                    }
                } else {
                    VStack(spacing: 12) {
                        pods.frame(maxHeight: geo.size.height * 0.55)
                        HStack(spacing: 16) {
                            SportRing(readout: ring)
                                .frame(width: geo.size.width * 0.32)
                            VStack(spacing: 8) {
                                ForEach(Array(side.enumerated()), id: \.offset) { _, r in
                                    SportReadoutRow(readout: r)
                                }
                            }
                            .frame(maxWidth: .infinity)
                        }
                        footerRow
                    }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .background(edgeToEdge ? Sport.panel : nil)
        }
        // Padding goes outside the GeometryReader, so `geo.size` already reflects the inset space —
        // applying it inside (after children were already sized from the full geo.size) pushed
        // content past the edges of whatever fixed-size container this dashboard sits in.
        .padding(edgeToEdge ? 16 : 12)
        .background(edgeToEdge ? Sport.panel : Sport.panel)
        .clipShape(RoundedRectangle(cornerRadius: edgeToEdge ? 0 : 28))
        .overlay(
            RoundedRectangle(cornerRadius: edgeToEdge ? 0 : 28)
                .strokeBorder(edgeToEdge ? Color.clear : Sport.edge, lineWidth: 1)
        )
    }

    private var pods: some View {
        HStack(spacing: 0) {
            SportPod(spec: speedSpec, value: speed, readout: speedText, units: speedUnits, mirrored: false)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Rectangle()
                .fill(Sport.edge)
                .frame(width: 2)
                .padding(.vertical, 24)
            SportPod(spec: rpmSpec, value: rpm, readout: rpmText, units: rpmUnits, mirrored: true)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var footerRow: some View {
        TimelineView(.periodic(from: .now, by: 5)) { context in
            HStack {
                Text(footer)
                    .font(.system(size: 18, design: .rounded))
                    .foregroundStyle(Sport.label)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(clockString(context.date))
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
            }
        }
    }
}
