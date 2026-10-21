package com.rowlandrestorations.klinelogger.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import com.rowlandrestorations.klinelogger.ui.theme.Condensed

/** Everything a cluster-style dashboard (Sport, LCD, Digital) shows, already converted and formatted. */
data class ClusterData(
    val speedSpec: GaugeSpec,
    val speed: Double?,
    val speedText: String,
    val speedUnits: String,
    val rpmSpec: GaugeSpec,
    val rpm: Double?,
    val rpmText: String,
    val rpmUnits: String,
    val throttle: SportReadout,
    val coolant: SportReadout,
    val oil: SportReadout,
    val battery: SportReadout,
    val map: SportReadout,
    val footer: String,             // e.g. "IAT 91°F"
)

/** The largest [aspect] (width / height) box centred in a canvas of [w] x [h]. */
internal fun fitBox(w: Float, h: Float, aspect: Float): Rect =
    if (w / h > aspect) {
        val bw = h * aspect
        Rect((w - bw) / 2, 0f, (w + bw) / 2, h)
    } else {
        val bh = w / aspect
        Rect(0f, (h - bh) / 2, w, (h + bh) / 2)
    }

/** Draws [text] with its left (anchor 0), centre (0.5) or right (1) edge at [x], vertically centred on [centerY]. */
internal fun DrawScope.alignedText(
    measurer: TextMeasurer, text: String, x: Float, centerY: Float, sizePx: Float, color: Color,
    anchor: Float = 0f, weight: FontWeight = FontWeight.Bold, maxWidth: Float = Float.MAX_VALUE,
) {
    if (text.isEmpty()) return
    fun style(px: Float) = TextStyle(fontFamily = Condensed, fontWeight = weight, fontSize = px.toSp(), color = color)
    var layout = measurer.measure(text, style(sizePx))
    if (layout.size.width > maxWidth && layout.size.width > 0) {
        layout = measurer.measure(text, style(sizePx * maxWidth / layout.size.width))
    }
    drawText(layout, topLeft = Offset(x - anchor * layout.size.width, centerY - layout.size.height / 2f))
}

/** A polyline through [points] with each inner corner rounded off by up to [radius]. */
internal fun roundedPath(points: List<Offset>, radius: Float) = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size - 1) {
        val p = points[i]
        val din = p - points[i - 1]
        val dout = points[i + 1] - p
        val p1 = p - din / din.getDistance() * minOf(radius, din.getDistance() / 2)
        val p2 = p + dout / dout.getDistance() * minOf(radius, dout.getDistance() / 2)
        lineTo(p1.x, p1.y)
        quadraticBezierTo(p.x, p.y, p2.x, p2.y)
    }
    lineTo(points.last().x, points.last().y)
}
