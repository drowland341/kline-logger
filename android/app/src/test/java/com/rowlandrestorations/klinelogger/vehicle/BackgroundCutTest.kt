package com.rowlandrestorations.klinelogger.vehicle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundCutTest {
    private val white = 0xFFFFFFFF.toInt()
    private val blue = 0xFF1F4FD0.toInt()

    /** A white picture with a blue rectangle in the middle (a stand-in for a studio shot of a ski). */
    private fun picture(w: Int, h: Int, background: (Int, Int) -> Int = { _, _ -> white }): IntArray =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 10 until 30 && y in 5 until 15) blue else background(x, y)
        }

    private fun alpha(c: Int) = c ushr 24

    @Test
    fun whiteBackgroundBecomesTransparentAndTheSubjectStays() {
        val px = picture(40, 20)
        val out = BackgroundCut.cut(px, 40, 20)
        assertEquals(0, alpha(out[0]))
        assertEquals(0, alpha(out[39 + 19 * 40]))
        assertEquals(255, alpha(out[20 + 10 * 40]))
    }

    @Test
    fun aGentleGradientIsRemovedToo() {
        // White at the top fading to light grey at the bottom, like a studio sweep.
        val px = picture(40, 20) { _, y -> 0xFF000000.toInt() or ((255 - y * 2) * 0x010101) }
        val out = BackgroundCut.cut(px, 40, 20)
        assertEquals(0, alpha(out[5 + 18 * 40]))
        assertEquals(255, alpha(out[20 + 10 * 40]))
    }

    @Test
    fun alreadyTransparentPicturesAreLeftAlone() {
        val px = picture(40, 20) { _, _ -> 0 }
        assertArrayEquals(px, BackgroundCut.cut(px, 40, 20))
    }

    @Test
    fun boundsTrimToTheSubjectWithAMargin() {
        val out = BackgroundCut.cut(picture(40, 20), 40, 20)
        val b = BackgroundCut.visibleBounds(out, 40, 20)
        assertNotNull(b)
        b!!
        assertTrue(b[0] in 6..10 && b[1] in 1..5 && b[2] in 30..34 && b[3] in 15..19)
    }
}
