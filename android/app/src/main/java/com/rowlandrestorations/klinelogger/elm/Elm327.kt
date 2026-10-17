package com.rowlandrestorations.klinelogger.elm

import com.rowlandrestorations.klinelogger.kline.Direction
import com.rowlandrestorations.klinelogger.kline.ECU_AUTO_ADDRESSES
import com.rowlandrestorations.klinelogger.kline.EcuLink
import com.rowlandrestorations.klinelogger.kline.KLineException
import com.rowlandrestorations.klinelogger.kline.NEGATIVE_RESPONSE
import com.rowlandrestorations.klinelogger.kline.NegativeResponseException
import com.rowlandrestorations.klinelogger.kline.NoResponseException
import com.rowlandrestorations.klinelogger.kline.SID_READ_LOCAL_ID
import com.rowlandrestorations.klinelogger.kline.SID_START_DIAG
import com.rowlandrestorations.klinelogger.kline.SID_STOP_COMM
import com.rowlandrestorations.klinelogger.kline.SID_TESTER_PRESENT
import com.rowlandrestorations.klinelogger.kline.hexs
import com.rowlandrestorations.klinelogger.kline.nrcName
import com.rowlandrestorations.klinelogger.kline.preciseSleep
import java.io.IOException

/** What the adapter and ECU reported while the session was set up. */
class ElmSession(
    val ecu: Int,
    val version: String,
    val protocol: String,
    val keyBytes: String,
    val diag: String,
    val voltage: String?,
)

/**
 * The Kawasaki KWP2000 dialect driven through an ELM327's AT-command interface. Ported from the iOS
 * app's OBDEngine. The ELM327 chip does the wake-up, framing and checksums that [com.rowlandrestorations.klinelogger.kline.KLine]
 * does by hand on a raw cable; this just sets its header to the ECU's address and sends service bytes as hex.
 *
 * Blocking: call only from a background thread. Throws KLineException for ECU problems and IOException
 * when the adapter itself stops answering or the radio link drops.
 */
class Elm327(
    private val io: ByteLink,
    private val traffic: (Direction, ByteArray) -> Unit = { _, _ -> },
    private val note: (String) -> Unit = {},
    private val ecuCandidates: List<Int> = ECU_AUTO_ADDRESSES,
    private val tester: Int = 0xF1,
    private val requestGapMs: Long = 55,     // same pacing as the cable: Kawasaki ignores requests < ~49 ms apart
) : EcuLink {
    private var lastActivity = 0L

    // --- session ----------------------------------------------------------

    fun startSession(): ElmSession {
        val banner = try {
            command("ATZ", RESET_TIMEOUT_MS)
        } catch (e: IOException) {
            throw IOException("No reply from the adapter. Check that it's plugged in with the key on, then try again.", e)
        }
        val version = banner.lines().map { it.trim() }.lastOrNull { it.contains("ELM", ignoreCase = true) }
            ?: banner.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.equals("ATZ", true) }
            ?: "ELM327"
        for (setup in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATST19")) {
            // ATST19 caps the adapter's wait for an answer at ~100 ms instead of its slower default.
            optionalCommand(setup)
        }
        val voltage = optionalCommand("ATRV")?.trim()?.takeIf { it.isNotEmpty() && !it.contains("?") }

        // The adapter's own bus init (wake-up + StartCommunication) runs on the first real message, so the
        // first request sent is StartDiagnosticSession. Clones vary in which K-line variants they handle well.
        var found: Pair<Int, String>? = null
        var lastError: Exception? = null
        attempts@ for ((proto, keyCheck) in PROTOCOLS) {
            optionalCommand(if (keyCheck) "ATKW1" else "ATKW0")
            command("ATSP$proto")
            for (addr in ecuCandidates) {
                command("ATSH81%02X%02X".format(addr, tester))
                if (proto != "5") optionalCommand("ATIIA%02X".format(addr))   // slow inits wake this address
                try {
                    request(byteArrayOf(SID_START_DIAG.toByte(), DIAG_SESSION.toByte()))
                    found = addr to "accepted"
                    break@attempts
                } catch (e: NegativeResponseException) {
                    found = addr to "refused (${nrcName(e.code)})"      // the ECU answered: it's there
                    break@attempts
                } catch (e: KLineException) {
                    lastError = e
                    note("No answer at ECU 0x%02X over protocol %s: %s".format(addr, proto, e.message))
                }
            }
        }
        val (ecu, diag) = found ?: throw NoResponseException(
            "The adapter couldn't wake the ECU at " + ecuCandidates.joinToString(" or ") { "0x%02X".format(it) } +
                ". Make sure the key is on and the adapter is in the ski's diagnostic plug. (${lastError?.message})",
        )
        val protocol = optionalCommand("ATDP")?.trim().orEmpty().ifEmpty { "K-line" }
        val keyBytes = optionalCommand("ATKW")?.trim().orEmpty()
        return ElmSession(ecu, version, protocol, keyBytes, diag, voltage)
    }

    override fun stop() {
        try {
            request(byteArrayOf(SID_STOP_COMM.toByte()))
        } catch (_: KLineException) {
        } catch (_: IOException) {
        }
        optionalCommand("ATPC")
    }

    override fun alive(): Boolean = try {
        request(byteArrayOf(SID_TESTER_PRESENT.toByte(), 0x01))
        true
    } catch (_: NegativeResponseException) {
        true
    } catch (_: KLineException) {
        false
    }

    // --- requests ---------------------------------------------------------

    override fun request(payload: ByteArray, timeoutMs: Long?): ByteArray {
        val sid = payload[0].toInt() and 0xFF
        traffic(Direction.TX, payload)
        val text = command(payload.joinToString("") { "%02X".format(it.toInt() and 0xFF) }, timeoutMs ?: REQUEST_TIMEOUT_MS)
        val frames = parseElmReply(text, hexs(payload))
        for (f in frames) {
            traffic(Direction.RX, f)
            val first = f[0].toInt() and 0xFF
            if (first == NEGATIVE_RESPONSE && f.size >= 3) {
                val code = f[2].toInt() and 0xFF
                if (code == 0x78) continue                           // response pending: the answer follows
                throw NegativeResponseException(f[1].toInt() and 0xFF, code)
            }
            if (first == (sid or 0x40)) return f
        }
        throw KLineException("Unexpected reply to ${hexs(payload)}: ${frames.joinToString(" / ") { hexs(it) }}")
    }

    override fun readLocalId(pid: Int): ByteArray {
        val resp = request(byteArrayOf(SID_READ_LOCAL_ID.toByte(), pid.toByte()))
        if (resp.size < 2 || (resp[1].toInt() and 0xFF) != pid) {
            throw KLineException("Answer was for a different PID: ${hexs(resp)}")
        }
        return resp.copyOfRange(2, resp.size)
    }

    // --- AT layer -----------------------------------------------------------

    /** An AT command whose failure isn't fatal (older clones lack some of them). Null if it failed. */
    private fun optionalCommand(cmd: String): String? = try {
        command(cmd)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Sends [cmd] and returns everything up to the adapter's '>' prompt. */
    private fun command(cmd: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): String {
        val wait = lastActivity + requestGapMs * 1_000_000 - System.nanoTime()
        if (lastActivity != 0L && wait > 0) preciseSleep(wait)
        try {
            io.purgeInput()
            io.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            val sb = StringBuilder()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val prompt = sb.indexOf(">")
                if (prompt >= 0) {
                    sb.setLength(prompt)
                    break
                }
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw IOException("The adapter stopped answering (no reply to $cmd).")
                sb.append(String(io.read(left), Charsets.US_ASCII))
            }
            // Drop the echo (until ATE0 takes effect) and blank lines.
            val reply = sb.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
                .joinToString("\n")
            if (cmd.startsWith("AT", ignoreCase = true)) {
                note("$cmd → ${reply.ifEmpty { "(nothing)" }}")
                if (reply.trim() == "?") throw KLineException("The adapter doesn't support $cmd.")
            }
            return reply
        } finally {
            lastActivity = System.nanoTime()
        }
    }

    companion object {
        const val DIAG_SESSION = 0x80
        private const val RESET_TIMEOUT_MS = 5_000L
        private const val COMMAND_TIMEOUT_MS = 2_000L
        private const val REQUEST_TIMEOUT_MS = 8_000L    // the first request includes the adapter's bus init

        /** ATSP protocol to try, and whether the adapter should check the ECU's key bytes. */
        private val PROTOCOLS = listOf(
            "5" to true,    // ISO 14230-4 KWP, fast init: what Kawasaki uses
            "4" to true,    // ISO 14230-4 KWP, 5-baud init
            "3" to true,    // ISO 9141-2
            "5" to false,   // fast init again, ignoring key bytes some clones reject
        )
    }
}

/** What an ELM327 says when it gives up on a request, mapped to a plain-language reason. */
private val ELM_ERRORS = listOf(
    "NO DATA" to "No reply from the ECU",
    "UNABLE TO CONNECT" to "The adapter couldn't wake the ECU",
    "BUS INIT" to "The adapter couldn't wake the ECU",
    "BUS ERROR" to "K-line bus error",
    "BUS BUSY" to "K-line bus busy",
    "FB ERROR" to "K-line feedback error (check the adapter's wiring)",
    "DATA ERROR" to "Garbled reply (bad checksum)",
    "BUFFER FULL" to "The adapter's buffer overflowed",
    "STOPPED" to "The adapter interrupted the request",
    "CAN ERROR" to "The adapter is on the wrong protocol",
    "ERR" to "The adapter reported an error",
    "?" to "The adapter didn't understand the request",
)

/**
 * The reply lines of a KWP request sent with headers off (ATH0), as byte arrays. "SEARCHING..." and a
 * successful "BUS INIT: ...OK" are skipped; anything an ELM327 prints when it gives up becomes a
 * KLineException ([NoResponseException] when the ECU simply didn't answer).
 */
fun parseElmReply(text: String, request: String = "request"): List<ByteArray> {
    val frames = mutableListOf<ByteArray>()
    for (raw in text.split('\r', '\n')) {
        var line = raw.trim().uppercase()
        if (line.isEmpty() || line.startsWith("SEARCHING")) continue
        if (line.startsWith("BUS INIT")) {
            if (line.contains("ERROR")) throw NoResponseException("The adapter couldn't wake the ECU ($raw)")
            line = line.substringAfter("OK", "").trim()      // "BUS INIT: ...OK" may share a line with the data
            if (line.isEmpty()) continue
        }
        val hex = line.replace(" ", "")
        if (hex.isNotEmpty() && hex.all { it in "0123456789ABCDEF" } && hex.length % 2 == 0) {
            frames += ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            continue
        }
        val reason = ELM_ERRORS.firstOrNull { line.contains(it.first) }?.second
        when {
            reason == null -> throw KLineException("Unexpected reply to $request: ${raw.trim()}")
            line.contains("NO DATA") || line.contains("UNABLE TO CONNECT") ->
                throw NoResponseException("$reason to $request")
            else -> throw KLineException("$reason (${raw.trim()})")
        }
    }
    if (frames.isEmpty()) throw NoResponseException("No reply to $request")
    return frames
}
