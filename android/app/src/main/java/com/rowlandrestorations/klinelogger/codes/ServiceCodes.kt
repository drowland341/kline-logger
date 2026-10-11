package com.rowlandrestorations.klinelogger.codes

import android.content.Context
import com.rowlandrestorations.klinelogger.kline.hexs
import com.rowlandrestorations.klinelogger.kline.parseHex
import org.json.JSONObject

const val SERVICE_CODE_FILE = "ultra_lx_service_codes.json"

/**
 * The fault-code requests, in one place. These are the standard KWP2000 services; if the ski turns out
 * to use something else (check the Codes screen's debug section), change the hex here.
 */
object DtcProtocol {
    /** ReadDiagnosticTroubleCodesByStatus: 18 <status mask: 00 = all> <group FF 00 = all groups>. */
    val READ_REQUEST: ByteArray = parseHex("18 00 FF 00")

    /** ClearDiagnosticInformation: 14 <group FF 00 = all groups>. */
    val CLEAR_REQUEST: ByteArray = parseHex("14 FF 00")

    /** Bytes per code in the reply after "58 <count>": DTC high, DTC low, status. */
    const val RECORD_SIZE = 3

    /**
     * true: the DTC bytes hold the code as hex digits (0x0014 = code 14), as Kawasaki shows it on the dash.
     * false: they hold it as a plain number (0x000E = code 14).
     */
    const val CODES_AS_HEX_DIGITS = true

    val readSid: Int get() = READ_REQUEST[0].toInt() and 0xFF
    val clearSid: Int get() = CLEAR_REQUEST[0].toInt() and 0xFF
}

/** One entry of the service manual's code table. */
data class ServiceCode(
    val code: Int,
    val meter: String,
    val title: String,
    val problem: String,
    val causes: List<String>,
    val ecuPins: List<Int>,
    val failSafe: String,
)

fun loadServiceCodes(context: Context): Map<Int, ServiceCode> =
    context.assets.open(SERVICE_CODE_FILE).bufferedReader().use { parseServiceCodes(it.readText()) }

fun parseServiceCodes(json: String): Map<Int, ServiceCode> {
    val items = JSONObject(json).getJSONArray("codes")
    return (0 until items.length()).associate { i ->
        val o = items.getJSONObject(i)
        val causes = o.optJSONArray("causes")
        val pins = o.optJSONArray("ecuPins")
        val code = o.getInt("code")
        code to ServiceCode(
            code = code,
            meter = o.optString("meter"),
            title = o.optString("title"),
            problem = o.optString("problem"),
            causes = causes?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            ecuPins = pins?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
            failSafe = o.optString("failSafe"),
        )
    }
}

/** One code from the ECU. [code] is null when the bytes don't spell a service code number. */
data class StoredCode(val raw: ByteArray, val code: Int?, val status: Int?, val info: ServiceCode?) {
    val rawHex: String get() = hexs(raw)
}

class DtcParseResult(val codes: List<StoredCode>, val reportedCount: Int?, val warning: String?)

/** Parses a positive ReadDTCByStatus reply: 58 <count> [<hi> <lo> <status>]... */
fun parseDtcReply(payload: ByteArray, table: Map<Int, ServiceCode>): DtcParseResult {
    if (payload.size < 2) return DtcParseResult(emptyList(), null, if (payload.size == 1) "Reply had no code count." else null)
    val count = payload[1].toInt() and 0xFF
    val body = payload.copyOfRange(2, payload.size)
    val size = DtcProtocol.RECORD_SIZE
    val codes = (0 until body.size / size).map { i ->
        val rec = body.copyOfRange(i * size, i * size + size)
        val number = ((rec[0].toInt() and 0xFF) shl 8) or (rec.getOrNull(1)?.toInt()?.and(0xFF) ?: 0)
        val code = if (DtcProtocol.CODES_AS_HEX_DIGITS) "%X".format(number).toIntOrNull() else number
        StoredCode(rec, code, rec.getOrNull(2)?.toInt()?.and(0xFF), code?.let { table[it] })
    }
    val warning = when {
        body.size % size != 0 -> "Reply had ${body.size % size} extra byte(s) after the codes; check RECORD_SIZE."
        codes.size != count -> "ECU said $count code(s) but sent ${codes.size}."
        else -> null
    }
    return DtcParseResult(codes, count, warning)
}
