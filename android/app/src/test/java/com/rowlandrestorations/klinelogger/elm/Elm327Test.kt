package com.rowlandrestorations.klinelogger.elm

import com.rowlandrestorations.klinelogger.kline.KLineException
import com.rowlandrestorations.klinelogger.kline.NegativeResponseException
import com.rowlandrestorations.klinelogger.kline.NoResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A pretend ELM327 with a Kawasaki ECU behind it at [ecu], answering the way the real adapter prints. */
private class FakeElm(private val ecu: Int = 0x11, private val echo: Boolean = true) : QueuedByteLink() {
    override val description = "fake"
    var header = 0x33
    var protocol = "0"
    val sent = mutableListOf<String>()

    override fun write(data: ByteArray) {
        val cmd = String(data, Charsets.US_ASCII).trim()
        sent += cmd
        val reply = when {
            cmd == "ATZ" -> "\r\rELM327 v1.5"
            cmd.startsWith("ATSH") -> {
                header = cmd.substring(6, 8).toInt(16)
                "OK"
            }
            cmd.startsWith("ATSP") -> {
                protocol = cmd.substring(4)
                "OK"
            }
            cmd == "ATRV" -> "12.6V"
            cmd == "ATDP" -> "ISO 14230-4 (KWP FAST)"
            cmd == "ATKW" -> "1:EA 2:8F"
            cmd == "ATXX" -> "?"
            cmd.startsWith("AT") -> "OK"
            protocol != "5" || header != ecu -> "BUS INIT: ...ERROR"
            cmd == "1080" -> "BUS INIT: ...OK\r50 80"
            cmd == "2109" -> "61 09 01 32"
            cmd == "2105" -> "7F 21 78\r61 05 03 44"           // response pending, then the answer
            cmd == "2122" -> "7F 21 31"
            cmd == "3E01" -> "7E 01"
            cmd == "82" -> "C2"
            else -> "NO DATA"
        }
        val text = (if (echo) "$cmd\r" else "") + reply + "\r\r>"
        // Arrive in two pieces, the way a radio link chops it up.
        val bytes = text.toByteArray(Charsets.US_ASCII)
        deliver(bytes.copyOfRange(0, bytes.size / 2))
        deliver(bytes.copyOfRange(bytes.size / 2, bytes.size))
    }

    override fun close() {}
}

class Elm327Test {
    @Test
    fun startsSessionAndReadsPids() {
        val io = FakeElm(ecu = 0x11)
        val elm = Elm327(io, requestGapMs = 0)
        val s = elm.startSession()
        assertEquals(0x11, s.ecu)
        assertEquals("accepted", s.diag)
        assertEquals("ELM327 v1.5", s.version)
        assertEquals("12.6V", s.voltage)
        assertEquals("ISO 14230-4 (KWP FAST)", s.protocol)
        assertTrue("header must target the ECU", io.sent.contains("ATSH8111F1"))

        assertArrayEquals(byteArrayOf(0x01, 0x32), elm.readLocalId(0x09))
        assertArrayEquals(byteArrayOf(0x03, 0x44), elm.readLocalId(0x05))
        assertTrue(elm.alive())
    }

    @Test
    fun findsTheSecondEcuAddress() {
        val elm = Elm327(FakeElm(ecu = 0x28), requestGapMs = 0)
        assertEquals(0x28, elm.startSession().ecu)
    }

    @Test
    fun refusalsAndSilenceAreReported() {
        val elm = Elm327(FakeElm(), requestGapMs = 0)
        elm.startSession()
        try {
            elm.readLocalId(0x22)
            fail("expected a refusal")
        } catch (e: NegativeResponseException) {
            assertEquals(0x31, e.code)
        }
        try {
            elm.readLocalId(0x40)
            fail("expected no answer")
        } catch (e: NoResponseException) {
            // NO DATA: the scanner's "no answer"
        }
    }

    @Test
    fun noEcuMeansNoSession() {
        try {
            Elm327(FakeElm(ecu = 0x55), requestGapMs = 0).startSession()
            fail("expected no ECU")
        } catch (e: NoResponseException) {
            assertTrue(e.message!!.contains("0x11"))
        }
    }

    @Test
    fun parsesElmReplies() {
        assertArrayEquals(byteArrayOf(0x61, 0x09, 0x01), parseElmReply("610901").single())
        assertArrayEquals(byteArrayOf(0x50, 0x80.toByte()), parseElmReply("BUS INIT: ...OK 50 80").single())
        assertEquals(2, parseElmReply("SEARCHING...\r7F 21 78\r61 09 00 01").size)
        for (bad in listOf("NO DATA", "UNABLE TO CONNECT", "BUS INIT: ...ERROR", "")) {
            try {
                parseElmReply(bad)
                fail("'$bad' should mean no answer")
            } catch (e: NoResponseException) {
            }
        }
        for (bad in listOf("?", "BUFFER FULL", "<DATA ERROR", "STOPPED")) {
            try {
                parseElmReply(bad)
                fail("'$bad' should fail")
            } catch (e: NoResponseException) {
                fail("'$bad' is an adapter error, not silence")
            } catch (e: KLineException) {
            }
        }
    }
}
