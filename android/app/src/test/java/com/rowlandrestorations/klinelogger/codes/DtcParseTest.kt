package com.rowlandrestorations.klinelogger.codes

import com.rowlandrestorations.klinelogger.kline.parseHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcParseTest {
    private val table = mapOf(
        23 to ServiceCode(23, "CAA1", "Camshaft position sensor", "", emptyList(), listOf(16), "", dtc = "P0340", dtcVerified = true),
        72 to ServiceCode(72, "OILP", "Engine oil pressure low", "", emptyList(), listOf(3), "", dtc = "P0524"),
    )

    @Test
    fun camSensorReplyFromUltraLx() {
        // What a 2007 Ultra LX sent with its cam sensor unplugged.
        val r = parseDtcReply(parseHex("53 01 03 40"), table)
        assertEquals(listOf("P0340"), r.codes.map { it.dtc })
        assertEquals(23, r.codes[0].code)
        assertEquals("CAA1", r.codes[0].info?.meter)
        assertNull(r.codes[0].status)
        assertNull(r.warning)
    }

    @Test
    fun knownCodes() {
        val r = parseDtcReply(parseHex("53 02 03 40 05 24"), table)
        assertEquals(listOf(23, 72), r.codes.map { it.code })
        assertNull(r.warning)
    }

    @Test
    fun noCodes() {
        val r = parseDtcReply(parseHex("53 00"), table)
        assertTrue(r.codes.isEmpty())
        assertNull(r.warning)
    }

    @Test
    fun unknownCodesDoNotCrash() {
        val r = parseDtcReply(parseHex("53 01 01 23"), table)
        assertEquals("P0123", r.codes[0].dtc)
        assertNull(r.codes[0].code)
        assertNull(r.codes[0].info)
    }

    @Test
    fun obdLetters() {
        assertEquals("P0340", obdCode(0x03, 0x40))
        assertEquals("C1234", obdCode(0x52, 0x34))
        assertEquals("B0001", obdCode(0x80, 0x01))
        assertEquals("U3FFF", obdCode(0xFF, 0xFF))
    }

    @Test
    fun countMismatchAndLeftoverBytesWarn() {
        assertNotNull(parseDtcReply(parseHex("53 03 03 40"), table).warning)
        assertNotNull(parseDtcReply(parseHex("53 01 03 40 00"), table).warning)
    }
}
