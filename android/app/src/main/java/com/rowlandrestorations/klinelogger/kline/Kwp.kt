package com.rowlandrestorations.klinelogger.kline

/*
 * ISO 14230 / KWP2000 framing for Kawasaki KDS ECUs. Ported from kline.py.
 *
 * Protocol summary (what this code sends):
 *   wake-up       ISO 14230 fast init: 25 ms low, 25 ms high on the K-line
 *   80/81 11 F1   format byte, ECU address (0x11 default), tester address (0xF1)
 *   81            StartCommunication      -> ECU answers C1 <key bytes>
 *   10 80         StartDiagnosticSession  -> 50 80   (Kawasaki wants this first)
 *   21 <pid>      ReadDataByLocalIdentifier -> 61 <pid> <data...>
 *   3E 01         TesterPresent (keep-alive while idle)
 *   82            StopCommunication
 * Every frame ends with an 8-bit additive checksum.
 */

const val SID_START_COMM = 0x81
const val SID_STOP_COMM = 0x82
const val SID_START_DIAG = 0x10
const val SID_READ_LOCAL_ID = 0x21
const val SID_TESTER_PRESENT = 0x3E
const val NEGATIVE_RESPONSE = 0x7F

val ECU_AUTO_ADDRESSES = listOf(0x11, 0x28)   // the two addresses Kawasaki KDS ECUs are known to use

private val NRC_NAMES = mapOf(
    0x10 to "general reject",
    0x11 to "service not supported",
    0x12 to "sub-function not supported / invalid format",
    0x21 to "busy, repeat request",
    0x22 to "conditions not correct",
    0x31 to "request out of range",
    0x33 to "security access denied",
    0x35 to "invalid key",
    0x78 to "response pending",
    0x80 to "not supported in active session",
)

fun nrcName(code: Int): String = NRC_NAMES[code] ?: "unknown reason"

/** Anything that went wrong talking to the ECU. Port failures (cable unplugged) are IOExceptions instead. */
open class KLineException(message: String) : Exception(message)

class NoResponseException(message: String) : KLineException(message)

class BadChecksumException(message: String) : KLineException(message)

class NegativeResponseException(val sid: Int, val code: Int) :
    KLineException("ECU refused service 0x%02X: %s (0x%02X)".format(sid, nrcName(code), code))

fun hexs(data: ByteArray): String = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/** "21 09", "0x21,0x09" and "2109" all become [0x21, 0x09]. */
fun parseHex(text: String): ByteArray {
    val cleaned = text.replace(",", " ").replace("0x", " ").replace("0X", " ")
    val out = mutableListOf<Byte>()
    for (word in cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        val part = if (word.length % 2 != 0) "0$word" else word
        for (i in part.indices step 2) out.add(part.substring(i, i + 2).toInt(16).toByte())
    }
    return out.toByteArray()
}

fun checksum(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
    var sum = 0
    for (i in from until to) sum += data[i].toInt() and 0xFF
    return sum and 0xFF
}

fun buildFrame(target: Int, source: Int, payload: ByteArray, lengthInFmt: Boolean = false): ByteArray {
    val n = payload.size
    require(n in 1..255) { "payload must be 1-255 bytes" }
    val head = if (lengthInFmt && n <= 63) {
        byteArrayOf((0x80 or n).toByte(), target.toByte(), source.toByte())
    } else {
        byteArrayOf(0x80.toByte(), target.toByte(), source.toByte(), n.toByte())
    }
    val body = head + payload
    return body + checksum(body).toByte()
}

/** Total length of the frame starting at buf[0], or null if not known yet. */
fun frameLength(buf: ByteArray, size: Int = buf.size): Int? {
    if (size == 0) return null
    val fmt = buf[0].toInt() and 0xFF
    var pos = if (fmt and 0xC0 != 0) 3 else 1
    var n = fmt and 0x3F
    if (n == 0) {
        if (size <= pos) return null
        n = buf[pos].toInt() and 0xFF
        pos += 1
    }
    return pos + n + 1
}

/** One received frame. target/source are null if the frame has no address bytes. */
class Frame(val target: Int?, val source: Int?, val payload: ByteArray)

fun splitFrame(frame: ByteArray): Frame {
    val fmt = frame[0].toInt() and 0xFF
    var pos = 1
    var tgt: Int? = null
    var src: Int? = null
    if (fmt and 0xC0 != 0) {
        tgt = frame[1].toInt() and 0xFF
        src = frame[2].toInt() and 0xFF
        pos = 3
    }
    var n = fmt and 0x3F
    if (n == 0) {
        n = frame[pos].toInt() and 0xFF
        pos += 1
    }
    return Frame(tgt, src, frame.copyOfRange(pos, pos + n))
}
