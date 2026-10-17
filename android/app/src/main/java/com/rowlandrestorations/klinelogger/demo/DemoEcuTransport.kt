package com.rowlandrestorations.klinelogger.demo

import com.rowlandrestorations.klinelogger.codes.DtcProtocol
import com.rowlandrestorations.klinelogger.kline.SID_READ_LOCAL_ID
import com.rowlandrestorations.klinelogger.kline.SID_START_COMM
import com.rowlandrestorations.klinelogger.kline.SID_START_DIAG
import com.rowlandrestorations.klinelogger.kline.SID_STOP_COMM
import com.rowlandrestorations.klinelogger.kline.SID_TESTER_PRESENT
import com.rowlandrestorations.klinelogger.kline.Transport
import com.rowlandrestorations.klinelogger.kline.buildFrame
import com.rowlandrestorations.klinelogger.kline.checksum
import com.rowlandrestorations.klinelogger.kline.frameLength
import com.rowlandrestorations.klinelogger.kline.splitFrame
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Byte-level stand-in for a USB K-line cable plus a Kawasaki ECU, for Demo mode.
 * KLine talks to it exactly as it talks to the real cable (wake-up, echo, framing, checksums,
 * request gap), so demo data goes through the same pipeline as live data. Ported from
 * kline.py's FakeECUSerial. Single-threaded, like every Transport.
 */
class DemoEcuTransport(
    private val ecuAddr: Int = 0x11,
    private val replyDelayNs: Long = 25_000_000,
    private val minGapNs: Long = 45_000_000,
) : Transport {
    override var baudRate = 10400
        private set

    private val sim = EngineSim()
    private val rx = ArrayDeque<Byte>()
    private val pending = mutableListOf<Pair<Long, ByteArray>>()
    private var incoming = ByteArray(0)
    private var brk = false
    private var brkAt = 0L
    private var wokenAt: Long? = null
    private var awake = false
    private var session = false
    private var lastReply = 0L

    // Stored fault codes (water temp sensor, oil pressure), as the hex-digit DTC numbers Kawasaki uses.
    private val storedCodes = mutableListOf(0x0014, 0x0072)

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
        rx.addAll(data.toList())     // the cable hears its own transmission
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
                else -> sim.read(arg)?.let { listOf(0x61, arg) + it } ?: listOf(0x7F, sid, 0x12)
            }
            DtcProtocol.readSid -> listOf(0x58, storedCodes.size) + storedCodes.flatMap { listOf(it shr 8, it and 0xFF, 0xE0) }
            DtcProtocol.clearSid -> {
                storedCodes.clear()
                listOf(0x54) + p.drop(1).map { it.toInt() and 0xFF }
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

/**
 * Synthetic jet ski engine. Every 30 s: idle, a throttle sweep to ~7500 rpm, a short hold,
 * then back off to idle. Coolant warms from garage temperature toward operating temperature and
 * runs hot during the pulls, so the dashboard's warning colors get exercised.
 */
internal class EngineSim {
    private val t0 = System.nanoTime()
    private val rnd = java.util.Random()
    private var lastT = 0.0
    private var coolantC = 22.0
    private var counter = 0

    // Like kline.py's EngineSim: every PID up to 0x62 answers, unknown ones with fixed bytes, so the scanner has plenty to find.
    private val static: Map<Int, List<Int>> = java.util.Random(1234).let { r ->
        (0x00..0x62).associateWith { List(listOf(1, 2, 2, 4)[r.nextInt(4)]) { r.nextInt(256) } }
    }

    private fun noise(amount: Double) = rnd.nextGaussian() * amount

    internal class State(val rpm: Double, val tps: Double)

    internal fun state(el: Double): State {
        val idle = 1250 + 25 * sin(el * 3)
        val t = el % 30
        return when {
            t < 8 -> State(idle, 0.0)
            t < 16 -> {
                val f = (t - 8) / 8
                State(1250 + 6250 * f.pow(0.8), 15 + 85 * minOf(1.0, f * 1.6))
            }
            t < 19 -> State(7500 + 40 * sin(el * 20), 100.0)
            t < 23 -> State(7500 - 6250 * (t - 19) / 4, 0.0)
            else -> State(idle, 0.0)
        }
    }

    fun read(pid: Int): List<Int>? {
        val el = (System.nanoTime() - t0) / 1e9
        val s = state(el)
        // Coolant chases a target that rises with load: ~84 C cruising, high 90s after a long pull.
        val target = 84 + 16 * (s.rpm - 1250) / 6250
        val dt = (el - lastT).coerceIn(0.0, 1.0)
        lastT = el
        coolantC += (target - coolantC) * (1 - exp(-dt / 25)) * (if (coolantC < 70) 0.6 else 1.0)
        return when (pid) {
            0x09 -> u16((s.rpm + noise(8.0)).coerceIn(0.0, MAX_RPM) / 4)          // 4 rpm per count, like the real ECU
            0x04 -> u16(246 + (s.tps + noise(0.3)).coerceIn(0.0, 100.0) / 100 * 666)
            0x05 -> u16((40 + 57 * s.tps / 100 + noise(0.4)) / 0.124)            // ~40 kPa idle, ~97 kPa WOT
            0x08 -> u16(101.3 / 0.124)                                            // frozen duplicate, like the real ECU
            0x06 -> u16(coolantC + 40)
            0x07 -> u16(31 + 2 * sin(el / 20) + noise(0.3) + 40)
            0x98 -> u16(coolantC * 1.5 + noise(0.3) + 40)                          // oil runs hotter than the coolant
            0x0A -> {
                val volts = (if (s.rpm > 2000) 14.2 else 12.9) + noise(0.05)       // charging above idle
                u16(volts * 51)
            }
            0x1F -> {
                counter = (counter + 1) and 0xFF
                listOf(counter)
            }
            else -> static[pid]
        }
    }

    companion object {
        // No jet ski engine gets near this; keeps the demo believable whatever the sweep does.
        const val MAX_RPM = 10_000.0
    }

    private fun u16(v: Double): List<Int> {
        val n = v.roundToInt().coerceIn(0, 0xFFFF)
        return listOf(n shr 8, n and 0xFF)
    }
}
