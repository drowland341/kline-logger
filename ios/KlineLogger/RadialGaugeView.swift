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
            let lineWidth = size * 0.09
            let radius = (size - lineWidth) / 2
            let center = CGPoint(x: size / 2, y: size / 2)

            ZStack {
                Canvas { context, _ in
                    var trackPath = Path()
                    trackPath.addArc(center: center, radius: radius, startAngle: .degrees(startAngle), endAngle: .degrees(startAngle + sweep), clockwise: false)
                    context.stroke(trackPath, with: .color(.secondary.opacity(0.15)), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))

                    for i in 0...tickCount {
                        let t = Double(i) / Double(tickCount)
                        let angle = (startAngle + t * sweep) * .pi / 180
                        let outer = radius + lineWidth * 0.62
                        let inner = radius + lineWidth * 0.32
                        var tickPath = Path()
                        tickPath.move(to: CGPoint(x: center.x + cos(angle) * inner, y: center.y + sin(angle) * inner))
                        tickPath.addLine(to: CGPoint(x: center.x + cos(angle) * outer, y: center.y + sin(angle) * outer))
                        context.stroke(tickPath, with: .color(.secondary.opacity(0.5)), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    }

                    var progressPath = Path()
                    progressPath.addArc(center: center, radius: radius, startAngle: .degrees(startAngle), endAngle: .degrees(startAngle + progress * sweep), clockwise: false)
                    context.stroke(progressPath, with: .color(.accentColor), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                }
                .frame(width: size, height: size)

                VStack(spacing: 2) {
                    Text(valueText)
                        .font(.system(size: size * 0.2, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                    Text(unit)
                        .font(.system(size: size * 0.08))
                        .foregroundStyle(.secondary)
                    Text(title)
                        .font(.system(size: size * 0.075))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(width: size * 0.8)
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

#Preview("Side by Side") {
    HStack(spacing: 16) {
        RadialGaugeView(value: 6100, range: 0...8000, title: "Engine RPM", unit: "RPM", valueText: "6100")
        RadialGaugeView(value: 41, range: 0...70, title: "GPS Speed", unit: "mph", valueText: "41.0")
    }
    .padding()
}
