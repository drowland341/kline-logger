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
    /**
     * ReadDiagnosticTroubleCodes: 13 01. Found by probing a 2007 Ultra LX (2026-10-20): it refuses the usual
     * 18 00 FF 00 and 17/19/03/07 outright (7F xx 11, no such service); 13 with any other parameter byte, or
     * none, is refused as the wrong format (7F 13 12). 13 01 answered 53 00: no codes stored. With the cam sensor
     * unplugged it answered 53 01 03 40 = P0340, and went back to 53 00 by itself once the sensor was plugged in
     * and the engine ran: this request seems to report current faults, not history.
     */
    val READ_REQUEST: ByteArray = parseHex("13 01")

    /**
     * ClearDiagnosticInformation forms, tried in turn until one answers 54. In the 10 80 session an Ultra LX
     * (2026-10-20) refused all of 14 00..14 FF as no such service (7F 14 11) and every three-byte form as the
     * wrong format (7F 14 12), so Clear Codes also tries them in other diagnostic sessions ([CLEAR_SESSIONS]).
     * A 2024 SXR160 (2026-10-21) took 14 01 in the 10 80 session: 7F 14 78 (response pending), then 54.
     */
    val CLEAR_FORMS: List<ByteArray> =
        listOf("14 FF 00", "14 00 00", "14 FF FF", "14 01", "14 00", "14 FF", "14").map(::parseHex)

    /**
     * StartDiagnosticSession types to try clearing in, after the 10 80 session the app normally uses. 0x85
     * (programming / reflash) is left out on purpose. The app always switches back to 10 80 afterwards.
     */
    val CLEAR_SESSIONS: List<Int> = listOf(0x81, 0x87, 0x86, 0x89, 0x90, 0x92, 0xC0, 0xFA)

    /** The session the app opens on connect (kline.py: "Kawasaki wants this first"). */
    const val NORMAL_SESSION = 0x80

    /** SecurityAccess, request seed only: tells whether the ECU wants unlocking. No key is ever sent. */
    val SEED_REQUEST: ByteArray = parseHex("27 01")

    /** Bytes per code in the reply after "53 <count>": DTC high, DTC low, no status byte (confirmed on an Ultra LX). */
    const val RECORD_SIZE = 2

    val readSid: Int get() = READ_REQUEST[0].toInt() and 0xFF
    val clearSid: Int get() = CLEAR_FORMS[0][0].toInt() and 0xFF
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
    val dtc: String = "",               // the OBD-II code the ECU sends for it, e.g. P0340
    val dtcVerified: Boolean = false,   // true once a real ski sent [dtc] for this fault
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
            dtc = o.optString("dtc"),
            dtcVerified = o.optBoolean("dtcVerified"),
        )
    }
}

/** One code from the ECU: [dtc] as the ECU sent it (e.g. P0340), [info] the service manual entry it matches, if any. */
data class StoredCode(val raw: ByteArray, val dtc: String, val status: Int?, val info: ServiceCode?) {
    val code: Int? get() = info?.code   // Kawasaki service code number, as the dash shows it
    val rawHex: String get() = hexs(raw)
}

class DtcParseResult(val codes: List<StoredCode>, val reportedCount: Int?, val warning: String?)

/** Parses a positive read-codes reply: 53 <count> [<hi> <lo>]... (a status byte follows each code if RECORD_SIZE is 3). */
fun parseDtcReply(payload: ByteArray, table: Map<Int, ServiceCode>): DtcParseResult {
    if (payload.size < 2) return DtcParseResult(emptyList(), null, if (payload.size == 1) "Reply had no code count." else null)
    val count = payload[1].toInt() and 0xFF
    val body = payload.copyOfRange(2, payload.size)
    val size = DtcProtocol.RECORD_SIZE
    val byDtc = table.values.filter { it.dtc.isNotEmpty() }.associateBy { it.dtc }
    val codes = (0 until body.size / size).map { i ->
        val rec = body.copyOfRange(i * size, i * size + size)
        val dtc = obdCode(rec[0].toInt() and 0xFF, rec.getOrNull(1)?.toInt()?.and(0xFF) ?: 0)
        StoredCode(rec, dtc, rec.getOrNull(2)?.toInt()?.and(0xFF), byDtc[dtc])
    }
    val warning = when {
        body.size % size != 0 -> "Reply had ${body.size % size} extra byte(s) after the codes; check RECORD_SIZE."
        codes.size != count -> "ECU said $count code(s) but sent ${codes.size}."
        else -> null
    }
    return DtcParseResult(codes, count, warning)
}

/**
 * Two DTC bytes in the standard OBD-II layout: the top two bits pick P/C/B/U, then four hex digits.
 * 03 40 -> P0340 (camshaft position sensor), the code an Ultra LX sent with its cam sensor unplugged.
 */
fun obdCode(hi: Int, lo: Int): String = "%c%X%X%02X".format("PCBU"[hi shr 6], (hi shr 4) and 3, hi and 0xF, lo)
