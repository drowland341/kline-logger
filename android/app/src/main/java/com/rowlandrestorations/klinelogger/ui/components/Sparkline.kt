package com.rowlandrestorations.klinelogger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Recent history as a line with a soft fill. Scaled to the data's own range so small changes
 * are visible; [capacity] fixes the x spacing, so new points enter at the right like a strip chart.
 */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier, capacity: Int = values.size) {
    Canvas(modifier) {
        val baseline = size.height - 1.dp.toPx()
        if (values.size < 2) {
            drawLine(color.copy(alpha = 0.3f), Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            return@Canvas
        }
        var lo = values.min()
        var hi = values.max()
        if (hi - lo < 1e-3f) {
            lo -= 1f
            hi += 1f
        }
        val pad = 2.dp.toPx()
        val n = maxOf(capacity, values.size) - 1
        val start = n - (values.size - 1)
        fun pt(i: Int) = Offset(
            size.width * (start + i) / n,
            pad + (size.height - 2 * pad) * (1 - (values[i] - lo) / (hi - lo)),
        )

        val line = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(pt(values.lastIndex).x, size.height)
            lineTo(pt(0).x, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 2.5.dp.toPx(), pt(values.lastIndex))
    }
}
