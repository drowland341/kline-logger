//
//  DashCluster.swift
//  KlineLogger
//
//  Shared model and Canvas-drawing helpers for the Sport/LCD/Digital dash styles, ported from the
//  Android app's Gauges.kt/Cluster.kt/DashboardScreen.kt (same model, same three styles there).
//  Classic keeps using RadialGaugeView/GaugeColorStop — a different, already-polished color-ramp
//  model that isn't worth unifying with this threshold-based one.
//

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

enum Level {
    case normal, warning, critical
}

/// Warning/critical limits. Any can be nil; "high" limits trip at or above, "low" at or below.
struct Thresholds {
    var warnHigh: Double?
    var critHigh: Double?
    var warnLow: Double?
    var critLow: Double?

    init(warnHigh: Double? = nil, critHigh: Double? = nil, warnLow: Double? = nil, critLow: Double? = nil) {
        self.warnHigh = warnHigh
        self.critHigh = critHigh
        self.warnLow = warnLow
        self.critLow = critLow
    }

    func check(_ v: Double?) -> (level: Level, label: String) {
        guard let v else { return (.normal, "") }
        if let critHigh, v >= critHigh { return (.critical, "HIGH") }
        if let critLow, v <= critLow { return (.critical, "LOW") }
        if let warnHigh, v >= warnHigh { return (.warning, "HIGH") }
        if let warnLow, v <= warnLow { return (.warning, "LOW") }
        return (.normal, "")
    }
}

/// How one dashboard gauge shows a channel.
struct GaugeSpec {
    var pid: UInt8
    var label: String
    var min: Double
    var max: Double
    var decimals: Int = 0
    var step: Double = 0            // round the readout to this (0 = no rounding)
    var thresholds: Thresholds = Thresholds()
    var majorTick: Double = 0       // arc/pod gauges only
    var tickDivisor: Double = 1     // tick labels are value / divisor

    func fraction(_ v: Double) -> Double {
        guard max > min else { return 0 }
        return Swift.min(Swift.max((v - min) / (max - min), 0), 1)
    }
}

/// One small cluster readout: label, formatted value, units, alert level and where it sits in its range.
struct SportReadout {
    var label: String
    var text: String
    var units: String
    var level: Level
    var fraction: Double?
    var note: String?
}

/// Everything a cluster-style dashboard (Sport, LCD, Digital) shows, already converted and formatted.
struct ClusterData {
    var speedSpec: GaugeSpec
    var speed: Double?
    var speedText: String
    var speedUnits: String
    var rpmSpec: GaugeSpec
    var rpm: Double?
    var rpmText: String
    var rpmUnits: String
    var throttle: SportReadout
    var coolant: SportReadout
    var oil: SportReadout
    var battery: SportReadout
    var map: SportReadout
    var footer: String          // e.g. "IAT 91°F"
}

/// How the Live Data gauges are drawn, picked by the rider.
enum DashStyle: String, CaseIterable {
    case classic, sport, lcd, digital

    var label: String {
        switch self {
        case .classic: return "Classic"
        case .sport: return "Sport"
        case .lcd: return "LCD"
        case .digital: return "Digital"
        }
    }
}

/// Formats like Android's `formatValue`: fixed decimals, comma-grouped.
func formatValue(_ v: Double, _ decimals: Int, step: Double = 0) -> String {
    let shown = step > 0 ? (v / step).rounded() * step : v
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.minimumFractionDigits = decimals
    formatter.maximumFractionDigits = decimals
    formatter.usesGroupingSeparator = true
    formatter.groupingSeparator = ","
    formatter.decimalSeparator = "."
    return formatter.string(from: NSNumber(value: shown)) ?? String(format: "%.\(decimals)f", shown)
}

/// Current time, formatted 12h/24h per the user's locale — like Android's `rememberClock()`.
func clockString(_ date: Date = Date()) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale.current
    formatter.dateFormat = DateFormatter.dateFormat(fromTemplate: "j:mm", options: 0, locale: Locale.current) ?? "h:mm a"
    return formatter.string(from: date)
}

/// The 7 channel `GaugeSpec`s the dashboards draw, in their native units (matching `pids.json`).
/// Ranges come from `pids.json`'s `gauge_min`/`gauge_max` (falling back to the Android defaults this
/// was ported from if a channel is somehow missing), so a ski whose RPM range gets recalibrated in
/// Channels/pids.json is reflected here too, the same way the existing Classic tach already works.
enum DashChannels {
    private static func range(for pid: UInt8, fallback: (Double, Double)) -> (min: Double, max: Double) {
        guard let def = PIDLibrary.all.first(where: { $0.pid == pid }) else { return fallback }
        return (def.gaugeMin ?? fallback.0, def.gaugeMax ?? fallback.1)
    }

    static var rpm: GaugeSpec {
        let r = range(for: 0x09, fallback: (0, 8000))
        return GaugeSpec(
            pid: 0x09, label: "RPM", min: r.min, max: r.max, step: 10,
            thresholds: Thresholds(warnHigh: 7000, critHigh: 7600),
            majorTick: 1000, tickDivisor: 1000
        )
    }

    static var coolant: GaugeSpec {
        let r = range(for: 0x06, fallback: (20, 120))
        return GaugeSpec(pid: 0x06, label: "COOLANT", min: r.min, max: r.max, thresholds: Thresholds(warnHigh: 95, critHigh: 105))
    }

    static var battery: GaugeSpec {
        let r = range(for: 0x0A, fallback: (10, 16))
        return GaugeSpec(
            pid: 0x0A, label: "BATTERY", min: r.min, max: r.max, decimals: 1,
            thresholds: Thresholds(warnHigh: 15.0, critHigh: 15.5, warnLow: 12.2, critLow: 11.5)
        )
    }

    static var throttle: GaugeSpec {
        let r = range(for: 0x04, fallback: (0, 100))
        return GaugeSpec(pid: 0x04, label: "THROTTLE", min: r.min, max: r.max)
    }

    static var map: GaugeSpec {
        let r = range(for: 0x05, fallback: (0, 110))
        return GaugeSpec(pid: 0x05, label: "MAP", min: r.min, max: r.max)
    }

    static var oil: GaugeSpec {
        let r = range(for: 0x98, fallback: (20, 160))
        return GaugeSpec(pid: 0x98, label: "OIL", min: r.min, max: r.max)
    }

    static var iat: GaugeSpec {
        let r = range(for: 0x07, fallback: (-10, 60))
        return GaugeSpec(pid: 0x07, label: "IAT", min: r.min, max: r.max)
    }

    static var all: [GaugeSpec] { [rpm, coolant, battery, throttle, map, oil, iat] }
}

extension Color {
    /// An opaque color from a 24-bit RGB hex value, e.g. `Color(hex: 0x040506)` — matches how the
    /// Android source these dashboards were ported from writes its panel/accent colors.
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// A plain RGB blend between two colors — used for the Sport style's amber-to-deep-amber segment
/// ramp, where (unlike `RadialGaugeView`'s `GaugeColorStop`) a simple linear blend is what the
/// source this was ported from does (Compose's `androidx.compose.ui.graphics.lerp`).
func lerpColor(_ a: Color, _ b: Color, _ t: Double) -> Color {
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

// MARK: - Shared Canvas-drawing helpers (ported from Android's Cluster.kt)

/// The largest box with the given aspect ratio (width / height), centered in a canvas of `w` x `h`.
func fitBox(_ w: CGFloat, _ h: CGFloat, aspect: CGFloat) -> CGRect {
    if w / h > aspect {
        let bw = h * aspect
        return CGRect(x: (w - bw) / 2, y: 0, width: bw, height: h)
    } else {
        let bh = w / aspect
        return CGRect(x: 0, y: (h - bh) / 2, width: w, height: bh)
    }
}

/// A point `radius` from `center`, at `degrees` (0 = +x axis, increasing sweeps visually clockwise
/// in SwiftUI's y-down canvas space — same convention `RadialGaugeView` already uses).
func polar(_ center: CGPoint, _ radius: CGFloat, _ degrees: CGFloat) -> CGPoint {
    let a = degrees * .pi / 180
    return CGPoint(x: center.x + radius * cos(a), y: center.y + radius * sin(a))
}

/// A ring (annulus) segment path between `inner` and `outer` radius, from `start` sweeping `sweep` degrees.
func ringSegment(_ c: CGPoint, inner: CGFloat, outer: CGFloat, start: CGFloat, sweep: CGFloat) -> Path {
    var path = Path()
    path.addArc(center: c, radius: outer, startAngle: .degrees(start), endAngle: .degrees(start + sweep), clockwise: false)
    path.addArc(center: c, radius: inner, startAngle: .degrees(start + sweep), endAngle: .degrees(start), clockwise: true)
    path.closeSubpath()
    return path
}

/// A smooth curve through `pts` (Catmull-Rom, drawn as cubic Béziers) — used by the Digital style's frame outline.
func splinePath(_ pts: [CGPoint]) -> Path {
    var path = Path()
    guard let first = pts.first else { return path }
    path.move(to: first)
    for i in 0..<(pts.count - 1) {
        let p0 = pts[Swift.max(0, i - 1)]
        let p1 = pts[i]
        let p2 = pts[i + 1]
        let p3 = pts[Swift.min(pts.count - 1, i + 2)]
        let c1 = CGPoint(x: p1.x + (p2.x - p0.x) / 6, y: p1.y + (p2.y - p0.y) / 6)
        let c2 = CGPoint(x: p2.x - (p3.x - p1.x) / 6, y: p2.y - (p3.y - p1.y) / 6)
        path.addCurve(to: p2, control1: c1, control2: c2)
    }
    return path
}

extension GraphicsContext {
    /// Draws `text` anchored at `point`, shrinking the font until it fits `maxWidth` if needed —
    /// replaces Android's `alignedText`/`centredText` (SwiftUI's Canvas text doesn't auto-shrink).
    func drawFitted(
        _ text: String, at point: CGPoint, size: CGFloat, color: Color,
        anchor: UnitPoint = .leading, weight: Font.Weight = .bold, maxWidth: CGFloat = .infinity
    ) {
        guard !text.isEmpty else { return }
        func resolved(_ px: CGFloat) -> GraphicsContext.ResolvedText {
            resolve(Text(text).font(.system(size: px, weight: weight, design: .rounded)).foregroundColor(color))
        }
        var px = size
        var resolvedText = resolved(px)
        let measured = resolvedText.measure(in: CGSize(width: CGFloat.infinity, height: CGFloat.infinity))
        if measured.width > maxWidth, measured.width > 0 {
            px *= maxWidth / measured.width
            resolvedText = resolved(px)
        }
        draw(resolvedText, at: point, anchor: anchor)
    }

    /// Draws `body` mirrored horizontally around `pivotX` when `mirrored` is true, otherwise as-is.
    /// Scoped to a fresh layer so the mirroring never leaks into the rest of the canvas — the SwiftUI
    /// equivalent of Android's `withTransform({ scale(-1f, 1f, pivot = center) }) { ... }`.
    func drawMirrored(around pivotX: CGFloat, mirrored: Bool, _ body: (inout GraphicsContext) -> Void) {
        drawLayer { layer in
            if mirrored {
                layer.concatenate(
                    CGAffineTransform(translationX: pivotX, y: 0)
                        .scaledBy(x: -1, y: 1)
                        .translatedBy(x: -pivotX, y: 0)
                )
            }
            body(&layer)
        }
    }
}
