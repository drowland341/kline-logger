//
//  RadialGaugeView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A keyframe in a gauge's color ramp: `location` is a fraction (0...1) of
/// the gauge's full range, `color` is what the dial shows exactly at that
/// fraction. Between keyframes, color blends continuously in HSB space
/// (shortest way around the hue wheel) rather than straight RGB — RGB
/// blending between hues as different as blue and yellow dips through a
/// muddy, desaturated gray-green along the way, while an HSB blend stays
/// vivid and reads as the familiar cool-to-hot ramp (blue → cyan → green →
/// yellow → orange → red) without needing orange as its own keyframe.
struct GaugeColorStop {
    var location: Double
    var color: Color

    /// Normal (safe) blue for most of the range, easing into cautionary
    /// yellow and finally red right at the edge. Tuned for the Engine RPM
    /// gauge's 0-8000 range specifically: yellow starts right at 7200 rpm
    /// (location 0.90), with red reserved for the last few hundred rpm
    /// before the limit.
    ///
    /// The blue→yellow and yellow→red blends are kept narrow relative to
    /// their flat zones rather than wide: an HSB blend between hues this
    /// different necessarily passes through the hue in between (green
    /// between blue and yellow, orange between yellow and red), and a wide
    /// zone would make that in-between hue read as its own meaningful color
    /// — green in particular, sitting right before the caution zone, looks
    /// backwards. Narrow zones keep it a quick wipe instead of a lingering band.
    static let standard: [GaugeColorStop] = [
        GaugeColorStop(location: 0.00, color: .accentColor),
        GaugeColorStop(location: 0.90, color: .accentColor),
        GaugeColorStop(location: 0.92, color: .yellow),
        GaugeColorStop(location: 0.96, color: .yellow),
        GaugeColorStop(location: 0.98, color: .red),
        GaugeColorStop(location: 1.00, color: .red),
    ]

    /// A single color across the whole range — for gauges like GPS Speed
    /// where there's no realistic "danger zone," so a color sweep would
    /// just be a confusing signal that isn't actually there.
    static func flat(_ color: Color = .accentColor) -> [GaugeColorStop] {
        [GaugeColorStop(location: 0.00, color: color), GaugeColorStop(location: 1.00, color: color)]
    }

    static func color(at progress: Double, in stops: [GaugeColorStop]) -> Color {
        guard let first = stops.first else { return .accentColor }
        guard progress > first.location else { return first.color }
        for (lower, upper) in zip(stops, stops.dropFirst()) {
            if progress <= upper.location {
                guard upper.location > lower.location else { return upper.color }
                let t = (progress - lower.location) / (upper.location - lower.location)
                return lerp(lower.color, upper.color, t)
            }
        }
        return stops.last?.color ?? .accentColor
    }

    /// Builds a `Gradient` whose stop locations are scaled into the 0...1
    /// space `.conicGradient` expects (a full 360° revolution). It's densely
    /// sampled through the same HSB `color(at:in:)` ramp used elsewhere
    /// (rather than handing SwiftUI the raw keyframes to blend on its own),
    /// so the rendered arc matches the vivid HSB blend pixel-for-pixel
    /// instead of SwiftUI's own flat RGB interpolation between stops.
    static func gradient(for stops: [GaugeColorStop], sweepFraction: Double, opacity: Double = 1) -> Gradient {
        guard let first = stops.first, let last = stops.last else { return Gradient(colors: [.accentColor]) }

        let sampleCount = 64
        var mapped: [Gradient.Stop] = (0...sampleCount).map { i in
            let t = Double(i) / Double(sampleCount)
            return Gradient.Stop(color: color(at: t, in: stops).opacity(opacity), location: t * sweepFraction)
        }
        // `.conicGradient` always cycles through a full 360°, but the dial only
        // occupies `sweepFraction` of that. A round line cap bulges a few degrees
        // past the arc's start/end, which — without this — samples color from the
        // *other* edge through that wraparound (e.g. the start cap picking up red
        // from the far/high end). Pin the unused remainder of the circle to each
        // edge's own color so a cap can never bleed across the seam.
        let margin = 0.02
        mapped.append(.init(color: last.color.opacity(opacity), location: sweepFraction + margin))
        mapped.append(.init(color: first.color.opacity(opacity), location: 1.0 - margin))
        mapped.append(.init(color: first.color.opacity(opacity), location: 1.0))
        return Gradient(stops: mapped)
    }

    /// Blends two colors in HSB space, taking the shorter way around the hue
    /// wheel, so intermediate hues stay saturated instead of sliding through
    /// a desaturated midpoint the way a plain RGB blend would. But when the
    /// two hues are far enough apart (e.g. blue to yellow, ~150° apart) that
    /// "the shorter way" still cuts through an unrelated, misleadingly-named
    /// hue — green, sitting right before a caution zone, reads as backwards
    /// — fade through a bright near-white flash instead of rotating through it.
    private static func lerp(_ a: Color, _ b: Color, _ t: Double) -> Color {
        #if canImport(UIKit)
        var ah: CGFloat = 0, asat: CGFloat = 0, abr: CGFloat = 0, aa: CGFloat = 0
        var bh: CGFloat = 0, bsat: CGFloat = 0, bbr: CGFloat = 0, ba: CGFloat = 0
        UIColor(a).getHue(&ah, saturation: &asat, brightness: &abr, alpha: &aa)
        UIColor(b).getHue(&bh, saturation: &bsat, brightness: &bbr, alpha: &ba)

        var delta = bh - ah
        if delta > 0.5 { delta -= 1 }
        if delta < -0.5 { delta += 1 }

        guard abs(delta) <= 0.25 else {
            return t < 0.5 ? rgbLerp(a, .white, t * 2) : rgbLerp(.white, b, (t - 0.5) * 2)
        }

        var hue = ah + delta * CGFloat(t)
        if hue < 0 { hue += 1 }
        if hue > 1 { hue -= 1 }

        let ct = CGFloat(t)
        return Color(
            hue: Double(hue),
            saturation: Double(asat + (bsat - asat) * ct),
            brightness: Double(abr + (bbr - abr) * ct),
            opacity: Double(aa + (ba - aa) * ct)
        )
        #else
        return t < 0.5 ? a : b
        #endif
    }

    #if canImport(UIKit)
    private static func rgbLerp(_ a: Color, _ b: Color, _ t: Double) -> Color {
        var ar: CGFloat = 0, ag: CGFloat = 0, ab: CGFloat = 0, aa: CGFloat = 0
        var br: CGFloat = 0, bg: CGFloat = 0, bb: CGFloat = 0, ba: CGFloat = 0
        UIColor(a).getRed(&ar, green: &ag, blue: &ab, alpha: &aa)
        UIColor(b).getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        let ct = CGFloat(t)
        return Color(
            red: Double(ar + (br - ar) * ct),
            green: Double(ag + (bg - ag) * ct),
            blue: Double(ab + (bb - ab) * ct),
            opacity: Double(aa + (ba - aa) * ct)
        )
    }
    #endif
}

struct RadialGaugeView: View {
    var value: Double
    var range: ClosedRange<Double>
    var title: String
    var unit: String
    var valueText: String
    var tickCount: Int = 10
    /// Divides each major tick's value before it's drawn as a label — e.g. 1000
    /// for an RPM gauge so the ring reads the conventional bare "0, 1, 2 … 8"
    /// instead of "0, 1000, 2000 … 8000".
    var tickLabelScale: Double = 1
    var colorZones: [GaugeColorStop] = GaugeColorStop.standard

    private let startAngle: Double = 135
    private let sweep: Double = 270

    private var progress: Double {
        guard range.upperBound > range.lowerBound else { return 0 }
        let clamped = min(max(value, range.lowerBound), range.upperBound)
        return (clamped - range.lowerBound) / (range.upperBound - range.lowerBound)
    }

    private var liveColor: Color {
        GaugeColorStop.color(at: progress, in: colorZones)
    }

    /// Whether `colorZones` actually varies — if every stop is the same
    /// color (see `GaugeColorStop.flat`), the value text shouldn't tint as
    /// progress rises, since that tint exists to signal approaching danger.
    private var hasColorRamp: Bool {
        guard let first = colorZones.first?.color else { return false }
        return colorZones.contains { $0.color != first }
    }

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let lineWidth = size * 0.085
            let radius = (size - lineWidth) / 2
            let center = CGPoint(x: size / 2, y: size / 2)

            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [.secondary.opacity(0.1), .secondary.opacity(0.02)],
                            center: .center,
                            startRadius: 0,
                            endRadius: radius * 1.05
                        )
                    )
                    .frame(width: radius * 2.08, height: radius * 2.08)
                    .shadow(color: .black.opacity(0.2), radius: size * 0.04, x: 0, y: size * 0.015)

                // Everything — dial, ticks, progress arc, and all three text labels — is drawn
                // inside this single Canvas rather than overlaying separate SwiftUI Text views,
                // so a rotation applied to this view rotates every pixel together. Note: the
                // value text must NOT use .monospacedDigit() — that font feature resolves through
                // a different glyph-shaping path that does not respect an ancestor's
                // rotationEffect, which left the number stuck upright while everything else
                // (dial, ticks, unit/title labels) rotated correctly on real hardware.
                Canvas { context, _ in
                    var trackPath = Path()
                    trackPath.addArc(center: center, radius: radius, startAngle: .degrees(startAngle), endAngle: .degrees(startAngle + sweep), clockwise: false)
                    context.stroke(trackPath, with: .color(.secondary.opacity(0.14)), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))

                    let tickLabelFontSize = size * 0.044
                    let tickLabelRadius = radius - lineWidth * 1.55

                    for i in 0...tickCount {
                        let t = Double(i) / Double(tickCount)
                        let isMajor = i % 2 == 0
                        let angle = (startAngle + t * sweep) * .pi / 180
                        let outer = radius + lineWidth * (isMajor ? 0.7 : 0.58)
                        let inner = radius + lineWidth * (isMajor ? 0.24 : 0.36)
                        var tickPath = Path()
                        tickPath.move(to: CGPoint(x: center.x + cos(angle) * inner, y: center.y + sin(angle) * inner))
                        tickPath.addLine(to: CGPoint(x: center.x + cos(angle) * outer, y: center.y + sin(angle) * outer))
                        context.stroke(tickPath, with: .color(.secondary.opacity(isMajor ? 0.6 : 0.3)), style: StrokeStyle(lineWidth: isMajor ? 2.2 : 1.2, lineCap: .round))

                        // Labels sit inside the track, in the open face between the dial
                        // ring and the center readout, upright like the rest of the dial
                        // text (not rotated to follow the tick's angle).
                        guard isMajor else { continue }
                        let labelValue = (range.lowerBound + t * (range.upperBound - range.lowerBound)) / tickLabelScale
                        let labelPoint = CGPoint(x: center.x + cos(angle) * tickLabelRadius, y: center.y + sin(angle) * tickLabelRadius)
                        context.draw(
                            Text(String(Int(labelValue.rounded())))
                                .font(.system(size: tickLabelFontSize, weight: .semibold, design: .rounded))
                                .foregroundStyle(.secondary),
                            at: labelPoint,
                            anchor: .center
                        )
                    }

                    var progressPath = Path()
                    progressPath.addArc(center: center, radius: radius, startAngle: .degrees(startAngle), endAngle: .degrees(startAngle + max(progress, 0.0015) * sweep), clockwise: false)

                    context.drawLayer { ctx in
                        ctx.addFilter(.blur(radius: lineWidth * 0.4))
                        ctx.stroke(
                            progressPath,
                            with: .conicGradient(
                                GaugeColorStop.gradient(for: colorZones, sweepFraction: sweep / 360, opacity: 0.6),
                                center: center,
                                angle: .degrees(startAngle)
                            ),
                            style: StrokeStyle(lineWidth: lineWidth * 1.25, lineCap: .round)
                        )
                    }

                    context.stroke(
                        progressPath,
                        with: .conicGradient(
                            GaugeColorStop.gradient(for: colorZones, sweepFraction: sweep / 360),
                            center: center,
                            angle: .degrees(startAngle)
                        ),
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                    )

                    let tipAngle = (startAngle + max(progress, 0.0015) * sweep) * .pi / 180
                    let tip = CGPoint(x: center.x + cos(tipAngle) * radius, y: center.y + sin(tipAngle) * radius)
                    context.fill(Path(ellipseIn: CGRect(x: tip.x - lineWidth * 0.28, y: tip.y - lineWidth * 0.28, width: lineWidth * 0.56, height: lineWidth * 0.56)), with: .color(.white.opacity(0.9)))
                    context.stroke(Path(ellipseIn: CGRect(x: tip.x - lineWidth * 0.28, y: tip.y - lineWidth * 0.28, width: lineWidth * 0.56, height: lineWidth * 0.56)), with: .color(liveColor), style: StrokeStyle(lineWidth: lineWidth * 0.08))

                    let valueFontSize = size * 0.21
                    let unitFontSize = size * 0.075
                    let titleFontSize = size * 0.065
                    let lineSpacing = size * 0.015
                    let valueLineHeight = valueFontSize * 1.15
                    let unitLineHeight = unitFontSize * 1.15
                    let titleLineHeight = titleFontSize * 1.15
                    let blockHeight = valueLineHeight + lineSpacing + unitLineHeight + lineSpacing + titleLineHeight
                    let blockTop = center.y - blockHeight / 2

                    context.draw(
                        Text(valueText)
                            .font(.system(size: valueFontSize, weight: .bold, design: .rounded))
                            .foregroundStyle(hasColorRamp && progress >= 0.90 ? liveColor : .primary),
                        at: CGPoint(x: center.x, y: blockTop + valueLineHeight / 2),
                        anchor: .center
                    )
                    context.draw(
                        Text(unit.uppercased())
                            .font(.system(size: unitFontSize, weight: .semibold, design: .rounded))
                            .tracking(1.2)
                            .foregroundStyle(.secondary),
                        at: CGPoint(x: center.x, y: blockTop + valueLineHeight + lineSpacing + unitLineHeight / 2),
                        anchor: .center
                    )
                    context.draw(
                        Text(title.uppercased())
                            .font(.system(size: titleFontSize, weight: .medium, design: .rounded))
                            .tracking(0.8)
                            .foregroundStyle(.secondary.opacity(0.75)),
                        at: CGPoint(x: center.x, y: blockTop + valueLineHeight + lineSpacing + unitLineHeight + lineSpacing + titleLineHeight / 2),
                        anchor: .center
                    )
                }
                .frame(width: size, height: size)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .aspectRatio(1, contentMode: .fit)
        // Short enough that the dial doesn't add its own lag on top of polling latency —
        // a tach in particular needs to read as near-instant, not eased.
        .animation(.easeOut(duration: 0.12), value: value)
    }
}

#Preview("RPM") {
    RadialGaugeView(value: 4250, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "4250", tickCount: 16, tickLabelScale: 1000)
        .frame(width: 320, height: 320)
        .padding()
}

#Preview("RPM Redline") {
    RadialGaugeView(value: 7700, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "7700", tickCount: 16, tickLabelScale: 1000)
        .frame(width: 320, height: 320)
        .padding()
}

#Preview("RPM Warning") {
    RadialGaugeView(value: 7000, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "7000", tickCount: 16, tickLabelScale: 1000)
        .frame(width: 320, height: 320)
        .padding()
}

#Preview("RPM At Yellow Start") {
    RadialGaugeView(value: 7200, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "7200", tickCount: 16, tickLabelScale: 1000)
        .frame(width: 320, height: 320)
        .padding()
}

#Preview("RPM Near Top") {
    RadialGaugeView(value: 7900, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "7900", tickCount: 16, tickLabelScale: 1000)
        .frame(width: 320, height: 320)
        .padding()
}

#Preview("Speed") {
    RadialGaugeView(value: 23.4, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "23.4", tickCount: 14, colorZones: GaugeColorStop.flat())
        .frame(width: 320, height: 320)
        .padding()
}

#Preview("Speed High") {
    RadialGaugeView(value: 68, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "68.0", tickCount: 14, colorZones: GaugeColorStop.flat())
        .frame(width: 320, height: 320)
        .padding()
}

#Preview("Stacked") {
    VStack(spacing: 16) {
        RadialGaugeView(value: 6100, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "6100", tickCount: 16, tickLabelScale: 1000)
        RadialGaugeView(value: 41, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "41.0", tickCount: 14, colorZones: GaugeColorStop.flat())
    }
    .padding()
}

#Preview("Rotated 90°") {
    VStack(spacing: 16) {
        RadialGaugeView(value: 6100, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "6100", tickCount: 16, tickLabelScale: 1000)
        RadialGaugeView(value: 41, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "41.0", tickCount: 14, colorZones: GaugeColorStop.flat())
    }
    .padding()
    .rotationEffect(.degrees(90))
}
