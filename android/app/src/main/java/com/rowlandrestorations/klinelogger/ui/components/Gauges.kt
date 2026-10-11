package com.rowlandrestorations.klinelogger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.rowlandrestorations.klinelogger.ui.theme.Condensed
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeNumerals
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

enum class Level { NORMAL, WARNING, CRITICAL }

data class Alert(val level: Level, val label: String = "")

/** Warning/critical limits. Any can be null; "high" limits trip at or above, "low" at or below. */
@Immutable
data class Thresholds(
    val warnHigh: Double? = null,
    val critHigh: Double? = null,
    val warnLow: Double? = null,
    val critLow: Double? = null,
) {
    fun check(v: Double?): Alert = when {
        v == null -> Alert(Level.NORMAL)
        critHigh != null && v >= critHigh -> Alert(Level.CRITICAL, "HIGH")
        critLow != null && v <= critLow -> Alert(Level.CRITICAL, "LOW")
        warnHigh != null && v >= warnHigh -> Alert(Level.WARNING, "HIGH")
        warnLow != null && v <= warnLow -> Alert(Level.WARNING, "LOW")
        else -> Alert(Level.NORMAL)
    }
}

/** How one dashboard gauge shows a channel. */
@Immutable
data class GaugeSpec(
    val pid: Int,
    val label: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val step: Double = 0.0,                 // round the readout to this (0 = no rounding)
    val thresholds: Thresholds = Thresholds(),
    val majorTick: Double = 0.0,            // arc gauges only
    val tickDivisor: Double = 1.0,          // tick labels are value / divisor
) {
    fun fraction(v: Double): Float = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

@Composable
fun levelColor(level: Level, normal: Color = MaterialTheme.colorScheme.primary): Color = when (level) {
    Level.NORMAL -> normal
    Level.WARNING -> GaugeTheme.status.warning
    Level.CRITICAL -> GaugeTheme.status.critical
}

fun formatValue(v: Double, decimals: Int, step: Double = 0.0): String {
    val shown = if (step > 0) (v / step).roundToLong() * step else v
    return String.format(Locale.US, "%,.${decimals}f", shown)
}

/** Decimals that suit a value's size, for channels without a hand-tuned gauge. */
fun autoDecimals(v: Double): Int = when {
    Math.abs(v) >= 100 -> 0
    Math.abs(v) >= 10 -> 1
    else -> 2
}

private val ValueAnimation = tween<Float>(durationMillis = 260, easing = LinearOutSlowInEasing)
private val ColorAnimation = tween<Color>(durationMillis = 300)

/** Large 270-degree arc gauge with ticks, warning zones, and a big numeric readout. */
@Composable
fun ArcGauge(spec: GaugeSpec, value: Double?, units: String, modifier: Modifier = Modifier) {
    val status = GaugeTheme.status
    val scheme = MaterialTheme.colorScheme
    val alert = spec.thresholds.check(value)
    val animated by animateFloatAsState((value ?: spec.min).toFloat(), ValueAnimation, label = "arcValue")
    val color = levelColor(alert.level)     // snaps: a fade between cyan and amber passes through a muddy green
    val measurer = rememberTextMeasurer()
    val readout = if (value == null) "—" else formatValue(animated.toDouble(), spec.decimals, spec.step)

    BoxWithConstraints(
        modifier.semantics { contentDescription = "${spec.label} $readout $units" },
        contentAlignment = Alignment.Center,
    ) {
        val side = min(maxWidth, maxHeight)
        val tickStyle = GaugeLabel.copy(fontFamily = Condensed, fontSize = (side.value * 0.055f).coerceIn(11f, 22f).sp, color = scheme.onSurfaceVariant)

        Canvas(Modifier.size(side)) {
            val stroke = size.minDimension * 0.07f
            val inset = stroke / 2 + size.minDimension * 0.02f
            val arcSize = Size(size.minDimension - 2 * inset, size.minDimension - 2 * inset)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            val center = Offset(size.width / 2, size.height / 2)
            val radius = arcSize.width / 2
            fun angle(v: Double) = 135f + 270f * spec.fraction(v)

            drawArc(status.track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

            // Warning and redline zones, painted onto the track so they're visible before the value reaches them.
            val t = spec.thresholds
            t.warnHigh?.let { w ->
                val end = t.critHigh ?: spec.max
                drawArc(status.warning.copy(alpha = 0.45f), angle(w), angle(end) - angle(w), false, topLeft, arcSize, style = Stroke(stroke * 0.35f))
            }
            t.critHigh?.let { c ->
                drawArc(status.critical.copy(alpha = 0.7f), angle(c), angle(spec.max) - angle(c), false, topLeft, arcSize, style = Stroke(stroke * 0.35f))
            }

            val sweep = 270f * spec.fraction(animated.toDouble())
            if (value != null && sweep > 0.5f) {
                drawArc(color.copy(alpha = 0.18f), 135f, sweep, false, topLeft, arcSize, style = Stroke(stroke * 2f, cap = StrokeCap.Round))
                drawArc(color, 135f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }

            // Ticks and labels inside the arc.
            if (spec.majorTick > 0) {
                var v = spec.min
                val minor = spec.majorTick / 2
                var i = 0
                while (v <= spec.max + 1e-9) {
                    val major = i % 2 == 0
                    val rad = Math.toRadians(angle(v).toDouble())
                    val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
                    val outer = radius - stroke * 0.95f
                    val inner = outer - if (major) stroke * 0.9f else stroke * 0.45f
                    val hot = t.critHigh != null && v >= t.critHigh
                    drawLine(
                        if (hot) status.critical else if (major) scheme.onSurface.copy(alpha = 0.85f) else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        center + dir * inner, center + dir * outer, strokeWidth = if (major) stroke * 0.16f else stroke * 0.1f, cap = StrokeCap.Round,
                    )
                    if (major) {
                        val label = measurer.measure((v / spec.tickDivisor).roundToLong().toString(), tickStyle)
                        val at = center + dir * (inner - stroke * 0.9f) - Offset(label.size.width / 2f, label.size.height / 2f)
                        drawText(label, color = if (hot) status.critical else tickStyle.color, topLeft = at)
                    }
                    v += minor
                    i++
                }
            }

            if (value != null) {
                val rad = Math.toRadians((135f + sweep).toDouble())
                val tip = center + Offset(cos(rad).toFloat(), sin(rad).toFloat()) * radius
                drawCircle(color, stroke * 0.78f, tip)
                drawCircle(scheme.onSurface, stroke * 0.3f, tip)
            }
        }

        // Sits a little below centre, in the open bottom of the dial, so wide readouts clear the tick labels.
        Column(Modifier.offset(y = side * 0.07f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                readout,
                style = GaugeNumerals.copy(fontSize = (side.value * 0.17f).sp, lineHeight = (side.value * 0.19f).sp),
                color = if (alert.level == Level.NORMAL) scheme.onSurface else color,
                maxLines = 1,
            )
            Text(units.uppercase(), style = GaugeLabel.copy(fontSize = (side.value * 0.05f).coerceIn(12f, 20f).sp), color = scheme.onSurfaceVariant)
            Spacer(Modifier.height((side.value * 0.03f).dp))
            AlertBadge(alert, placeholder = true)
        }
    }
}

/** Compact gauge card: label, big value, units, and a bar with the warning zones marked. */
@Composable
fun GaugeCard(spec: GaugeSpec, value: Double?, units: String, modifier: Modifier = Modifier, caption: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val status = GaugeTheme.status
    val alert = spec.thresholds.check(value)
    val animated by animateFloatAsState((value ?: spec.min).toFloat(), ValueAnimation, label = "cardValue")
    val color = levelColor(alert.level)
    val container by animateColorAsState(
        when (alert.level) {
            Level.CRITICAL -> status.critical.copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerHigh)
            Level.WARNING -> status.warning.copy(alpha = 0.10f).compositeOver(scheme.surfaceContainerHigh)
            Level.NORMAL -> scheme.surfaceContainerHigh
        },
        ColorAnimation, label = "cardBg",
    )
    val readout = if (value == null) "—" else formatValue(animated.toDouble(), spec.decimals, spec.step)

    Surface(
        modifier.semantics(mergeDescendants = true) { contentDescription = "${spec.label} $readout $units ${alert.label}" },
        shape = RoundedCornerShape(20.dp),
        color = container,
        border = BorderStroke(if (alert.level == Level.NORMAL) 1.dp else 2.dp, if (alert.level == Level.NORMAL) scheme.outline else color),
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val valueSize = minOf(maxHeight.value * 0.40f, maxWidth.value * 0.30f).coerceIn(26f, 76f)
            val roomForCaption = maxHeight >= 100.dp    // short landscape cards have no room for it
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.label, style = GaugeLabel, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    AlertBadge(alert)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        readout,
                        style = GaugeNumerals.copy(fontSize = valueSize.sp, lineHeight = (valueSize * 1.05f).sp),
                        color = if (alert.level == Level.NORMAL) scheme.onSurface else color,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(units, style = MaterialTheme.typography.titleMedium.copy(fontSize = (valueSize * 0.32f).coerceIn(14f, 22f).sp), color = scheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
                Column {
                    LevelBar(spec, if (value == null) null else animated.toDouble(), color)
                    if (caption != null && roomForCaption) {
                        Spacer(Modifier.height(6.dp))
                        Text(caption, style = MaterialTheme.typography.labelSmall, color = status.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Thin horizontal bar: track, warning/critical zones, and the current value. */
@Composable
fun LevelBar(spec: GaugeSpec, value: Double?, color: Color, modifier: Modifier = Modifier) {
    val status = GaugeTheme.status
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(status.track, cornerRadius = r)
        val t = spec.thresholds
        fun x(v: Double) = size.width * spec.fraction(v)
        fun zone(from: Double, to: Double, c: Color) = drawRect(c, Offset(x(from), size.height * 0.6f), Size(x(to) - x(from), size.height * 0.4f))
        t.warnHigh?.let { zone(it, t.critHigh ?: spec.max, status.warning.copy(alpha = 0.5f)) }
        t.critHigh?.let { zone(it, spec.max, status.critical.copy(alpha = 0.7f)) }
        t.warnLow?.let { zone(t.critLow ?: spec.min, it, status.warning.copy(alpha = 0.5f)) }
        t.critLow?.let { zone(spec.min, it, status.critical.copy(alpha = 0.7f)) }
        if (value != null) {
            val w = x(value).coerceAtLeast(size.height)
            drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/** "HIGH" / "LOW" pill in the alert color. With [placeholder], reserves its space when there's no alert. */
@Composable
fun AlertBadge(alert: Alert, modifier: Modifier = Modifier, placeholder: Boolean = false) {
    if (alert.level == Level.NORMAL && !placeholder) return
    val bg = levelColor(alert.level, Color.Transparent)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            if (alert.level == Level.NORMAL) " " else alert.label,
            style = MaterialTheme.typography.labelMedium,
            color = GaugeTheme.status.onStatus,
        )
    }
}
