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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

// The "LCD" dashboard: a wide amber tach sweeping across the top of a black panel, an amber LCD window with
// seven-segment digits (unlit segments faintly showing), and two half-dial segment gauges.
private object Lcd {
    val Panel = Color(0xFF070707)
    val Band = Color(0xFF3A3411)         // unlit LCD band: dim olive
    val Lit = Color(0xFFFFB21E)
    val LitDeep = Color(0xFFFF9419)
    val Red = Color(0xFFFF5A1F)
    val RedUnlit = Color(0xFF4A1E0C)
    val Tick = Color(0xFFF2F2F2)
    val Window = Color(0xFF24130A)
    val WindowDeep = Color(0xFF130A05)
    val On = Color(0xFFFF8A50)           // lit LCD segment
    val Ghost = Color(0xFF2C170C)        // unlit LCD segment: only just visible, so lit digits stand out
    val Strip = Color(0xFF111111)
    val Edge = Color(0xFF1C1C1C)
}

private const val ASPECT = 2.2f
private const val TACH_START = 205f
private const val TACH_SWEEP = 110f
private const val TACH_SEGMENTS = 84
private val LcdAnimation = tween<Float>(durationMillis = 200, easing = LinearOutSlowInEasing)

// Segments a-g of a seven-segment digit, as bits: a=1 b=2 c=4 d=8 e=16 f=32 g=64.
private val SEGMENTS = mapOf(
    '0' to 0b0111111, '1' to 0b0000110, '2' to 0b1011011, '3' to 0b1001111, '4' to 0b1100110,
    '5' to 0b1101101, '6' to 0b1111101, '7' to 0b0000111, '8' to 0b1111111, '9' to 0b1101111,
    '-' to 0b1000000, '—' to 0b1000000,
)

private fun hSegment(x1: Float, x2: Float, y: Float, t: Float) = Path().apply {
    moveTo(x1, y)
    lineTo(x1 + t / 2, y - t / 2)
    lineTo(x2 - t / 2, y - t / 2)
    lineTo(x2, y)
    lineTo(x2 - t / 2, y + t / 2)
    lineTo(x1 + t / 2, y + t / 2)
    close()
}

private fun vSegment(x: Float, y1: Float, y2: Float, t: Float) = Path().apply {
    moveTo(x, y1)
    lineTo(x + t / 2, y1 + t / 2)
    lineTo(x + t / 2, y2 - t / 2)
    lineTo(x, y2)
    lineTo(x - t / 2, y2 - t / 2)
    lineTo(x - t / 2, y1 + t / 2)
    close()
}

/** Seven-segment [text], right-aligned at [right], digits [digitH] tall. Every segment shows faintly, like real LCD glass. */
private fun DrawScope.sevenSegment(text: String, right: Float, top: Float, digitH: Float, on: Color, ghost: Color) {
    val dw = digitH * 0.52f
    val t = digitH * 0.13f
    val gap = t * 0.18f
    val pitch = dw + digitH * 0.2f
    text.reversed().forEachIndexed { i, ch ->
        val bits = SEGMENTS[ch] ?: 0
        val x = right - dw - i * pitch
        val y = top
        val segs = listOf(
            hSegment(x + gap, x + dw - gap, y + t / 2, t),                       // a
            vSegment(x + dw - t / 2, y + t / 2 + gap, y + digitH / 2 - gap, t),  // b
            vSegment(x + dw - t / 2, y + digitH / 2 + gap, y + digitH - t / 2 - gap, t), // c
            hSegment(x + gap, x + dw - gap, y + digitH - t / 2, t),              // d
            vSegment(x + t / 2, y + digitH / 2 + gap, y + digitH - t / 2 - gap, t),      // e
            vSegment(x + t / 2, y + t / 2 + gap, y + digitH / 2 - gap, t),       // f
            hSegment(x + gap, x + dw - gap, y + digitH / 2, t),                  // g
        )
        segs.forEachIndexed { s, path -> drawPath(path, if (bits and (1 shl s) != 0) on else ghost) }
    }
}

/** A half-dial segment gauge (left end = low), with end labels and the value in the middle. */
private fun DrawScope.halfDial(
    measurer: TextMeasurer, c: Offset, r: Float, fraction: Float, r0: SportReadout, low: String, high: String,
) {
    val n = 18
    val seg = 180f / n
    val tint = when (r0.level) {
        Level.NORMAL -> Lcd.Lit
        Level.WARNING -> Lcd.LitDeep
        Level.CRITICAL -> Lcd.Red
    }
    for (i in 0 until n) {
        val lit = r0.fraction != null && fraction * n > i + 0.001f
        drawPath(ringSegment(c, r * 0.74f, r, 180f + i * seg + 0.6f, seg - 1.2f), if (lit) tint else Lcd.Band)
    }
    val rail = r * 0.66f
    drawArc(Lcd.Tick, 180f, 180f, false, Offset(c.x - rail, c.y - rail), Size(rail * 2, rail * 2), style = Stroke(1.5.dp.toPx()))
    drawLine(Lcd.Tick, Offset(c.x - r * 1.04f, c.y), Offset(c.x - r * 0.7f, c.y), 3.dp.toPx())     // low-end cap
    drawLine(Lcd.Red, Offset(c.x + r * 0.7f, c.y), Offset(c.x + r * 1.04f, c.y), 3.dp.toPx())      // high-end cap
    alignedText(measurer, low, c.x - r * 0.48f, c.y - r * 0.18f, r * 0.24f, Lcd.Tick, anchor = 0.5f)
    alignedText(measurer, high, c.x + r * 0.48f, c.y - r * 0.18f, r * 0.24f, Lcd.Tick, anchor = 0.5f)
    alignedText(measurer, r0.text + r0.units, c.x, c.y - r * 0.36f, r * 0.24f, Color.White, anchor = 0.5f, maxWidth = r * 0.6f)
    alignedText(measurer, r0.label, c.x, c.y + r * 0.2f, r * 0.2f, Lcd.Tick.copy(alpha = 0.7f), anchor = 0.5f, weight = FontWeight.Medium)
}

@Composable
private fun LcdCluster(d: ClusterData, modifier: Modifier) {
    val rpmF by animateFloatAsState(d.rpmSpec.fraction(d.rpm ?: d.rpmSpec.min), LcdAnimation, label = "lcdRpm")
    val throttleF by animateFloatAsState(d.throttle.fraction ?: 0f, LcdAnimation, label = "lcdThrottle")
    val coolantF by animateFloatAsState(d.coolant.fraction ?: 0f, LcdAnimation, label = "lcdCoolant")
    val measurer = rememberTextMeasurer()
    val clock = rememberClock()
    // The big digits are GPS speed (a dash until there's a fix); RPM is the sweep above.
    val big = d.speedText
    val bigUnits = d.speedUnits
    val t = d.rpmSpec.thresholds
    val critF = t.critHigh?.let { d.rpmSpec.fraction(it) }
    val warnF = t.warnHigh?.let { d.rpmSpec.fraction(it) }

    Canvas(modifier.semantics { contentDescription = "RPM ${d.rpmText}, ${d.speedText} ${d.speedUnits}" }) {
        val b = fitBox(size.width, size.height, ASPECT)
        val u = b.height
        fun at(fx: Float, fy: Float) = Offset(b.left + fx * b.width, b.top + fy * u)

        // Tach: a wide arc of a big circle whose centre sits below the panel.
        val c = Offset(b.left + 0.45f * b.width, b.top + 1.22f * u)
        val r = 1.1f * u
        val seg = TACH_SWEEP / TACH_SEGMENTS
        for (i in 0 until TACH_SEGMENTS) {
            val f0 = i / TACH_SEGMENTS.toFloat()
            val red = critF != null && f0 >= critF
            val lit = rpmF * TACH_SEGMENTS > i + 0.001f
            val color = when {
                red -> if (lit) Lcd.Red else Lcd.RedUnlit
                lit -> if (warnF != null && f0 >= warnF) Lcd.LitDeep else Lcd.Lit
                else -> Lcd.Band
            }
            val gap = if (red) 0.55f else 0.18f       // the redline reads as hatching
            drawPath(ringSegment(c, r - 0.085f * u, r, TACH_START + i * seg + gap / 2, seg - gap), color)
        }
        val rail = r - 0.095f * u
        drawArc(Lcd.Tick, TACH_START, TACH_SWEEP, false, Offset(c.x - rail, c.y - rail), Size(rail * 2, rail * 2), style = Stroke(1.5.dp.toPx()))
        val spec = d.rpmSpec
        val major = if (spec.majorTick > 0) spec.majorTick else (spec.max - spec.min) / 8
        var v = spec.min
        var n = 0
        while (v <= spec.max + 1e-6) {
            val a = TACH_START + TACH_SWEEP * spec.fraction(v)
            val isMajor = n % 2 == 0
            drawLine(Lcd.Tick, polar(c, rail, a), polar(c, rail - (if (isMajor) 0.06f else 0.035f) * u, a), (if (isMajor) 3.dp else 2.dp).toPx())
            if (isMajor) {
                val p = polar(c, r - 0.2f * u, a)
                alignedText(measurer, formatValue(v / spec.tickDivisor, 0), p.x, p.y, 0.12f * u, Lcd.Tick, anchor = 0.5f)
            }
            v += major / 2
            n++
        }
        val unitsAt = at(0.235f, 0.74f)
        alignedText(measurer, "×1000 r/min", unitsAt.x, unitsAt.y, 0.055f * u, Lcd.Tick, anchor = 0.5f, weight = FontWeight.Medium)

        // LCD window: big seven-segment readout, a divider, then intake air temp and the clock.
        val win = Rect(at(0.37f, 0.42f), at(0.655f, 0.95f))
        drawRoundRect(
            Brush.verticalGradient(listOf(Lcd.Window, Lcd.WindowDeep), win.top, win.bottom),
            win.topLeft, win.size, CornerRadius(0.03f * u),
        )
        // Four digit places, like the glass: blanks show as ghost 8s.
        val digits = big.replace(",", "").take(4).padStart(4, ' ')
        val digitH = minOf(0.3f * u, (win.width - 0.08f * u) / (4 * 0.72f - 0.2f))
        sevenSegment(digits, win.right - 0.04f * u, win.top + 0.05f * u + (0.3f * u - digitH) / 2, digitH, Lcd.On, Lcd.Ghost)
        alignedText(measurer, bigUnits, win.right + 0.02f * u, win.top + 0.3f * u, 0.07f * u, Lcd.On)
        drawLine(Lcd.On.copy(alpha = 0.5f), Offset(win.left + 0.03f * u, win.top + 0.4f * u), Offset(win.right - 0.03f * u, win.top + 0.4f * u), 1.5.dp.toPx())
        val line = win.top + 0.46f * u
        alignedText(measurer, d.footer, win.left + 0.04f * u, line, 0.06f * u, Lcd.On)
        alignedText(measurer, clock, win.right - 0.04f * u, line, 0.06f * u, Lcd.On, anchor = 1f)

        // Readout strip, lower left.
        val strip = Rect(at(0.08f, 0.85f), at(0.345f, 0.99f))
        drawRoundRect(Lcd.Strip, strip.topLeft, strip.size, CornerRadius(0.03f * u))
        listOf(d.oil, d.battery, d.map).forEachIndexed { i, r0 ->
            val cw = strip.width / 3
            val cx = strip.left + cw * (i + 0.5f)
            val tint = if (r0.level == Level.NORMAL) Lcd.On else Lcd.Red
            alignedText(measurer, r0.label, cx, strip.top + strip.height * 0.3f, 0.045f * u, Lcd.Tick.copy(alpha = 0.7f), anchor = 0.5f, weight = FontWeight.Medium)
            alignedText(measurer, r0.text + r0.units, cx, strip.top + strip.height * 0.68f, 0.06f * u, tint, anchor = 0.5f, maxWidth = cw * 0.92f)
        }

        // Half dials, stepping down to the right like the reference.
        halfDial(measurer, at(0.835f, 0.74f), 0.16f * u, coolantF, d.coolant, "C", "H")
        halfDial(measurer, at(0.915f, 0.985f), 0.16f * u, throttleF, d.throttle, "0", "100")
    }
}

/** The LCD cluster. Landscape fills the space; portrait puts the cluster on top and the readouts under it. */
@Composable
fun LcdDashboard(d: ClusterData, modifier: Modifier = Modifier, edgeToEdge: Boolean = false) {
    val shape = RoundedCornerShape(28.dp)
    val panel = if (edgeToEdge) modifier.background(Lcd.Panel).padding(12.dp)
    else modifier.clip(shape).background(Lcd.Panel).border(BorderStroke(1.dp, Lcd.Edge), shape).padding(12.dp)
    BoxWithConstraints(panel) {
        if (maxWidth > maxHeight * 1.2f) {
            LcdCluster(d, Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                LcdCluster(d, Modifier.fillMaxWidth().aspectRatio(ASPECT))
                listOf(d.throttle, d.coolant, d.oil, d.battery, d.map).forEach { SportReadoutRow(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}
