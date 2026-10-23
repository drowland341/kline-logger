//
//  DigitalDashboardView.swift
//  KlineLogger
//
//  The "Digital" dash style: twin frames (speed left, RPM mirrored right), each one smooth curve
//  with a white outline, a slim blue band that fills with the value, fine ticks and a needle; big
//  white numbers over a rule, faint road lines and the rider's ski between, and small readouts
//  filling the space around them. Ported from the Android app's DigitalGauges.kt.
//
//  SwiftUI's Path has no built-in equivalent of Android's PathMeasure (arc-length position/tangent
//  queries), so `ArcLengthCurve` below samples the same spline this file draws and interpolates
//  between samples — the same trick Android's own `frame()` already uses to build the offset band.
//

import SwiftUI

private enum Digital {
    static let panel = Color(hex: 0x04070D)
    static let blue = Color(hex: 0x2A9DFF)
    static let blueDeep = Color(hex: 0x1456C8)
    static let track = Color(hex: 0x101B2B)
    static let line = Color(hex: 0xDCE4EE)
    static let label = Color(hex: 0xAEBBCC)
    static let faint = Color(hex: 0x8A99AD)
    static let red = Color(hex: 0xFF4D4F)
    static let amber = Color(hex: 0xFFB020)
    static let edge = Color(hex: 0x141C28)
}

private let digitalAspect: CGFloat = 2.1

// Points the left frame's outline passes through, as fractions of the cluster box (x of width, y of
// height): value 0 at the bottom, curving left and up the side, then across the top.
private let frameControlFractions: [(Double, Double)] = [
    (0.33, 0.80), (0.24, 0.798), (0.145, 0.765), (0.088, 0.68), (0.072, 0.56),
    (0.088, 0.44), (0.14, 0.345), (0.25, 0.275), (0.36, 0.243), (0.46, 0.228),
]

private func digitalTint(_ level: Level, normal: Color) -> Color {
    switch level {
    case .normal: return normal
    case .warning: return Digital.amber
    case .critical: return Digital.red
    }
}

private extension CGPoint {
    static func - (a: CGPoint, b: CGPoint) -> CGPoint { CGPoint(x: a.x - b.x, y: a.y - b.y) }
    static func + (a: CGPoint, b: CGPoint) -> CGPoint { CGPoint(x: a.x + b.x, y: a.y + b.y) }
    static func * (a: CGPoint, s: CGFloat) -> CGPoint { CGPoint(x: a.x * s, y: a.y * s) }
    var length: CGFloat { (x * x + y * y).squareRoot() }
}

private func cubicPoint(_ p1: CGPoint, _ c1: CGPoint, _ c2: CGPoint, _ p2: CGPoint, _ t: CGFloat) -> CGPoint {
    let mt = 1 - t
    let a = mt * mt * mt, b = 3 * mt * mt * t, c = 3 * mt * t * t, d = t * t * t
    return CGPoint(x: a * p1.x + b * c1.x + c * c2.x + d * p2.x, y: a * p1.y + b * c1.y + c * c2.y + d * p2.y)
}

/// Samples lying exactly on the same Catmull-Rom spline `splinePath(_:)` draws, for `ArcLengthCurve`.
private func splineSamples(_ pts: [CGPoint], perSegment: Int = 24) -> [CGPoint] {
    guard pts.count > 1 else { return pts }
    var out: [CGPoint] = [pts[0]]
    for i in 0..<(pts.count - 1) {
        let p0 = pts[Swift.max(0, i - 1)]
        let p1 = pts[i]
        let p2 = pts[i + 1]
        let p3 = pts[Swift.min(pts.count - 1, i + 2)]
        let c1 = p1 + (p2 - p0) * (1.0 / 6)
        let c2 = p2 - (p3 - p1) * (1.0 / 6)
        for s in 1...perSegment {
            out.append(cubicPoint(p1, c1, c2, p2, CGFloat(s) / CGFloat(perSegment)))
        }
    }
    return out
}

/// Arc-length-parameterized position/tangent/segment queries over a polyline of samples.
private struct ArcLengthCurve {
    private struct Sample { var distance: CGFloat; var point: CGPoint; var tangent: CGPoint }
    private var samples: [Sample] = []
    private(set) var length: CGFloat = 0

    init(_ points: [CGPoint]) {
        guard points.count > 1 else { return }
        var dist: CGFloat = 0
        var tangents: [CGPoint] = []
        for i in 0..<points.count {
            let a = points[Swift.max(0, i - 1)]
            let b = points[Swift.min(points.count - 1, i + 1)]
            let d = b - a
            let m = Swift.max(d.length, 0.0001)
            tangents.append(CGPoint(x: d.x / m, y: d.y / m))
        }
        samples.append(Sample(distance: 0, point: points[0], tangent: tangents[0]))
        for i in 1..<points.count {
            dist += (points[i] - points[i - 1]).length
            samples.append(Sample(distance: dist, point: points[i], tangent: tangents[i]))
        }
        length = dist
    }

    private func interpolated(at d: CGFloat) -> (point: CGPoint, tangent: CGPoint) {
        guard !samples.isEmpty else { return (.zero, CGPoint(x: 1, y: 0)) }
        let clamped = Swift.min(Swift.max(d, 0), length)
        var lo = 0, hi = samples.count - 1
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            if samples[mid].distance <= clamped { lo = mid } else { hi = mid - 1 }
        }
        if lo == samples.count - 1 { return (samples[lo].point, samples[lo].tangent) }
        let a = samples[lo], b = samples[lo + 1]
        let span = b.distance - a.distance
        let t = span > 0 ? (clamped - a.distance) / span : 0
        return (a.point + (b.point - a.point) * t, a.tangent)
    }

    func position(at d: CGFloat) -> CGPoint { interpolated(at: d).point }

    /// Normal to the right of travel direction — matches Android's `PathMeasure.outward`.
    func outward(at d: CGFloat) -> CGPoint {
        let t = interpolated(at: d).tangent
        return CGPoint(x: t.y, y: -t.x)
    }

    /// A polyline path through the samples between two arc-length distances.
    func segmentPath(from: CGFloat, to: CGFloat) -> Path {
        var path = Path()
        var started = false
        path.move(to: interpolated(at: from).point)
        started = true
        for s in samples where s.distance > from && s.distance < to {
            path.addLine(to: s.point)
        }
        if started { path.addLine(to: interpolated(at: to).point) }
        return path
    }

    func asPath() -> Path {
        var path = Path()
        guard let first = samples.first else { return path }
        path.move(to: first.point)
        for s in samples.dropFirst() { path.addLine(to: s.point) }
        return path
    }
}

private struct Frame {
    var outline: Path
    var outer: ArcLengthCurve
    var bandPath: Path
    var band: ArcLengthCurve
}

private func makeFrame(_ b: CGRect) -> Frame {
    let u = b.height
    let controlPoints = frameControlFractions.map { CGPoint(x: b.minX + $0.0 * b.width, y: b.minY + $0.1 * u) }
    let outlineSamples = splineSamples(controlPoints)
    let outer = ArcLengthCurve(outlineSamples)
    let inset = 0.032 * u
    var bandPts: [CGPoint] = []
    let sampleCount = 240
    for k in 0...sampleCount {
        let d = outer.length * CGFloat(k) / CGFloat(sampleCount)
        let p = outer.position(at: d)
        let n = outer.outward(at: d)
        bandPts.append(p - n * inset)
    }
    let band = ArcLengthCurve(bandPts)
    return Frame(outline: splinePath(controlPoints), outer: outer, bandPath: band.asPath(), band: band)
}

private func measuredWidth(_ context: GraphicsContext, _ text: String, size: CGFloat, weight: Font.Weight) -> CGFloat {
    context.resolve(Text(text).font(.system(size: size, weight: weight, design: .rounded)))
        .measure(in: CGSize(width: CGFloat.infinity, height: CGFloat.infinity)).width
}

/// One frame's graphics, in unmirrored coordinates. Text is drawn separately so it never mirrors.
private func drawFrameGraphics(_ context: inout GraphicsContext, b: CGRect, f: Frame, spec: GaugeSpec, fraction: Double, tint: Color) {
    let u = b.height
    let width = 0.026 * u
    let len = f.band.length
    let t = spec.thresholds
    let critF = t.critHigh.map { spec.fraction($0) }
    let warnF = t.warnHigh.map { spec.fraction($0) }

    context.stroke(f.bandPath, with: .color(Digital.track), style: StrokeStyle(lineWidth: width, lineCap: .round))
    if let critF {
        let red = f.band.segmentPath(from: CGFloat(critF) * len, to: len)
        context.stroke(red, with: .color(Digital.red.opacity(0.28)), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }
    if fraction > 0.002 {
        let lit = f.band.segmentPath(from: 0, to: CGFloat(fraction) * len)
        let end: Color
        if let critF, fraction >= critF { end = Digital.red }
        else if let warnF, fraction >= warnF { end = Digital.amber }
        else { end = Digital.blue }
        context.stroke(lit, with: .color(end.opacity(0.1)), style: StrokeStyle(lineWidth: width * 1.9, lineCap: .round))
        context.stroke(
            lit,
            with: .linearGradient(Gradient(colors: [Digital.blueDeep, end]), startPoint: f.band.position(at: 0), endPoint: f.band.position(at: CGFloat(fraction) * len)),
            style: StrokeStyle(lineWidth: width, lineCap: .round)
        )
    }

    // Outline, brightest at the bottom and fading slightly toward the top end.
    context.stroke(
        f.outline,
        with: .linearGradient(Gradient(colors: [Digital.line, Digital.line.opacity(0.55)]), startPoint: f.outer.position(at: 0), endPoint: f.outer.position(at: f.outer.length)),
        style: StrokeStyle(lineWidth: 1.6, lineCap: .round)
    )

    // Ticks: majors with labels (drawn later), minors halfway between.
    let o = f.outer
    let major = spec.majorTick > 0 ? spec.majorTick : (spec.max - spec.min) / 7
    var v = spec.min
    var n = 0
    while v <= spec.max + 1e-6 {
        let d = CGFloat(spec.fraction(v)) * o.length
        let p = o.position(at: d)
        let nrm = o.outward(at: d)
        let isMajor = n % 2 == 0
        var tick = Path()
        tick.move(to: p + nrm * (0.008 * u))
        tick.addLine(to: p + nrm * ((isMajor ? 0.034 : 0.022) * u))
        context.stroke(tick, with: .color(isMajor ? Digital.line : Digital.line.opacity(0.45)), style: StrokeStyle(lineWidth: isMajor ? 2 : 1.2, lineCap: .round))
        v += major / 2
        n += 1
    }

    // Needle: a slim white bar standing inward from the band, with a soft hint of colour behind it.
    let d = CGFloat(fraction) * len
    let p = f.band.position(at: d)
    let nrm = f.band.outward(at: d)
    let glow = p - nrm * (0.045 * u)
    context.fill(
        Path(ellipseIn: CGRect(x: glow.x - 0.1 * u, y: glow.y - 0.1 * u, width: 0.2 * u, height: 0.2 * u)),
        with: .radialGradient(Gradient(colors: [tint.opacity(0.12), .clear]), center: glow, startRadius: 0, endRadius: 0.1 * u)
    )
    var glowLine = Path(); glowLine.move(to: p + nrm * (0.004 * u)); glowLine.addLine(to: p - nrm * (0.09 * u))
    context.stroke(glowLine, with: .color(tint.opacity(0.18)), style: StrokeStyle(lineWidth: 8, lineCap: .round))
    var needle = Path(); needle.move(to: p + nrm * (0.004 * u)); needle.addLine(to: p - nrm * (0.085 * u))
    context.stroke(needle, with: .color(.white), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
}

/// Tick labels just outside the outline. `mirror` maps an x to where it lands on screen.
private func drawFrameLabels(_ context: inout GraphicsContext, b: CGRect, f: Frame, spec: GaugeSpec, mirror: (CGFloat) -> CGFloat) {
    let u = b.height
    let o = f.outer
    let major = spec.majorTick > 0 ? spec.majorTick : (spec.max - spec.min) / 7
    var v = spec.min
    while v <= spec.max + 1e-6 {
        let d = CGFloat(spec.fraction(v)) * o.length
        let q = o.position(at: d) + o.outward(at: d) * (0.075 * u)
        context.drawFitted(formatValue(v / spec.tickDivisor, 0), at: CGPoint(x: mirror(q.x), y: q.y), size: 0.052 * u, color: Digital.label, anchor: .center, weight: .medium)
        v += major
    }
}

private func drawVehicle(_ context: inout GraphicsContext, image: Image?, area: CGRect, flip: Bool) {
    context.drawLayer { layer in
        if flip {
            layer.concatenate(CGAffineTransform(translationX: area.midX, y: 0).scaledBy(x: -1, y: 1).translatedBy(x: -area.midX, y: 0))
        }
        if let image {
            let resolved = layer.resolve(image)
            let intrinsic = resolved.size
            let aspect = intrinsic.height > 0 ? intrinsic.width / intrinsic.height : 2.5
            let w = Swift.min(area.width, area.height * aspect)
            let h = w / aspect
            layer.draw(resolved, in: CGRect(x: area.midX - w / 2, y: area.midY - h / 2, width: w, height: h))
        } else {
            drawBuiltInSilhouette(&layer, in: area)
        }
    }
}

/// A simple built-in placeholder graphic for the center of the frame, shown until the rider picks
/// their own ski photo — intentionally plain, not a traced replica of any particular ski.
private func drawBuiltInSilhouette(_ context: inout GraphicsContext, in area: CGRect) {
    let w = Swift.min(area.width, area.height * 2.6)
    let h = w / 2.6
    let rect = CGRect(x: area.midX - w / 2, y: area.midY - h / 2, width: w, height: h)
    var hull = Path()
    hull.move(to: CGPoint(x: rect.minX + rect.width * 0.05, y: rect.maxY))
    hull.addQuadCurve(to: CGPoint(x: rect.minX + rect.width * 0.85, y: rect.maxY), control: CGPoint(x: rect.midX, y: rect.maxY + rect.height * 0.12))
    hull.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + rect.height * 0.55))
    hull.addQuadCurve(to: CGPoint(x: rect.minX + rect.width * 0.18, y: rect.minY + rect.height * 0.25), control: CGPoint(x: rect.midX, y: rect.minY - rect.height * 0.05))
    hull.addQuadCurve(to: CGPoint(x: rect.minX + rect.width * 0.05, y: rect.maxY), control: CGPoint(x: rect.minX, y: rect.minY + rect.height * 0.7))
    hull.closeSubpath()
    context.fill(hull, with: .color(Digital.faint.opacity(0.35)))
    context.stroke(hull, with: .color(Digital.label.opacity(0.6)), lineWidth: 1.5)

    var seat = Path()
    let seatRect = CGRect(x: rect.minX + rect.width * 0.3, y: rect.minY + rect.height * 0.08, width: rect.width * 0.4, height: rect.height * 0.3)
    seat.addRoundedRect(in: seatRect, cornerSize: CGSize(width: seatRect.height * 0.4, height: seatRect.height * 0.4))
    context.fill(seat, with: .color(Digital.faint.opacity(0.45)))
}

private struct DigitalCluster: View {
    var data: ClusterData
    var vehicleImage: Image?
    var flipVehicle: Bool

    var body: some View {
        Canvas { context, size in
            draw(context: &context, size: size)
        }
        .accessibilityLabel("\(data.speedText) \(data.speedUnits), RPM \(data.rpmText)")
        .animation(.easeOut(duration: 0.22), value: data.speed)
        .animation(.easeOut(duration: 0.22), value: data.rpm)
    }

    private func draw(context: inout GraphicsContext, size: CGSize) {
        let speedF = data.speedSpec.fraction(data.speed ?? data.speedSpec.min)
        let rpmF = data.rpmSpec.fraction(data.rpm ?? data.rpmSpec.min)
        let rpmLevel = data.rpmSpec.thresholds.check(data.rpm).level
        let speedLevel = data.speedSpec.thresholds.check(data.speed).level

        let b = fitBox(size.width, size.height, aspect: digitalAspect)
        let u = b.height
        func at(_ fx: CGFloat, _ fy: CGFloat) -> CGPoint { CGPoint(x: b.minX + fx * b.width, y: b.minY + fy * u) }
        func x(_ fx: CGFloat) -> CGFloat { b.minX + fx * b.width }
        func mirror(_ px: CGFloat) -> CGFloat { 2 * b.midX - px }
        let f = makeFrame(b)

        // Road: faint lines running to a vanishing point between the frames.
        let vp = at(0.5, 0.40)
        for k in -3...3 {
            let bottom = at(0.5 + CGFloat(k) * 0.11, 0.94)
            let s: CGFloat = (0.52 - 0.40) / (0.94 - 0.40)
            let top = CGPoint(x: vp.x + (bottom.x - vp.x) * s, y: b.minY + 0.52 * u)
            var line = Path(); line.move(to: top); line.addLine(to: bottom)
            context.stroke(line, with: .linearGradient(Gradient(colors: [.clear, Digital.faint.opacity(0.28)]), startPoint: top, endPoint: bottom), lineWidth: 1.2)
        }
        for y: CGFloat in [0.62, 0.74, 0.9] {
            let half = 0.06 + (y - 0.5) * 0.35
            var line = Path(); line.move(to: at(0.5 - half, y)); line.addLine(to: at(0.5 + half, y))
            context.stroke(line, with: .color(Digital.faint.opacity(0.1)), lineWidth: 1)
        }
        drawVehicle(&context, image: vehicleImage, area: CGRect(x: at(0.37, 0.48).x, y: at(0.37, 0.48).y, width: at(0.63, 0.84).x - at(0.37, 0.48).x, height: at(0.63, 0.84).y - at(0.37, 0.48).y), flip: flipVehicle)

        // Speed frame (left), then the RPM frame mirrored onto the right.
        drawFrameGraphics(&context, b: b, f: f, spec: data.speedSpec, fraction: speedF, tint: digitalTint(speedLevel, normal: Digital.blue))
        context.drawMirrored(around: b.midX, mirrored: true) { ctx in
            drawFrameGraphics(&ctx, b: b, f: f, spec: data.rpmSpec, fraction: rpmF, tint: digitalTint(rpmLevel, normal: Digital.blue))
        }
        drawFrameLabels(&context, b: b, f: f, spec: data.speedSpec) { $0 }
        drawFrameLabels(&context, b: b, f: f, spec: data.rpmSpec, mirror: mirror)

        // Big numbers over a rule; the RPM side is the mirror image.
        let ruleY = b.minY + 0.47 * u
        let numY = b.minY + 0.39 * u
        var ruleLeft = Path(); ruleLeft.move(to: CGPoint(x: x(0.11), y: ruleY)); ruleLeft.addLine(to: CGPoint(x: x(0.47), y: ruleY))
        context.stroke(ruleLeft, with: .linearGradient(Gradient(colors: [.clear, Digital.line.opacity(0.8), Digital.line.opacity(0.8)]), startPoint: CGPoint(x: x(0.11), y: ruleY), endPoint: CGPoint(x: x(0.26), y: ruleY)), lineWidth: 1.5)
        var ruleRight = Path(); ruleRight.move(to: CGPoint(x: mirror(x(0.47)), y: ruleY)); ruleRight.addLine(to: CGPoint(x: mirror(x(0.11)), y: ruleY))
        context.stroke(ruleRight, with: .linearGradient(Gradient(colors: [Digital.line.opacity(0.8), Digital.line.opacity(0.8), .clear]), startPoint: CGPoint(x: mirror(x(0.26)), y: ruleY), endPoint: CGPoint(x: mirror(x(0.11)), y: ruleY)), lineWidth: 1.5)

        context.drawFitted(data.speedText, at: CGPoint(x: x(0.38), y: numY), size: 0.15 * u, color: digitalTint(speedLevel, normal: .white), anchor: .trailing, maxWidth: 0.2 * b.width)
        context.drawFitted(data.speedUnits, at: CGPoint(x: x(0.392), y: numY + 0.045 * u), size: 0.05 * u, color: Digital.label, weight: .medium)
        context.drawFitted(data.rpmText, at: CGPoint(x: mirror(x(0.38)), y: numY), size: 0.15 * u, color: digitalTint(rpmLevel, normal: .white), anchor: .leading, maxWidth: 0.2 * b.width)
        context.drawFitted("r/min", at: CGPoint(x: mirror(x(0.392)), y: numY + 0.045 * u), size: 0.05 * u, color: Digital.label, anchor: .trailing, weight: .medium)

        // Small readouts under the rules: oil and battery in the speed frame, intake pressure in the RPM frame.
        func row(_ r0: SportReadout, _ y: CGFloat, _ left: CGFloat, _ right: CGFloat) {
            context.drawFitted(r0.label, at: CGPoint(x: left, y: b.minY + y * u), size: 0.04 * u, color: Digital.label, weight: .medium)
            context.drawFitted("\(r0.text) \(r0.units)", at: CGPoint(x: right, y: b.minY + y * u), size: 0.052 * u, color: digitalTint(r0.level, normal: .white), anchor: .trailing)
        }
        row(data.oil, 0.53, x(0.15), x(0.34))
        row(data.battery, 0.595, x(0.15), x(0.34))
        row(data.map, 0.53, mirror(x(0.34)), mirror(x(0.15)))

        // Bottom row, one line each: clock, throttle, coolant, intake air temp.
        let baseY = b.minY + 0.965 * u
        func bottom(_ r0: SportReadout, _ edge: CGFloat, fromLeft: Bool) {
            let gap = 0.015 * u
            let labelPx = 0.038 * u
            let valuePx = 0.058 * u
            let value = "\(r0.text) \(r0.units)"
            let tint = digitalTint(r0.level, normal: .white)
            if fromLeft {
                let lw = measuredWidth(context, r0.label, size: labelPx, weight: .medium)
                context.drawFitted(r0.label, at: CGPoint(x: edge, y: baseY), size: labelPx, color: Digital.label, weight: .medium)
                context.drawFitted(value, at: CGPoint(x: edge + lw + gap, y: baseY), size: valuePx, color: tint)
            } else {
                let vw = measuredWidth(context, value, size: valuePx, weight: .bold)
                context.drawFitted(value, at: CGPoint(x: edge, y: baseY), size: valuePx, color: tint, anchor: .trailing)
                context.drawFitted(r0.label, at: CGPoint(x: edge - vw - gap, y: baseY), size: labelPx, color: Digital.label, anchor: .trailing, weight: .medium)
            }
        }
        bottom(data.throttle, x(0.13), fromLeft: true)
        bottom(data.coolant, mirror(x(0.13)), fromLeft: false)
        context.drawFitted(clockString(), at: CGPoint(x: x(0.015), y: baseY), size: 0.05 * u, color: .white)
        context.drawFitted(data.footer, at: CGPoint(x: x(0.985), y: baseY), size: 0.05 * u, color: .white, anchor: .trailing)
    }
}

/// The Digital cluster. Landscape fills the space; portrait puts the cluster on top and the readouts under it.
struct DigitalDashboard: View {
    var data: ClusterData
    var edgeToEdge: Bool = false
    var vehicleImage: Image? = nil
    var flipVehicle: Bool = false

    var body: some View {
        GeometryReader { geo in
            Group {
                if geo.size.width > geo.size.height * 1.2 {
                    DigitalCluster(data: data, vehicleImage: vehicleImage, flipVehicle: flipVehicle)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    VStack(spacing: 10) {
                        DigitalCluster(data: data, vehicleImage: vehicleImage, flipVehicle: flipVehicle)
                            .aspectRatio(digitalAspect, contentMode: .fit)
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
        .background(Digital.panel)
        .clipShape(RoundedRectangle(cornerRadius: edgeToEdge ? 0 : 28))
        .overlay(
            RoundedRectangle(cornerRadius: edgeToEdge ? 0 : 28)
                .strokeBorder(edgeToEdge ? Color.clear : Digital.edge, lineWidth: 1)
        )
    }
}
