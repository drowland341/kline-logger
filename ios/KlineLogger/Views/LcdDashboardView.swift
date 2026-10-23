//
//  LcdDashboardView.swift
//  KlineLogger
//
//  The "LCD" dash style: a wide amber tach sweeping across the top of a black panel, an amber LCD
//  window with seven-segment digits (unlit segments faintly showing), and two half-dial segment
//  gauges. Ported from the Android app's LcdGauges.kt.
//

import SwiftUI

private enum Lcd {
    static let panel = Color(hex: 0x070707)
    static let band = Color(hex: 0x3A3411)        // unlit LCD band: dim olive
    static let lit = Color(hex: 0xFFB21E)
    static let litDeep = Color(hex: 0xFF9419)
    static let red = Color(hex: 0xFF5A1F)
    static let redUnlit = Color(hex: 0x4A1E0C)
    static let tick = Color(hex: 0xF2F2F2)
    static let window = Color(hex: 0x24130A)
    static let windowDeep = Color(hex: 0x130A05)
    static let on = Color(hex: 0xFF8A50)           // lit LCD segment
    static let ghost = Color(hex: 0x2C170C)        // unlit LCD segment: only just visible, so lit digits stand out
    static let strip = Color(hex: 0x111111)
    static let edge = Color(hex: 0x1C1C1C)
}

private let lcdAspect: CGFloat = 2.2
private let tachStart: CGFloat = 205
private let tachSweep: CGFloat = 110
private let tachSegments = 84

// Segments a-g of a seven-segment digit, as bits: a=1 b=2 c=4 d=8 e=16 f=32 g=64.
private let sevenSegmentBits: [Character: Int] = [
    "0": 0b0111111, "1": 0b0000110, "2": 0b1011011, "3": 0b1001111, "4": 0b1100110,
    "5": 0b1101101, "6": 0b1111101, "7": 0b0000111, "8": 0b1111111, "9": 0b1101111,
    "-": 0b1000000, "—": 0b1000000,
]

private func hSegment(_ x1: CGFloat, _ x2: CGFloat, _ y: CGFloat, _ t: CGFloat) -> Path {
    var p = Path()
    p.move(to: CGPoint(x: x1, y: y))
    p.addLine(to: CGPoint(x: x1 + t / 2, y: y - t / 2))
    p.addLine(to: CGPoint(x: x2 - t / 2, y: y - t / 2))
    p.addLine(to: CGPoint(x: x2, y: y))
    p.addLine(to: CGPoint(x: x2 - t / 2, y: y + t / 2))
    p.addLine(to: CGPoint(x: x1 + t / 2, y: y + t / 2))
    p.closeSubpath()
    return p
}

private func vSegment(_ x: CGFloat, _ y1: CGFloat, _ y2: CGFloat, _ t: CGFloat) -> Path {
    var p = Path()
    p.move(to: CGPoint(x: x, y: y1))
    p.addLine(to: CGPoint(x: x + t / 2, y: y1 + t / 2))
    p.addLine(to: CGPoint(x: x + t / 2, y: y2 - t / 2))
    p.addLine(to: CGPoint(x: x, y: y2))
    p.addLine(to: CGPoint(x: x - t / 2, y: y2 - t / 2))
    p.addLine(to: CGPoint(x: x - t / 2, y: y1 + t / 2))
    p.closeSubpath()
    return p
}

/// Seven-segment `text`, right-aligned at `right`, digits `digitH` tall. Every segment shows
/// faintly, like real LCD glass.
private func drawSevenSegment(_ context: inout GraphicsContext, text: String, right: CGFloat, top: CGFloat, digitH: CGFloat, on: Color, ghost: Color) {
    let dw = digitH * 0.52
    let t = digitH * 0.13
    let gap = t * 0.18
    let pitch = dw + digitH * 0.2
    for (i, ch) in Array(text.reversed()).enumerated() {
        let bits = sevenSegmentBits[ch] ?? 0
        let x = right - dw - CGFloat(i) * pitch
        let y = top
        let segs = [
            hSegment(x + gap, x + dw - gap, y + t / 2, t),                                // a
            vSegment(x + dw - t / 2, y + t / 2 + gap, y + digitH / 2 - gap, t),            // b
            vSegment(x + dw - t / 2, y + digitH / 2 + gap, y + digitH - t / 2 - gap, t),   // c
            hSegment(x + gap, x + dw - gap, y + digitH - t / 2, t),                        // d
            vSegment(x + t / 2, y + digitH / 2 + gap, y + digitH - t / 2 - gap, t),        // e
            vSegment(x + t / 2, y + t / 2 + gap, y + digitH / 2 - gap, t),                 // f
            hSegment(x + gap, x + dw - gap, y + digitH / 2, t),                            // g
        ]
        for (s, path) in segs.enumerated() {
            context.fill(path, with: .color((bits & (1 << s)) != 0 ? on : ghost))
        }
    }
}

/// A half-dial segment gauge (left end = low), with end labels and the value in the middle.
private func drawHalfDial(_ context: inout GraphicsContext, c: CGPoint, r: CGFloat, fraction: Double, readout: SportReadout, low: String, high: String) {
    let n = 18
    let seg = 180.0 / Double(n)
    let tint: Color
    switch readout.level {
    case .normal: tint = Lcd.lit
    case .warning: tint = Lcd.litDeep
    case .critical: tint = Lcd.red
    }
    for i in 0..<n {
        let lit = readout.fraction != nil && fraction * Double(n) > Double(i) + 0.001
        context.fill(
            ringSegment(c, inner: r * 0.74, outer: r, start: CGFloat(180 + Double(i) * seg + 0.6), sweep: CGFloat(seg - 1.2)),
            with: .color(lit ? tint : Lcd.band)
        )
    }
    let rail = r * 0.66
    context.stroke(Path { $0.addArc(center: c, radius: rail, startAngle: .degrees(180), endAngle: .degrees(360), clockwise: false) }, with: .color(Lcd.tick), lineWidth: 1.5)
    var lowCap = Path(); lowCap.move(to: CGPoint(x: c.x - r * 1.04, y: c.y)); lowCap.addLine(to: CGPoint(x: c.x - r * 0.7, y: c.y))
    context.stroke(lowCap, with: .color(Lcd.tick), lineWidth: 3)
    var highCap = Path(); highCap.move(to: CGPoint(x: c.x + r * 0.7, y: c.y)); highCap.addLine(to: CGPoint(x: c.x + r * 1.04, y: c.y))
    context.stroke(highCap, with: .color(Lcd.red), lineWidth: 3)
    context.drawFitted(low, at: CGPoint(x: c.x - r * 0.48, y: c.y - r * 0.18), size: r * 0.24, color: Lcd.tick, anchor: .center)
    context.drawFitted(high, at: CGPoint(x: c.x + r * 0.48, y: c.y - r * 0.18), size: r * 0.24, color: Lcd.tick, anchor: .center)
    context.drawFitted(readout.text + readout.units, at: CGPoint(x: c.x, y: c.y - r * 0.36), size: r * 0.24, color: .white, anchor: .center, maxWidth: r * 0.6)
    context.drawFitted(readout.label, at: CGPoint(x: c.x, y: c.y + r * 0.2), size: r * 0.2, color: Lcd.tick.opacity(0.7), anchor: .center, weight: .medium)
}

private struct LcdCluster: View {
    var data: ClusterData

    var body: some View {
        Canvas { context, size in
            draw(context: &context, size: size)
        }
        .accessibilityLabel("RPM \(data.rpmText), \(data.speedText) \(data.speedUnits)")
        .animation(.easeOut(duration: 0.2), value: data.rpm)
        .animation(.easeOut(duration: 0.2), value: data.throttle.fraction)
        .animation(.easeOut(duration: 0.2), value: data.coolant.fraction)
    }

    private func draw(context: inout GraphicsContext, size: CGSize) {
        let rpmF = data.rpmSpec.fraction(data.rpm ?? data.rpmSpec.min)
        let throttleF = data.throttle.fraction ?? 0
        let coolantF = data.coolant.fraction ?? 0
        let big = data.speedText
        let bigUnits = data.speedUnits
        let t = data.rpmSpec.thresholds
        let critF = t.critHigh.map { data.rpmSpec.fraction($0) }
        let warnF = t.warnHigh.map { data.rpmSpec.fraction($0) }

        let b = fitBox(size.width, size.height, aspect: lcdAspect)
        let u = b.height
        func at(_ fx: CGFloat, _ fy: CGFloat) -> CGPoint { CGPoint(x: b.minX + fx * b.width, y: b.minY + fy * u) }

        // Tach: a wide arc of a big circle whose centre sits below the panel. Raised closer to
        // the panel (1.14 rather than 1.22) so the sweep uses more of the available height
        // instead of leaving dead space above its peak.
        let c = CGPoint(x: b.minX + 0.45 * b.width, y: b.minY + 1.14 * u)
        let r = 1.1 * u
        let seg = tachSweep / CGFloat(tachSegments)
        for i in 0..<tachSegments {
            let f0 = Double(i) / Double(tachSegments)
            let isRed = critF != nil && f0 >= critF!
            let lit = rpmF * Double(tachSegments) > Double(i) + 0.001
            let color: Color
            if isRed { color = lit ? Lcd.red : Lcd.redUnlit }
            else if lit { color = (warnF != nil && f0 >= warnF!) ? Lcd.litDeep : Lcd.lit }
            else { color = Lcd.band }
            let gap: CGFloat = isRed ? 0.55 : 0.18
            context.fill(ringSegment(c, inner: r - 0.085 * u, outer: r, start: tachStart + CGFloat(i) * seg + gap / 2, sweep: seg - gap), with: .color(color))
        }
        let rail = r - 0.095 * u
        context.stroke(
            Path { $0.addArc(center: c, radius: rail, startAngle: .degrees(tachStart), endAngle: .degrees(tachStart + tachSweep), clockwise: false) },
            with: .color(Lcd.tick), lineWidth: 1.5
        )
        let spec = data.rpmSpec
        let major = spec.majorTick > 0 ? spec.majorTick : (spec.max - spec.min) / 8
        var v = spec.min
        var n = 0
        while v <= spec.max + 1e-6 {
            let a = tachStart + tachSweep * CGFloat(spec.fraction(v))
            let isMajor = n % 2 == 0
            var tickLine = Path()
            tickLine.move(to: polar(c, rail, a))
            tickLine.addLine(to: polar(c, rail - (isMajor ? 0.06 : 0.035) * u, a))
            context.stroke(tickLine, with: .color(Lcd.tick), lineWidth: isMajor ? 3 : 2)
            if isMajor {
                let p = polar(c, r - 0.2 * u, a)
                context.drawFitted(formatValue(v / spec.tickDivisor, 0), at: p, size: 0.12 * u, color: Lcd.tick, anchor: .center)
            }
            v += major / 2
            n += 1
        }
        let unitsAt = at(0.235, 0.74)
        context.drawFitted("×1000 r/min", at: unitsAt, size: 0.055 * u, color: Lcd.tick, anchor: .center, weight: .medium)

        // LCD window: big seven-segment readout, a divider, then intake air temp and the clock.
        // Narrower and lower than a literal center placement so it (and the "mph" label beside
        // it) sit inside the arc's open face without overlapping the tick labels/red zone on the
        // right — the window was wide enough to intrude on them before.
        let win = CGRect(x: at(0.35, 0.46).x, y: at(0.35, 0.46).y, width: at(0.60, 0.95).x - at(0.35, 0.46).x, height: at(0.60, 0.95).y - at(0.35, 0.46).y)
        context.fill(
            Path(roundedRect: win, cornerRadius: 0.03 * u),
            with: .linearGradient(Gradient(colors: [Lcd.window, Lcd.windowDeep]), startPoint: CGPoint(x: win.midX, y: win.minY), endPoint: CGPoint(x: win.midX, y: win.maxY))
        )
        let cleaned = big.replacingOccurrences(of: ",", with: "")
        let truncated = String(cleaned.prefix(4))
        let digits = String(repeating: " ", count: Swift.max(0, 4 - truncated.count)) + truncated
        let digitH = Swift.min(0.3 * u, (win.width - 0.08 * u) / (4 * 0.72 - 0.2))
        drawSevenSegment(&context, text: digits, right: win.maxX - 0.04 * u, top: win.minY + 0.05 * u + (0.3 * u - digitH) / 2, digitH: digitH, on: Lcd.on, ghost: Lcd.ghost)
        context.drawFitted(bigUnits, at: CGPoint(x: win.maxX + 0.02 * u, y: win.minY + 0.3 * u), size: 0.07 * u, color: Lcd.on)
        var divider = Path()
        divider.move(to: CGPoint(x: win.minX + 0.03 * u, y: win.minY + 0.4 * u))
        divider.addLine(to: CGPoint(x: win.maxX - 0.03 * u, y: win.minY + 0.4 * u))
        context.stroke(divider, with: .color(Lcd.on.opacity(0.5)), lineWidth: 1.5)
        let line = win.minY + 0.46 * u
        context.drawFitted(data.footer, at: CGPoint(x: win.minX + 0.04 * u, y: line), size: 0.06 * u, color: Lcd.on)
        context.drawFitted(clockString(), at: CGPoint(x: win.maxX - 0.04 * u, y: line), size: 0.06 * u, color: Lcd.on, anchor: .trailing)

        // Readout strip, lower left.
        let strip = CGRect(x: at(0.08, 0.85).x, y: at(0.08, 0.85).y, width: at(0.345, 0.99).x - at(0.08, 0.85).x, height: at(0.345, 0.99).y - at(0.08, 0.85).y)
        context.fill(Path(roundedRect: strip, cornerRadius: 0.03 * u), with: .color(Lcd.strip))
        for (i, r0) in [data.oil, data.battery, data.map].enumerated() {
            let cw = strip.width / 3
            let cx = strip.minX + cw * (CGFloat(i) + 0.5)
            let tint: Color = r0.level == .normal ? Lcd.on : Lcd.red
            context.drawFitted(r0.label, at: CGPoint(x: cx, y: strip.minY + strip.height * 0.3), size: 0.045 * u, color: Lcd.tick.opacity(0.7), anchor: .center, weight: .medium)
            context.drawFitted(r0.text + r0.units, at: CGPoint(x: cx, y: strip.minY + strip.height * 0.68), size: 0.06 * u, color: tint, anchor: .center, maxWidth: cw * 0.92)
        }

        // Half dials, stepping down to the right like the reference — inset a bit further than a
        // literal corner placement so neither dial nor its high-end label crosses into the arc.
        drawHalfDial(&context, c: at(0.80, 0.76), r: 0.15 * u, fraction: coolantF, readout: data.coolant, low: "C", high: "H")
        drawHalfDial(&context, c: at(0.83, 0.99), r: 0.15 * u, fraction: throttleF, readout: data.throttle, low: "0", high: "100")
    }
}

/// The LCD cluster. Landscape fills the space; portrait puts the cluster on top and the readouts under it.
struct LcdDashboard: View {
    var data: ClusterData
    var edgeToEdge: Bool = false

    var body: some View {
        GeometryReader { geo in
            Group {
                if geo.size.width > geo.size.height * 1.2 {
                    LcdCluster(data: data)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    VStack(spacing: 10) {
                        LcdCluster(data: data)
                            .aspectRatio(lcdAspect, contentMode: .fit)
                            .frame(maxWidth: .infinity)
                        ForEach([data.throttle, data.coolant, data.oil, data.battery, data.map].indices, id: \.self) { i in
                            SportReadoutRow(readout: [data.throttle, data.coolant, data.oil, data.battery, data.map][i])
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            // Padding is applied here, inside the space GeometryReader already measured — applying
            // it outside (after sizing content to the full geo.size) pushed content past the edges
            // of whatever fixed-size container this dashboard sits in (seen as clipped content in
            // full-screen landscape).
            .padding(edgeToEdge ? 16 : 12)
        }
        .background(Lcd.panel)
        .clipShape(RoundedRectangle(cornerRadius: edgeToEdge ? 0 : 28))
        .overlay(
            RoundedRectangle(cornerRadius: edgeToEdge ? 0 : 28)
                .strokeBorder(edgeToEdge ? Color.clear : Lcd.edge, lineWidth: 1)
        )
    }
}
