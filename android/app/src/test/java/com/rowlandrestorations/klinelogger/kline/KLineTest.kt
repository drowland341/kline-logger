package com.rowlandrestorations.klinelogger.kline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of selftest.py: checks the protocol code against a simulated cable + ECU. */
class KLineTest {

    @Test
    fun framesMatchWhatKawasakiEcusExpect() {
        assertEquals("81 11 F1 81 04", hexs(buildFrame(0x11, 0xF1, byteArrayOf(0x81.toByte()), lengthInFmt = true)))
        assertEquals("80 11 F1 02 10 80 14", hexs(buildFrame(0x11, 0xF1, byteArrayOf(0x10, 0x80.toByte()))))
        assertEquals("80 11 F1 02 21 09 AE", hexs(buildFrame(0x11, 0xF1, byteArrayOf(0x21, 0x09))))
    }

    @Test
    fun parsesFramesAndHex() {
        val f = splitFrame(parseHex("80 F1 11 03 C1 EA 8F BF"))
        assertEquals(0xF1, f.target)
        assertEquals(0x11, f.source)
        assertEquals("C1 EA 8F", hexs(f.payload))
        assertEquals(8, frameLength(parseHex("80F11103")))
        assertEquals(null, frameLength(parseHex("80 F1 11")))
        assertArrayEquals(byteArrayOf(0x21, 0x09), parseHex("0x21,0x9"))
    }

    @Test
    fun handshakeWithBreakWakeUp() = handshake(InitMethod.BREAK, echo = true)

    @Test
    fun handshakeWith360BaudWakeUp() = handshake(InitMethod.BAUD_360, echo = true)

    @Test
    fun handshakeWithoutEcho() = handshake(InitMethod.BREAK, echo = false)

    @Test
    fun autoAddressFindsSecondEcu() {
        val link = KLine(FakeEcuTransport(ecuAddr = 0x28))
        val result = link.startCommunication(attempts = 1)
        assertEquals(0x28, result.ecu)
    }

    @Test
    fun negativeResponseIsReported() {
        val link = KLine(FakeEcuTransport(), KLineSettings(diagSession = null))
        link.startSession()
        val e = runCatching { link.readLocalId(0x09) }.exceptionOrNull()
        assertEquals(0x22, (e as NegativeResponseException).code)
    }

    private fun handshake(method: InitMethod, echo: Boolean) {
        val traffic = mutableListOf<String>()
        val link = KLine(FakeEcuTransport(echo = echo), KLineSettings(initMethod = method)) { dir, data ->
            traffic += "$dir ${hexs(data)}"
        }
        val info = link.startSession()
        assertEquals("EA 8F", hexs(info.start.keyBytes))
        assertEquals("80 F1 11 03 C1 EA 8F BF", hexs(info.start.response))
        assertEquals(echo, info.start.echo)
        assertEquals("accepted", info.diag)
        assertEquals("0B B8", hexs(link.readLocalId(0x09)))
        assertEquals(true, link.alive())
        link.stop()
        assertEquals("TX 81 11 F1 81 04", traffic.first())
    }
}

/** Byte-level stand-in for a USB K-line cable plus a Kawasaki ECU. Port of kline.py's FakeECUSerial. */
class FakeEcuTransport(
    private val ecuAddr: Int = 0x11,
    private val echo: Boolean = true,
    private val replyDelayNs: Long = 30_000_000,
    private val minGapNs: Long = 45_000_000,
) : Transport {
    override var baudRate = 10400
        private set

    private val rx = ArrayDeque<Byte>()
    private val pending = mutableListOf<Pair<Long, ByteArray>>()
    private var incoming = ByteArray(0)
    private var brk = false
    private var brkAt = 0L
    private var wokenAt: Long? = null
    private var awake = false
    private var session = false
    private var lastReply = 0L

    override fun setBaudRate(baud: Int) {
        baudRate = baud
    }

    override fun setBreak(on: Boolean) {
        val now = System.nanoTime()
        if (on && !brk) {
            brkAt = now
        } else if (!on && brk && (now - brkAt) in 15_000_000..40_000_000) {
            wokenAt = now
            incoming = ByteArray(0)
        }
        brk = on
    }

    override fun write(data: ByteArray) {
        if (baudRate == 360) {
            if (data.contentEquals(byteArrayOf(0))) {
                wokenAt = System.nanoTime() + 28_000_000
                incoming = ByteArray(0)
            }
            return
        }
        if (echo) rx.addAll(data.toList())
        incoming += data
        while (true) {
            val n = frameLength(incoming) ?: break
            if (incoming.size < n) break
            val frame = incoming.copyOf(n)
            incoming = incoming.copyOfRange(n, incoming.size)
            if (checksum(frame, 0, n - 1) != (frame.last().toInt() and 0xFF)) continue
            val f = splitFrame(frame)
            if (f.target != ecuAddr || f.payload.isEmpty()) continue
            if (lastReply != 0L && System.nanoTime() - lastReply < minGapNs) continue  // real ECUs ignore requests that come too fast
            val reply = respond(f.payload) ?: continue
            val ready = System.nanoTime() + replyDelayNs
            pending += ready to buildFrame(f.source!!, ecuAddr, reply.map { it.toByte() }.toByteArray())
            lastReply = ready
        }
    }

    private fun respond(p: ByteArray): List<Int>? {
        val sid = p[0].toInt() and 0xFF
        val arg = if (p.size > 1) p[1].toInt() and 0xFF else null
        if (sid == SID_START_COMM) {
            val woken = wokenAt ?: return null
            if (System.nanoTime() - woken > 300_000_000) return null
            awake = true
            return listOf(0xC1, 0xEA, 0x8F)
        }
        if (!awake) return null
        return when (sid) {
            SID_START_DIAG -> {
                session = true
                listOf(0x50, arg ?: 0x80)
            }
            SID_TESTER_PRESENT -> listOf(0x7E)
            SID_STOP_COMM -> {
                awake = false
                session = false
                listOf(0xC2)
            }
            SID_READ_LOCAL_ID -> when {
                arg == null -> listOf(0x7F, sid, 0x12)
                !session -> listOf(0x7F, sid, 0x22)
                arg == 0x09 -> listOf(0x61, arg, 0x0B, 0xB8)    // 3000 rpm
                else -> listOf(0x7F, sid, 0x12)
            }
            else -> listOf(0x7F, sid, 0x11)
        }
    }

    private fun moveReady() {
        val now = System.nanoTime()
        val ready = pending.filter { it.first <= now }
        pending.removeAll(ready)
        ready.forEach { rx.addAll(it.second.toList()) }
    }

    override fun read(max: Int, timeoutMs: Long): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            moveReady()
            if (rx.isNotEmpty() || System.nanoTime() >= deadline) break
            Thread.sleep(1)
        }
        return ByteArray(minOf(max, rx.size)) { rx.removeFirst() }
    }

    override fun purgeInput() {
        moveReady()
        rx.clear()
    }

    override fun close() {}
}
