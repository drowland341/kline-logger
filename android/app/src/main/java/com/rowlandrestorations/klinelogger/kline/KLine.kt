package com.rowlandrestorations.klinelogger.kline

enum class InitMethod { BREAK, BAUD_360 }

/** The connection settings kline.py keeps in DEFAULT_SETTINGS, with the same defaults. */
data class KLineSettings(
    val ecuAddr: Int? = null,               // null = auto: tries each address in ECU_AUTO_ADDRESSES
    val testerAddr: Int = 0xF1,
    val initMethod: InitMethod = InitMethod.BREAK,
    val baud: Int = 10400,
    val diagSession: Int? = 0x80,           // null = skip StartDiagnosticSession
    val requestGapMs: Long = 55,            // ISO P3 min; forum reports Kawasaki ignores < ~49 ms
    val responseTimeoutMs: Long = 300,
    val fastSwitchCmd: ByteArray? = null,   // request that tells the ECU to change baud (null = stay)
    val fastBaud: Int = 62500,
    val dtr: Boolean = true,
    val rts: Boolean = false,
)

/** What StartCommunication found. [response] is the whole raw frame the ECU sent back. */
class StartCommResult(val ecu: Int, val keyBytes: ByteArray, val echo: Boolean?, val response: ByteArray)

class SessionInfo(val start: StartCommResult, val diag: String, val baud: Int)

enum class Direction { TX, RX, NOTE }    // NOTE: an ELM327 AT command and its answer, not a frame

/**
 * K-line transport and KWP2000 request/response handling. Ported from kline.py's KLine class.
 * Blocking: call only from a background thread. Throws KLineException for ECU problems and
 * IOException when the cable itself fails.
 */
class KLine(
    private val port: Transport,
    private val s: KLineSettings = KLineSettings(),
    private val traffic: (Direction, ByteArray) -> Unit = { _, _ -> },
) : EcuLink {
    private val ecuCandidates = s.ecuAddr?.let { listOf(it) } ?: ECU_AUTO_ADDRESSES
    var ecu = ecuCandidates[0]
        private set
    private val tester = s.testerAddr
    private val gapNs = s.requestGapMs * 1_000_000
    private val timeoutNs = s.responseTimeoutMs * 1_000_000

    var echo: Boolean? = null     // null = unknown, true/false once detected
        private set
    var keyBytes = ByteArray(0)
        private set
    private var lastActivity = 0L
    private var lastResponse = ByteArray(0)
    private val keepalivePayload = byteArrayOf(SID_TESTER_PRESENT.toByte(), 0x01)

    // --- session ----------------------------------------------------------

    /** ISO 14230 fast init: bus idle >= 300 ms, 25 ms low, 25 ms high. */
    fun wake() {
        port.setBaudRate(s.baud)
        port.setBreak(false)
        preciseSleep(350_000_000)
        port.purgeInput()
        if (s.initMethod == InitMethod.BAUD_360) {
            // A 0x00 byte at 360 baud = start bit + 8 zero bits = 25 ms low, then the stop bit goes high.
            port.setBaudRate(360)
            val t0 = System.nanoTime()
            port.write(byteArrayOf(0))
            preciseSleep(35_000_000 - (System.nanoTime() - t0))
            port.setBaudRate(s.baud)
            preciseSleep(52_000_000 - (System.nanoTime() - t0))
        } else {
            port.setBreak(true)
            preciseSleep(25_000_000)
            port.setBreak(false)
            preciseSleep(25_000_000)
        }
        port.purgeInput()
    }

    /** Wake-up plus StartCommunication, trying each candidate ECU address. */
    fun startCommunication(attempts: Int = 3): StartCommResult {
        var err: KLineException? = null
        repeat(attempts) {
            for (addr in ecuCandidates) {
                ecu = addr
                try {
                    wake()
                    echo = null
                    val frame = buildFrame(ecu, tester, byteArrayOf(SID_START_COMM.toByte()), lengthInFmt = true)
                    val resp = transact(frame, SID_START_COMM)
                    keyBytes = resp.copyOfRange(1, minOf(3, resp.size))
                    return StartCommResult(ecu, keyBytes, echo, lastResponse)
                } catch (e: KLineException) {
                    err = e
                }
            }
        }
        val tried = ecuCandidates.joinToString(", ") { "0x%02X".format(it) }
        throw NoResponseException("ECU did not answer the wake-up handshake at $tried (${err?.message})")
    }

    /** StartCommunication, then StartDiagnosticSession and the optional high-speed switch. */
    fun startSession(attempts: Int = 3): SessionInfo {
        val start = startCommunication(attempts)
        var diag = "skipped"
        s.diagSession?.let { session ->
            diag = try {
                request(byteArrayOf(SID_START_DIAG.toByte(), session.toByte()))
                "accepted"
            } catch (e: NegativeResponseException) {
                "refused (${nrcName(e.code)})"
            } catch (e: KLineException) {
                "no answer"
            }
        }
        s.fastSwitchCmd?.takeIf { it.isNotEmpty() }?.let { switch ->
            request(switch)
            port.setBaudRate(s.fastBaud)
            preciseSleep(50_000_000)
            port.purgeInput()
        }
        return SessionInfo(start, diag, port.baudRate)
    }

    override fun stop() {
        try {
            request(byteArrayOf(SID_STOP_COMM.toByte()), timeoutMs = 200)
        } catch (_: KLineException) {
        }
    }

    /** Any answer (even a refusal) proves the ECU is still listening. */
    override fun alive(): Boolean = try {
        request(keepalivePayload)
        true
    } catch (_: NegativeResponseException) {
        true
    } catch (_: KLineException) {
        false
    }

    // --- requests ---------------------------------------------------------

    override fun request(payload: ByteArray, timeoutMs: Long?): ByteArray {
        val frame = buildFrame(ecu, tester, payload)
        return transact(frame, payload[0].toInt() and 0xFF, timeoutMs?.let { it * 1_000_000 })
    }

    override fun readLocalId(pid: Int): ByteArray {
        val resp = request(byteArrayOf(SID_READ_LOCAL_ID.toByte(), pid.toByte()))
        if (resp.size < 2 || (resp[1].toInt() and 0xFF) != pid) {
            throw KLineException("Answer was for a different PID: ${hexs(resp)}")
        }
        return resp.copyOfRange(2, resp.size)
    }

    private fun readUntil(buf: RxBuffer, need: Int, deadline: Long) {
        while (buf.size < need) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            val chunk = port.read(need - buf.size, maxOf(1, left / 1_000_000))
            buf.append(chunk)
        }
    }

    private fun transact(frame: ByteArray, sid: Int, timeoutOverrideNs: Long? = null): ByteArray {
        val timeout = timeoutOverrideNs ?: timeoutNs
        val wait = lastActivity + gapNs - System.nanoTime()
        if (lastActivity != 0L && wait > 0) preciseSleep(wait)
        try {
            port.purgeInput()
            port.write(frame)
            traffic(Direction.TX, frame)
            val buf = RxBuffer()

            // 1. Echo. Most USB K-line cables hear their own transmission on the shared wire.
            if (echo != false) {
                val byteTimeNs = 10_000_000_000L / port.baudRate
                val deadline = System.nanoTime() + frame.size * byteTimeNs + 100_000_000
                while (true) {
                    buf.dropJunk()     // junk some adapters report after the wake pulse
                    readUntil(buf, frame.size, deadline)
                    if (!buf.startsWithJunk() || System.nanoTime() >= deadline) break
                }
                when {
                    buf.startsWith(frame) -> {
                        buf.drop(frame.size)
                        echo = true
                    }
                    buf.size == 0 -> {
                        if (echo == true) {
                            throw NoResponseException("Cable did not echo the request (adapter unpowered or K-line not connected?)")
                        }
                        throw NoResponseException("No reply to ${hexs(frame)}")
                    }
                    echo == null -> echo = false     // bytes came back but they are the ECU, not an echo
                    else -> throw KLineException("Echo did not match what was sent (bus collision or noise): ${buf.hex()}")
                }
            }

            // 2. ECU response. Kawasaki uses 0x80-format headers, so leading 0x00/0xFF is line noise.
            var deadline = System.nanoTime() + timeout
            while (true) {
                while (true) {
                    buf.dropJunk()
                    if (buf.size > 0 || System.nanoTime() >= deadline) break
                    readUntil(buf, 1, deadline)
                }
                if (buf.size == 0) throw NoResponseException("No reply to ${hexs(frame)}")
                var total = buf.frameLength()
                while (total == null && System.nanoTime() < deadline) {
                    readUntil(buf, buf.size + 1, deadline)
                    total = buf.frameLength()
                }
                if (total == null) throw NoResponseException("Reply cut off: ${buf.hex()}")
                readUntil(buf, total, deadline + 50_000_000)
                if (buf.size < total) throw NoResponseException("Reply cut off: ${buf.hex()}")
                val rframe = buf.take(total)
                traffic(Direction.RX, rframe)
                if (checksum(rframe, 0, rframe.size - 1) != (rframe.last().toInt() and 0xFF)) {
                    throw BadChecksumException("Bad checksum in ${hexs(rframe)}")
                }
                val f = splitFrame(rframe)
                val payload = f.payload
                if ((f.target != null && f.target != tester) || payload.isEmpty()) continue
                val first = payload[0].toInt() and 0xFF
                if (first == NEGATIVE_RESPONSE && payload.size >= 3) {
                    val code = payload[2].toInt() and 0xFF
                    if (code == 0x78) {                     // response pending: ECU needs more time
                        deadline = System.nanoTime() + 5_000_000_000
                        continue
                    }
                    throw NegativeResponseException(payload[1].toInt() and 0xFF, code)
                }
                if (first == (sid or 0x40)) {
                    lastResponse = rframe
                    return payload
                }
                // A positive answer to some other service: ignore it and keep listening.
            }
        } finally {
            lastActivity = System.nanoTime()
        }
    }

    /** Received bytes not yet consumed by the frame parser. */
    private class RxBuffer {
        private var data = ByteArray(64)
        var size = 0
            private set

        fun append(chunk: ByteArray) {
            if (size + chunk.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
            chunk.copyInto(data, size)
            size += chunk.size
        }

        fun drop(n: Int) {
            data.copyInto(data, 0, n, size)
            size -= n
        }

        fun take(n: Int): ByteArray = data.copyOf(n).also { drop(n) }

        fun startsWithJunk(): Boolean = size > 0 && (data[0] == 0x00.toByte() || data[0] == 0xFF.toByte())

        fun dropJunk() {
            while (startsWithJunk()) drop(1)
        }

        fun startsWith(frame: ByteArray): Boolean =
            size >= frame.size && frame.indices.all { data[it] == frame[it] }

        fun frameLength(): Int? = frameLength(data, size)

        fun hex(): String = hexs(data.copyOf(size))
    }
}

/** Thread.sleep can overshoot by a millisecond or two, so finish timing-critical waits with a spin. */
fun preciseSleep(nanos: Long) {
    if (nanos <= 0) return
    val end = System.nanoTime() + nanos
    if (nanos > 3_000_000) Thread.sleep((nanos - 2_000_000) / 1_000_000)
    while (System.nanoTime() < end) {
        // spin
    }
}
