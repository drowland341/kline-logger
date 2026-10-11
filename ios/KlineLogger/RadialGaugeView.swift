//
//  RadialGaugeView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//

import SwiftUI

struct RadialGaugeView: View {
    var value: Double
    var range: ClosedRange<Double>
    var title: String
    var unit: String
    var valueText: String
    var tickCount: Int = 10

    private let startAngle: Double = 135
    private let sweep: Double = 270

    private var progress: Double {
        guard range.upperBound > range.lowerBound else { return 0 }
        let clamped = min(max(value, range.lowerBound), range.upperBound)
        return (clamped - range.lowerBound) / (range.upperBound - range.lowerBound)
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
                    }

                    var progressPath = Path()
                    progressPath.addArc(center: center, radius: radius, startAngle: .degrees(startAngle), endAngle: .degrees(startAngle + max(progress, 0.0015) * sweep), clockwise: false)

                    context.drawLayer { ctx in
                        ctx.addFilter(.blur(radius: lineWidth * 0.4))
                        ctx.stroke(progressPath, with: .color(.accentColor.opacity(0.6)), style: StrokeStyle(lineWidth: lineWidth * 1.25, lineCap: .round))
                    }

                    context.stroke(
                        progressPath,
                        with: .conicGradient(
                            Gradient(colors: [.accentColor.opacity(0.55), .accentColor]),
                            center: center,
                            angle: .degrees(startAngle)
                        ),
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                    )

                    let tipAngle = (startAngle + max(progress, 0.0015) * sweep) * .pi / 180
                    let tip = CGPoint(x: center.x + cos(tipAngle) * radius, y: center.y + sin(tipAngle) * radius)
                    context.fill(Path(ellipseIn: CGRect(x: tip.x - lineWidth * 0.28, y: tip.y - lineWidth * 0.28, width: lineWidth * 0.56, height: lineWidth * 0.56)), with: .color(.white.opacity(0.9)))

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
                            .foregroundStyle(.primary),
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
        .animation(.easeOut(duration: 0.25), value: value)
    }
}

#Preview("RPM") {
    RadialGaugeView(value: 4250, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "4250")
        .frame(width: 220, height: 220)
        .padding()
}

#Preview("Speed") {
    RadialGaugeView(value: 23.4, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "23.4")
        .frame(width: 220, height: 220)
        .padding()
}

#Preview("Stacked") {
    VStack(spacing: 16) {
        RadialGaugeView(value: 6100, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "6100")
        RadialGaugeView(value: 41, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "41.0")
    }
    .padding()
}

#Preview("Rotated 90°") {
    VStack(spacing: 16) {
        RadialGaugeView(value: 6100, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "6100")
        RadialGaugeView(value: 41, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "41.0")
    }
    .padding()
    .rotationEffect(.degrees(90))
}
