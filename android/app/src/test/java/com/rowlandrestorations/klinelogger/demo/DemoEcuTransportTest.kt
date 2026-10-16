package com.rowlandrestorations.klinelogger.demo

import com.rowlandrestorations.klinelogger.channels.Formula
import com.rowlandrestorations.klinelogger.kline.KLine
import com.rowlandrestorations.klinelogger.kline.KLineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demo mode must work through the unchanged KLine protocol code, exactly like a real ECU. */
class DemoEcuTransportTest {
    @Test
    fun demoEcuAnswersTheRealProtocol() {
        val link = KLine(DemoEcuTransport(), KLineSettings())
        val info = link.startSession(attempts = 1)
        assertEquals(0x11, info.start.ecu)
        assertEquals("accepted", info.diag)

        val rpm = Formula("raw * 4").eval(link.readLocalId(0x09))
        assertTrue("idle rpm was $rpm", rpm in 1100.0..1400.0)
        val coolant = Formula("raw - 40").eval(link.readLocalId(0x06))
        assertTrue("coolant was $coolant", coolant in 15.0..30.0)
        val volts = Formula("raw / 51").eval(link.readLocalId(0x0A))
        assertTrue("battery was $volts", volts in 12.0..15.0)
        assertTrue(link.alive())
    }
}
