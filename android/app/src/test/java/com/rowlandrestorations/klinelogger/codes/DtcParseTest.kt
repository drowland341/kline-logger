package com.rowlandrestorations.klinelogger.codes

import com.rowlandrestorations.klinelogger.kline.parseHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcParseTest {
    private val table = mapOf(
        14 to ServiceCode(14, "AqUt", "Water temperature sensor", "", emptyList(), listOf(11, 32), ""),
        72 to ServiceCode(72, "OILP", "Engine oil pressure low", "", emptyList(), listOf(3), ""),
    )

    @Test
    fun knownCodes() {
        val r = parseDtcReply(parseHex("58 02 00 14 E0 00 72 E0"), table)
        assertEquals(listOf(14, 72), r.codes.map { it.code })
        assertEquals("AqUt", r.codes[0].info?.meter)
        assertEquals(0xE0, r.codes[1].status)
        assertNull(r.warning)
    }

    @Test
    fun noCodes() {
        val r = parseDtcReply(parseHex("58 00"), table)
        assertTrue(r.codes.isEmpty())
        assertNull(r.warning)
    }

    @Test
    fun unknownCodesDoNotCrash() {
        val r = parseDtcReply(parseHex("58 02 00 99 00 00 1E 00"), table)
        assertEquals(99, r.codes[0].code)
        assertNull(r.codes[0].info)
        assertNull(r.codes[1].code)          // 0x1E isn't a decimal code number
        assertNull(r.codes[1].info)
    }

    @Test
    fun countMismatchAndLeftoverBytesWarn() {
        assertNotNull(parseDtcReply(parseHex("58 03 00 14 E0"), table).warning)
        assertNotNull(parseDtcReply(parseHex("58 01 00 14 E0 00"), table).warning)
    }
}
