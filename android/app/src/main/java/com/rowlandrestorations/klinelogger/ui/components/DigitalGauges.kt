package com.rowlandrestorations.klinelogger.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.rowlandrestorations.klinelogger.R
import com.rowlandrestorations.klinelogger.ui.theme.Condensed

// The "Digital" dashboard: twin frames (speed left, RPM mirrored right), each one smooth curve with a white
// outline, a slim blue band that fills with the value, fine ticks and a needle; big white numbers over a rule,
// faint road lines and the rider's ski between, and small readouts filling the space around them.
private object Digital {
    val Panel = Color(0xFF04070D)
    val Blue = Color(0xFF2A9DFF)
    val BlueDeep = Color(0xFF1456C8)
    val Track = Color(0xFF101B2B)
    val Line = Color(0xFFDCE4EE)
    val Label = Color(0xFFAEBBCC)
    val Faint = Color(0xFF8A99AD)
    val Red = Color(0xFFFF4D4F)
    val Amber = Color(0xFFFFB020)
    val Edge = Color(0xFF141C28)
}

private const val ASPECT = 2.1f
private val DigitalAnimation = tween<Float>(durationMillis = 220, easing = LinearOutSlowInEasing)

// Points the left frame's outline passes through, as fractions of the cluster box (x of width, y of height):
// value 0 at the bottom, curving left and up the side, then across the top. A spline joins them smoothly.
private val FRAME = listOf(
    0.33f to 0.80f, 0.24f to 0.798f, 0.145f to 0.765f, 0.088f to 0.68f, 0.072f to 0.56f,
    0.088f to 0.44f, 0.14f to 0.345f, 0.25f to 0.275f, 0.36f to 0.243f, 0.46f to 0.228f,
)

private fun levelTint(level: Level, normal: Color) = when (level) {
    Level.NORMAL -> normal
    Level.WARNING -> Digital.Amber
    Level.CRITICAL -> Digital.Red
}

/** A smooth curve through [pts] (Catmull-Rom, drawn as cubic Béziers). */
private fun splinePath(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[maxOf(0, i - 1)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[minOf(pts.size - 1, i + 2)]
        val c1 = p1 + (p2 - p0) / 6f
        val c2 = p2 - (p3 - p1) / 6f
        cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
}

/** Outward normal of a path at distance [d] along it: to the right of travel. */
private fun PathMeasure.outward(d: Float): Offset {
    val t = getTangent(d)
    return Offset(t.y, -t.x)
}

/** The outline, and the band that runs a fixed distance inside it. */
private class Frame(val outline: Path, val outer: PathMeasure, val band: Path, val bandMeasure: PathMeasure)

private fun frame(b: Rect): Frame {
    val u = b.height
    val outline = splinePath(FRAME.map { Offset(b.left + it.first * b.width, b.top + it.second * u) })
    val outer = PathMeasure().apply { setPath(outline, false) }
    // Offset by sampling, so the gap between outline and band is the same all the way round.
    val inset = 0.032f * u
    val band = Path()
    val samples = 240
    for (k in 0..samples) {
        val d = outer.length * k / samples
        val q = outer.getPosition(d) - outer.outward(d) * inset
        if (k == 0) band.moveTo(q.x, q.y) else band.lineTo(q.x, q.y)
    }
    return Frame(outline, outer, band, PathMeasure().apply { setPath(band, false) })
}

/** One frame's graphics, in unmirrored coordinates. Text is drawn separately so it never mirrors. */
private fun DrawScope.frameGraphics(b: Rect, f: Frame, spec: GaugeSpec, fraction: Float, tint: Color) {
    val u = b.height
    val width = 0.026f * u
    val m = f.bandMeasure
    val len = m.length
    val t = spec.thresholds
    val critF = t.critHigh?.let { spec.fraction(it) }
    val warnF = t.warnHigh?.let { spec.fraction(it) }

    drawPath(f.band, Digital.Track, style = Stroke(width, cap = StrokeCap.Round))
    if (critF != null) {
        val red = Path()
        m.getSegment(critF * len, len, red, true)
        drawPath(red, Digital.Red.copy(alpha = 0.28f), style = Stroke(width, cap = StrokeCap.Round))
    }
    if (fraction > 0.002f) {
        val lit = Path()
        m.getSegment(0f, fraction * len, lit, true)
        val end = when {
            critF != null && fraction >= critF -> Digital.Red
            warnF != null && fraction >= warnF -> Digital.Amber
            else -> Digital.Blue
        }
        drawPath(lit, end.copy(alpha = 0.1f), style = Stroke(width * 1.9f, cap = StrokeCap.Round))     // faint halo
        drawPath(
            lit, Brush.linearGradient(listOf(Digital.BlueDeep, end), m.getPosition(0f), m.getPosition(fraction * len)),
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }

    // Outline, brightest at the bottom and fading slightly toward the top end.
    drawPath(
        f.outline,
        Brush.linearGradient(
            listOf(Digital.Line, Digital.Line.copy(alpha = 0.55f)),
            f.outer.getPosition(0f), f.outer.getPosition(f.outer.length),
        ),
        style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round),
    )

    // Ticks: majors with labels (drawn later), minors halfway between.
    val o = f.outer
    val major = if (spec.majorTick > 0) spec.majorTick else (spec.max - spec.min) / 7
    var v = spec.min
    var n = 0
    while (v <= spec.max + 1e-6) {
        val d = spec.fraction(v) * o.length
        val p = o.getPosition(d)
        val nrm = o.outward(d)
        val isMajor = n % 2 == 0
        drawLine(
            if (isMajor) Digital.Line else Digital.Line.copy(alpha = 0.45f),
            p + nrm * 0.008f * u, p + nrm * (if (isMajor) 0.034f else 0.022f) * u,
            (if (isMajor) 2.dp else 1.2.dp).toPx(), StrokeCap.Round,
        )
        v += major / 2
        n++
    }

    // Needle: a slim white bar standing inward from the band, with a soft hint of colour behind it.
    val d = fraction * len
    val p = m.getPosition(d)
    val nrm = m.outward(d)
    val glow = p - nrm * 0.045f * u
    drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.12f), Color.Transparent), glow, 0.1f * u), 0.1f * u, glow)
    drawLine(tint.copy(alpha = 0.18f), p + nrm * 0.004f * u, p - nrm * 0.09f * u, 8.dp.toPx(), StrokeCap.Round)
    drawLine(Color.White, p + nrm * 0.004f * u, p - nrm * 0.085f * u, 3.5.dp.toPx(), StrokeCap.Round)
}

/** Tick labels just outside the outline. [mirror] maps an x to where it lands on screen. */
private fun DrawScope.frameLabels(measurer: TextMeasurer, b: Rect, f: Frame, spec: GaugeSpec, mirror: (Float) -> Float) {
    val u = b.height
    val o = f.outer
    val major = if (spec.majorTick > 0) spec.majorTick else (spec.max - spec.min) / 7
    var v = spec.min
    while (v <= spec.max + 1e-6) {
        val d = spec.fraction(v) * o.length
        val q = o.getPosition(d) + o.outward(d) * 0.075f * u
        alignedText(measurer, formatValue(v / spec.tickDivisor, 0), mirror(q.x), q.y, 0.052f * u, Digital.Label, anchor = 0.5f, weight = FontWeight.Medium)
        v += major
    }
}

private fun DrawScope.vehicle(painter: Painter, area: Rect, flip: Boolean) {
    val intrinsic = painter.intrinsicSize
    val aspect = if (intrinsic.isSpecified() && intrinsic.height > 0) intrinsic.width / intrinsic.height else 2.5f
    val w = minOf(area.width, area.height * aspect)
    val h = w / aspect
    withTransform({
        if (flip) scale(-1f, 1f, pivot = area.center)
        translate(area.center.x - w / 2, area.center.y - h / 2)
    }) {
        with(painter) { draw(Size(w, h)) }
    }
}

private fun Size.isSpecified() = width.isFinite() && height.isFinite()

@Composable
private fun DigitalCluster(d: ClusterData, modifier: Modifier, vehicle: ImageBitmap?, flipVehicle: Boolean) {
    val speedF by animateFloatAsState(d.speedSpec.fraction(d.speed ?: d.speedSpec.min), DigitalAnimation, label = "digSpeed")
    val rpmF by animateFloatAsState(d.rpmSpec.fraction(d.rpm ?: d.rpmSpec.min), DigitalAnimation, label = "digRpm")
    val measurer = rememberTextMeasurer()
    // The rider's own picture when they've picked one, otherwise the built-in drawing.
    val builtIn = painterResource(R.drawable.dash_vehicle)
    val painter = if (vehicle != null) remember(vehicle) { BitmapPainter(vehicle) } else builtIn
    val clock = rememberClock()
    val rpmLevel = d.rpmSpec.thresholds.check(d.rpm).level
    val speedLevel = d.speedSpec.thresholds.check(d.speed).level

    Canvas(modifier.semantics { contentDescription = "${d.speedText} ${d.speedUnits}, RPM ${d.rpmText}" }) {
        val b = fitBox(size.width, size.height, ASPECT)
        val u = b.height
        fun at(fx: Float, fy: Float) = Offset(b.left + fx * b.width, b.top + fy * u)
        fun x(fx: Float) = b.left + fx * b.width
        fun mirror(px: Float) = 2 * b.center.x - px
        val f = frame(b)

        // Road: faint lines running to a vanishing point between the frames.
        val vp = at(0.5f, 0.40f)
        for (k in -3..3) {
            val bottom = at(0.5f + k * 0.11f, 0.94f)
            val s = (0.52f - 0.40f) / (0.94f - 0.40f)
            val top = Offset(vp.x + (bottom.x - vp.x) * s, b.top + 0.52f * u)
            drawLine(Brush.verticalGradient(listOf(Color.Transparent, Digital.Faint.copy(alpha = 0.28f)), top.y, bottom.y), top, bottom, 1.2.dp.toPx())
        }
        for (y in listOf(0.62f, 0.74f, 0.9f)) {
            val half = 0.06f + (y - 0.5f) * 0.35f
            drawLine(Digital.Faint.copy(alpha = 0.1f), at(0.5f - half, y), at(0.5f + half, y), 1.dp.toPx())
        }
        vehicle(painter, Rect(at(0.37f, 0.48f), at(0.63f, 0.84f)), flipVehicle)

        // Speed frame (left), then the RPM frame mirrored onto the right.
        frameGraphics(b, f, d.speedSpec, speedF, levelTint(speedLevel, Digital.Blue))
        withTransform({ scale(-1f, 1f, pivot = b.center) }) {
            frameGraphics(b, f, d.rpmSpec, rpmF, levelTint(rpmLevel, Digital.Blue))
        }
        frameLabels(measurer, b, f, d.speedSpec) { it }
        frameLabels(measurer, b, f, d.rpmSpec, ::mirror)

        // Big numbers over a rule; the RPM side is the mirror image.
        val ruleY = b.top + 0.47f * u
        val numY = b.top + 0.39f * u
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, Digital.Line.copy(alpha = 0.8f), Digital.Line.copy(alpha = 0.8f)), x(0.11f), x(0.26f)),
            Offset(x(0.11f), ruleY), Offset(x(0.47f), ruleY), 1.5.dp.toPx(),
        )
        drawLine(
            Brush.horizontalGradient(listOf(Digital.Line.copy(alpha = 0.8f), Digital.Line.copy(alpha = 0.8f), Color.Transparent), mirror(x(0.26f)), mirror(x(0.11f))),
            Offset(mirror(x(0.47f)), ruleY), Offset(mirror(x(0.11f)), ruleY), 1.5.dp.toPx(),
        )
        alignedText(measurer, d.speedText, x(0.38f), numY, 0.15f * u, levelTint(speedLevel, Color.White), anchor = 1f, maxWidth = 0.2f * b.width)
        alignedText(measurer, d.speedUnits, x(0.392f), numY + 0.045f * u, 0.05f * u, Digital.Label, weight = FontWeight.Medium)
        alignedText(measurer, d.rpmText, mirror(x(0.38f)), numY, 0.15f * u, levelTint(rpmLevel, Color.White), anchor = 0f, maxWidth = 0.2f * b.width)
        alignedText(measurer, "r/min", mirror(x(0.392f)), numY + 0.045f * u, 0.05f * u, Digital.Label, anchor = 1f, weight = FontWeight.Medium)

        // Small readouts under the rules: oil and battery in the speed frame, intake pressure in the RPM frame.
        fun row(r0: SportReadout, y: Float, left: Float, right: Float) {
            alignedText(measurer, r0.label, left, b.top + y * u, 0.04f * u, Digital.Label, weight = FontWeight.Medium)
            alignedText(measurer, r0.text + " " + r0.units, right, b.top + y * u, 0.052f * u, levelTint(r0.level, Color.White), anchor = 1f)
        }
        row(d.oil, 0.53f, x(0.15f), x(0.34f))
        row(d.battery, 0.595f, x(0.15f), x(0.34f))
        row(d.map, 0.53f, mirror(x(0.34f)), mirror(x(0.15f)))

        // Bottom row, one line each: clock, throttle, coolant, intake air temp. Below the tick labels, so nothing overlaps.
        val baseY = b.top + 0.965f * u
        fun bottom(r0: SportReadout, edge: Float, fromLeft: Boolean) {
            val gap = 0.015f * u
            val labelPx = 0.038f * u
            val valuePx = 0.058f * u
            val value = r0.text + " " + r0.units
            val tint = levelTint(r0.level, Color.White)
            if (fromLeft) {
                val lw = measurer.measure(r0.label, TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Medium, fontSize = labelPx.toSp())).size.width
                alignedText(measurer, r0.label, edge, baseY, labelPx, Digital.Label, weight = FontWeight.Medium)
                alignedText(measurer, value, edge + lw + gap, baseY, valuePx, tint)
            } else {
                val vw = measurer.measure(value, TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = valuePx.toSp())).size.width
                alignedText(measurer, value, edge, baseY, valuePx, tint, anchor = 1f)
                alignedText(measurer, r0.label, edge - vw - gap, baseY, labelPx, Digital.Label, anchor = 1f, weight = FontWeight.Medium)
            }
        }
        bottom(d.throttle, x(0.13f), fromLeft = true)
        bottom(d.coolant, mirror(x(0.13f)), fromLeft = false)
        alignedText(measurer, clock, x(0.015f), baseY, 0.05f * u, Color.White)
        alignedText(measurer, d.footer, x(0.985f), baseY, 0.05f * u, Color.White, anchor = 1f)
    }
}

/** The Digital cluster. Landscape fills the space; portrait puts the cluster on top and the readouts under it. */
@Composable
fun DigitalDashboard(
    d: ClusterData,
    modifier: Modifier = Modifier,
    edgeToEdge: Boolean = false,
    vehicle: ImageBitmap? = null,
    flipVehicle: Boolean = false,
) {
    val shape = RoundedCornerShape(28.dp)
    val panel = if (edgeToEdge) modifier.background(Digital.Panel).padding(12.dp)
    else modifier.clip(shape).background(Digital.Panel).border(BorderStroke(1.dp, Digital.Edge), shape).padding(12.dp)
    BoxWithConstraints(panel) {
        if (maxWidth > maxHeight * 1.2f) {
            DigitalCluster(d, Modifier.fillMaxSize(), vehicle, flipVehicle)
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                DigitalCluster(d, Modifier.fillMaxWidth().aspectRatio(ASPECT), vehicle, flipVehicle)
                listOf(d.throttle, d.coolant, d.oil, d.battery, d.map).forEach { SportReadoutRow(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}
