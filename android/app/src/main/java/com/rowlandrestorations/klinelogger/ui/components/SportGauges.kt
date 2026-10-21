package com.rowlandrestorations.klinelogger.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rowlandrestorations.klinelogger.ui.theme.Condensed
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// The "Sport" dashboard: a black glass cluster with twin mirrored pods (speed and RPM), each a tall bezel arc
// with ticks, a needle, lit segments and a perspective grid floor behind a big yellow readout.
private object Sport {
    val Panel = Color(0xFF040506)
    val Tunnel = Color(0xFF2C3238)
    val Bezel = Color(0xFFF1F4F7)
    val BezelShade = Color(0xFF6F7781)
    val Tick = Color(0xFFDCE1E7)
    val TickFaint = Color(0xFF59616B)
    val Yellow = Color(0xFFFFE135)
    val Amber = Color(0xFFFFB000)
    val AmberDeep = Color(0xFFFF7A00)
    val Grid = Color(0xFFFFC21A)
    val Red = Color(0xFFFF3B30)
    val Unlit = Color(0xFF231A0A)
    val Track = Color(0xFF1A1E23)
    val Label = Color(0xFFC9CFD6)
    val Edge = Color(0xFF1C2026)
}

private const val START = 115f      // bottom of the arc; the value sweeps up to START + SWEEP at the top
private const val SWEEP = 130f
private const val SEGMENTS = 30
private val SportAnimation = tween<Float>(durationMillis = 220, easing = LinearOutSlowInEasing)

/** One small Sport readout: label, formatted value, units, alert level and where it sits in its range. */
data class SportReadout(
    val label: String,
    val text: String,
    val units: String,
    val level: Level,
    val fraction: Float?,
    val note: String? = null,
)

private fun levelTint(level: Level, normal: Color) = when (level) {
    Level.NORMAL -> normal
    Level.WARNING -> Sport.AmberDeep
    Level.CRITICAL -> Sport.Red
}

internal fun ringSegment(c: Offset, inner: Float, outer: Float, start: Float, sweep: Float) = Path().apply {
    arcTo(Rect(c, outer), start, sweep, forceMoveTo = true)
    arcTo(Rect(c, inner), start + sweep, -sweep, forceMoveTo = false)
    close()
}

internal fun polar(c: Offset, radius: Float, degrees: Float): Offset {
    val a = Math.toRadians(degrees.toDouble())
    return Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
}

/** Draws [text] centred on [center], shrunk until it fits [maxWidth]. */
internal fun DrawScope.centredText(
    measurer: TextMeasurer, text: String, center: Offset, sizePx: Float, maxWidth: Float, color: Color,
    weight: FontWeight = FontWeight.Bold,
) {
    var px = sizePx
    var layout = measurer.measure(text, TextStyle(fontFamily = Condensed, fontWeight = weight, fontSize = px.toSp(), color = color))
    if (layout.size.width > maxWidth && layout.size.width > 0) {
        px *= maxWidth / layout.size.width
        layout = measurer.measure(text, TextStyle(fontFamily = Condensed, fontWeight = weight, fontSize = px.toSp(), color = color))
    }
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/**
 * One cluster pod: the bezel arc on the left with the readout to its right, or the mirror image when [mirrored]
 * (so two pods face each other like a twin-screen dash).
 */
@Composable
fun SportPod(spec: GaugeSpec, value: Double?, readout: String, units: String, mirrored: Boolean, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(spec.fraction(value ?: spec.min), SportAnimation, label = "sportPod")
    val measurer = rememberTextMeasurer()
    val t = spec.thresholds
    val warnF = t.warnHigh?.let { spec.fraction(it) }
    val critF = t.critHigh?.let { spec.fraction(it) }
    val tint = levelTint(t.check(value).level, Sport.Yellow)

    Canvas(modifier.semantics { contentDescription = "${spec.label} $readout $units" }) {
        val w = size.width
        val h = size.height
        val r = minOf(h * 0.46f, w * 0.6f)
        val c = Offset(r * 1.12f, h * 0.5f)           // arc centre, unmirrored
        fun mx(x: Float) = if (mirrored) w - x else x

        withTransform({ if (mirrored) scale(-1f, 1f, pivot = center) }) {
            // Tunnel: a soft grey glow behind the readout, fading to black.
            drawRect(
                Brush.radialGradient(
                    listOf(Sport.Tunnel, Sport.Panel.copy(alpha = 0f)),
                    center = Offset(c.x + r * 0.6f, c.y - r * 0.25f), radius = r * 1.15f,
                ),
            )

            // Perspective grid floor inside the arc.
            clipPath(Path().apply { addOval(Rect(c, r * 0.83f)) }) {
                val horizon = c.y + r * 0.08f
                val bottom = c.y + r * 0.85f
                val vp = Offset(c.x + r * 0.55f, horizon - r * 0.4f)
                val line = 1.2.dp.toPx()
                val fade = Brush.verticalGradient(listOf(Sport.Grid.copy(alpha = 0f), Sport.Grid.copy(alpha = 0.6f)), horizon, bottom)
                for (k in -12..12) {
                    val bx = vp.x + k * r * 0.2f
                    val s = (bottom - horizon) / (bottom - vp.y)
                    drawLine(fade, Offset(bx + (vp.x - bx) * s, horizon), Offset(bx, bottom), line)
                }
                for (j in 1..9) {
                    val f = j / 9f
                    val y = horizon + (bottom - horizon) * f * f
                    drawLine(Sport.Grid.copy(alpha = 0.06f + 0.5f * f), Offset(c.x - r, y), Offset(c.x + r, y), line)
                }
            }

            // Lit segments: amber, deepening toward the warning zone, red past the redline.
            val seg = SWEEP / SEGMENTS
            val gap = 1.8f
            for (i in 0 until SEGMENTS) {
                val f0 = i / SEGMENTS.toFloat()
                val zone = when {
                    critF != null && f0 >= critF -> Sport.Red
                    warnF != null && f0 >= warnF -> Sport.AmberDeep
                    else -> lerp(Sport.AmberDeep, Sport.Amber, f0)
                }
                val lit = animated * SEGMENTS > i + 0.001f
                val color = when {
                    lit -> zone
                    critF != null && f0 >= critF -> Sport.Red.copy(alpha = 0.22f)
                    else -> Sport.Unlit
                }
                drawPath(ringSegment(c, r * 0.70f, r * 0.82f, START + i * seg + gap / 2, seg - gap), color)
            }

            // Tick band between a thin inner rail and the bezel.
            val rail = Size(r * 1.72f, r * 1.72f)
            drawArc(Sport.TickFaint, START, SWEEP, false, Offset(c.x - r * 0.86f, c.y - r * 0.86f), rail, style = Stroke(1.dp.toPx()))
            val major = if (spec.majorTick > 0) spec.majorTick else (spec.max - spec.min) / 7
            val minor = major / 5
            var v = spec.min
            var n = 0
            while (v <= spec.max + 1e-6) {
                val a = START + SWEEP * spec.fraction(v)
                val isMajor = n % 5 == 0
                val inner = if (isMajor) r * 0.86f else r * 0.90f
                drawLine(
                    if (isMajor) Sport.Tick else Sport.Tick.copy(alpha = 0.7f), polar(c, inner, a), polar(c, r * 0.96f, a),
                    (if (isMajor) 3.dp else 1.6.dp).toPx(), StrokeCap.Round,
                )
                v += minor
                n++
            }

            // Glossy bezel with a dark lip outside it.
            val bezel = Size(r * 2, r * 2)
            drawArc(
                Sport.Edge, START - 5, SWEEP + 10, false, Offset(c.x - r * 1.04f, c.y - r * 1.04f), Size(r * 2.08f, r * 2.08f),
                style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                Brush.verticalGradient(listOf(Sport.BezelShade, Sport.Bezel, Sport.BezelShade), c.y - r, c.y + r),
                START - 5, SWEEP + 10, false, Offset(c.x - r, c.y - r), bezel,
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            )

            // Needle, with a soft glow under it.
            val a = START + SWEEP * animated
            drawLine(tint.copy(alpha = 0.25f), polar(c, r * 0.56f, a), polar(c, r * 0.99f, a), 12.dp.toPx(), StrokeCap.Round)
            drawLine(tint, polar(c, r * 0.58f, a), polar(c, r * 0.98f, a), 5.dp.toPx(), StrokeCap.Round)
        }

        // Text is drawn unmirrored, at mirrored positions.
        val labelPx = r * 0.11f
        var v = spec.min
        while (v <= spec.max + 1e-6) {
            val p = polar(c, r * 1.13f, START + SWEEP * spec.fraction(v))
            val label = formatValue(v / spec.tickDivisor, 0).replace(",", "")
            centredText(measurer, label, Offset(mx(p.x), p.y), labelPx, r * 0.4f, Sport.Label, FontWeight.Medium)
            v += if (spec.majorTick > 0) spec.majorTick else (spec.max - spec.min) / 7
        }
        val left = c.x - r * 0.5f
        val right = w - 8.dp.toPx()
        val cx = (left + right) / 2
        centredText(measurer, readout, Offset(mx(cx), c.y - r * 0.12f), h * 0.36f, (right - left) * 0.95f, tint)
        centredText(measurer, units, Offset(mx(cx), c.y + r * 0.3f), h * 0.07f, (right - left) * 0.9f, Sport.Label, FontWeight.Medium)
    }
}

/** A round mini-gauge, like the small ring on a twin-screen dash: a 270-degree track with the value inside. */
@Composable
fun SportRing(r: SportReadout, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(r.fraction ?: 0f, SportAnimation, label = "sportRing")
    val measurer = rememberTextMeasurer()
    val tint = levelTint(r.level, Sport.Yellow)
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = "${r.label} ${r.text} ${r.units}" }) {
        val stroke = size.minDimension * 0.07f
        val radius = size.minDimension / 2 - stroke
        val c = center
        val box = Size(radius * 2, radius * 2)
        val tl = Offset(c.x - radius, c.y - radius)
        drawCircle(Brush.radialGradient(listOf(Sport.Tunnel, Sport.Panel), c, radius), radius * 0.8f, c)
        drawArc(Sport.Track, 135f, 270f, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
        if (r.fraction != null && animated > 0.002f) {
            drawArc(
                Brush.sweepGradient(listOf(Sport.AmberDeep, Sport.Amber, Sport.AmberDeep), c),
                135f, 270f * animated, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        for (i in 0..10) {
            val a = 135f + 27f * i
            drawLine(Sport.Tick.copy(alpha = 0.8f), polar(c, radius * 0.72f, a), polar(c, radius * 0.8f, a), 1.5.dp.toPx())
        }
        centredText(measurer, r.text, Offset(c.x, c.y - radius * 0.08f), radius * 0.62f, radius * 1.2f, tint)
        centredText(measurer, r.units, Offset(c.x, c.y + radius * 0.38f), radius * 0.2f, radius, Sport.Label, FontWeight.Medium)
        centredText(measurer, r.label, Offset(c.x, c.y + radius * 0.86f), radius * 0.17f, radius * 1.2f, Sport.Label, FontWeight.Medium)
    }
}

/** A compact side readout: label and value on one line, a thin segmented bar under it. */
@Composable
fun SportReadoutRow(r: SportReadout, modifier: Modifier = Modifier) {
    val tint = levelTint(r.level, Color.White)
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.label, color = Sport.Label, fontFamily = Condensed, fontSize = 14.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(r.text, color = tint, fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text(r.units, color = Sport.Label, fontFamily = Condensed, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (r.note != null) {
            Text(r.note, color = Sport.TickFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                val n = 16
                val gap = 2.dp.toPx()
                val segW = (size.width - gap * (n - 1)) / n
                val lit = ((r.fraction ?: 0f) * n)
                for (i in 0 until n) {
                    val color = if (i < lit) levelTint(r.level, lerp(Sport.AmberDeep, Sport.Amber, i / n.toFloat())) else Sport.Track
                    drawRect(color, Offset(i * (segW + gap), 0f), Size(segW, size.height))
                }
            }
        }
    }
}

@Composable
internal fun rememberClock(): String {
    val context = LocalContext.current
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    val clock by produceState(SimpleDateFormat(pattern, Locale.getDefault()).format(Date()), pattern) {
        val format = SimpleDateFormat(pattern, Locale.getDefault())
        while (true) {
            value = format.format(Date())
            delay(5_000)
        }
    }
    return clock
}

/**
 * The whole Sport cluster. Landscape: ring and side readouts flank twin pods (speed left, RPM right).
 * Portrait: the pods on top, the ring and readouts under them.
 */
@Composable
fun SportDashboard(
    speedSpec: GaugeSpec,
    speed: Double?,
    speedText: String,
    speedUnits: String,
    rpmSpec: GaugeSpec,
    rpm: Double?,
    rpmText: String,
    rpmUnits: String,
    ring: SportReadout,
    side: List<SportReadout>,
    footer: String,
    modifier: Modifier = Modifier,
    edgeToEdge: Boolean = false,        // full screen: no rounded card edge, just the black panel
) {
    val clock = rememberClock()
    val shape = RoundedCornerShape(28.dp)
    val panel = if (edgeToEdge) modifier.background(Sport.Panel).padding(16.dp)
    else modifier.clip(shape).background(Sport.Panel).border(BorderStroke(1.dp, Sport.Edge), shape).padding(12.dp)
    BoxWithConstraints(panel) {
        @Composable
        fun Footer(m: Modifier = Modifier) = Row(m, verticalAlignment = Alignment.CenterVertically) {
            Text(footer, color = Sport.Label, fontFamily = Condensed, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(clock, color = Color.White, fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }

        @Composable
        fun Pods(m: Modifier) = Row(m) {
            SportPod(speedSpec, speed, speedText, speedUnits, mirrored = false, modifier = Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(2.dp).fillMaxHeight().padding(vertical = 24.dp).background(Sport.Edge))
            SportPod(rpmSpec, rpm, rpmText, rpmUnits, mirrored = true, modifier = Modifier.weight(1f).fillMaxHeight())
        }

        val split = (side.size + 1) / 2
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(0.55f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SportRing(ring, Modifier.fillMaxWidth(0.9f).weight(1f, fill = false))
                    side.take(split - 1).forEach { SportReadoutRow(it, Modifier.fillMaxWidth()) }
                }
                Pods(Modifier.weight(2.4f).fillMaxHeight())
                Column(Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                    side.drop(split - 1).forEach { SportReadoutRow(it, Modifier.fillMaxWidth()) }
                    Footer(Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Pods(Modifier.fillMaxWidth().weight(1.2f))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SportRing(ring, Modifier.weight(0.8f).align(Alignment.CenterVertically))
                    Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                        side.forEach { SportReadoutRow(it, Modifier.fillMaxWidth()) }
                    }
                }
                Footer(Modifier.fillMaxWidth())
            }
        }
    }
}
