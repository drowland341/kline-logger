package com.rowlandrestorations.klinelogger.vehicle

/**
 * Cuts a plain studio background out of a picture so the ski sits cleanly on a dark dashboard.
 *
 * Pixels are ARGB ints, row by row. Starting from every edge pixel that looks like the background (close to the
 * average corner colour), a flood fill spreads to neighbours whose colour changes only gently. It never crosses a
 * sharp edge (Sobel), so a white panel against a white background survives even when the colours match, while
 * gradual things like studio sweeps and soft floor shadows go. Inside grey shadows it takes bigger steps. Pixels next to the cut are faded
 * rather than left hard. Pictures that are already transparent at the corners are left alone.
 */
object BackgroundCut {
    private const val SEED_TOLERANCE = 240       // sum of |dR|+|dG|+|dB| from the corner colour
    private const val STEP_TOLERANCE = 20        // between neighbouring background pixels
    private const val SHADOW_STEP = 28           // looser inside grey floor shadows, which are darker than the background
    private const val EDGE_TOLERANCE = 20        // Sobel strength that counts as an outline the fill must not cross

    private fun a(c: Int) = c ushr 24
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun dist(x: Int, y: Int) = Math.abs(r(x) - r(y)) + Math.abs(g(x) - g(y)) + Math.abs(b(x) - b(y))
    private fun lum(c: Int) = (r(c) * 3 + g(c) * 6 + b(c)) / 10
    private fun sat(c: Int) = maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c))

    /** Returns a new pixel array with the background made transparent. */
    fun cut(
        px: IntArray, w: Int, h: Int,
        seedTolerance: Int = SEED_TOLERANCE, stepTolerance: Int = STEP_TOLERANCE, edgeTolerance: Int = EDGE_TOLERANCE,
    ): IntArray {
        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        if (corners.any { a(it) < 16 }) return px.copyOf()
        val seed = (0xFF shl 24) or
            (corners.sumOf { r(it) } / 4 shl 16) or (corners.sumOf { g(it) } / 4 shl 8) or (corners.sumOf { b(it) } / 4)

        val edge = edges(px, w, h)
        val removed = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun seedAt(i: Int) {
            if (!removed[i] && dist(px[i], seed) < seedTolerance && edge[i] < edgeTolerance) {
                removed[i] = true
                stack[sp++] = i
            }
        }
        for (x in 0 until w) {
            seedAt(x)
            seedAt((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seedAt(y * w)
            seedAt(y * w + w - 1)
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            fun visit(j: Int) {
                if (removed[j]) return
                // A colourless pixel darker than the background is floor shadow: let the fill move through it faster.
                val shadow = lum(seed) - lum(px[j]) > 12 && sat(px[j]) < 40
                val step = if (shadow) maxOf(stepTolerance, SHADOW_STEP) else stepTolerance
                if (dist(px[j], px[i]) < step && dist(px[j], seed) < seedTolerance && edge[j] < edgeTolerance) {
                    removed[j] = true
                    stack[sp++] = j
                }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }

        val out = px.copyOf()
        for (i in out.indices) {
            if (removed[i]) {
                out[i] = 0
                continue
            }
            // Soften the outline: a kept pixel touching the cut fades by how much it still looks like background.
            val x = i % w
            val y = i / w
            val touching = (x > 0 && removed[i - 1]) || (x < w - 1 && removed[i + 1]) ||
                (y > 0 && removed[i - w]) || (y < h - 1 && removed[i + w])
            if (touching) {
                val alpha = (dist(px[i], seed) * 255 / seedTolerance).coerceIn(90, 255)
                out[i] = (minOf(alpha, a(px[i])) shl 24) or (px[i] and 0xFFFFFF)
            }
        }
        return out
    }

    /** Sobel edge strength of the brightness at each pixel: high on outlines, low on gradients and soft shadows. */
    private fun edges(px: IntArray, w: Int, h: Int): IntArray {
        val l = IntArray(px.size) { lum(px[it]) }
        val out = IntArray(px.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (l[i - w + 1] + 2 * l[i + 1] + l[i + w + 1]) - (l[i - w - 1] + 2 * l[i - 1] + l[i + w - 1])
            val gy = (l[i + w - 1] + 2 * l[i + w] + l[i + w + 1]) - (l[i - w - 1] + 2 * l[i - w] + l[i - w + 1])
            out[i] = Math.abs(gx) + Math.abs(gy)
        }
        return out
    }

    /** Bounds (left, top, right, bottom; right/bottom exclusive) of the visible pixels, with a little margin. */
    fun visibleBounds(px: IntArray, w: Int, h: Int): IntArray? {
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (a(px[y * w + x]) > 24) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        val m = maxOf(2, (maxOf(right - left, bottom - top) * 0.02f).toInt())
        return intArrayOf(maxOf(0, left - m), maxOf(0, top - m), minOf(w, right + m + 1), minOf(h, bottom + m + 1))
    }
}
