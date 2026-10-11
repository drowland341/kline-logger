package com.rowlandrestorations.klinelogger.channels

import org.junit.Assert.assertEquals
import org.junit.Test

/** Formulas from pids.json must give the same numbers as kline.py's Python eval. */
class FormulaTest {
    private fun eval(formula: String, vararg bytes: Int) =
        Formula(formula).eval(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun defaultChannelFormulas() {
        assertEquals(3000.0, eval("raw", 0x0B, 0xB8), 1e-9)
        assertEquals(22.0, eval("raw - 40", 0x00, 62), 1e-9)
        assertEquals(101.308, eval("raw * 0.124", 0x03, 0x31), 1e-9)
        assertEquals(0.0, eval("max(0, min(100, (raw - 246) * 100 / (912 - 246)))", 0x00, 0x80), 1e-9)
        assertEquals(100.0, eval("max(0, min(100, (raw - 246) * 100 / (912 - 246)))", 0x03, 0xA0), 1e-9)
        assertEquals(13.25, eval("raw / 12.75", 0xA9), 0.01)
    }

    @Test
    fun pythonSemantics() {
        assertEquals(-1.0, eval("sraw", 0xFF, 0xFF), 1e-9)
        assertEquals(2.0, eval("n", 1, 2), 1e-9)
        assertEquals(0x0102.toDouble(), eval("A*256 + B", 1, 2), 1e-9)
        assertEquals(0x0102.toDouble(), eval("A << 8 | B", 1, 2), 1e-9)
        assertEquals(2.0, eval("d[1]", 1, 2), 1e-9)
        assertEquals(-4.0, eval("-2**2"), 1e-9)
        assertEquals(-4.0, eval("-7 // 2"), 1e-9)
        assertEquals(1.0, eval("-7 % 2"), 1e-9)
        assertEquals(-128.0, eval("signed(A, 8)", 0x80), 1e-9)
        assertEquals(3.0, eval("math.sqrt(9)"), 1e-9)
        assertEquals(0.0, eval("C", 1, 2), 1e-9)       // missing bytes read as 0, like kline.py
    }

    @Test(expected = FormulaException::class)
    fun rejectsUnknownNames() {
        Formula("__import__('os')")
    }
}
