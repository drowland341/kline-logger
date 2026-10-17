package com.rowlandrestorations.klinelogger.channels

import android.content.Context
import org.json.JSONObject

/** One channel from a channel list (same fields as the desktop logger and the iOS app; see shared/README.md). */
data class PidDef(
    val name: String,
    val pid: Int,
    val formula: Formula,
    val units: String,
    val enabled: Boolean,
    val every: Int,
    val verified: Boolean,
    val notes: String,
    val gaugeMin: Double?,
    val gaugeMax: Double?,
    val priority: Boolean = false,     // phone apps only: re-read between every other channel read (RPM)
) {
    fun decode(data: ByteArray): Double = formula.eval(data)

    /** Units for display; the desktop app uses "raw" to mean "no units". */
    val displayUnits: String get() = if (units == "raw") "" else units
}

/** The user's editable channel list, saved in the desktop logger's file format. */
const val PID_FILE = "pids.json"

/** Built-in channels, from the repo's shared/channels folder (one file per manufacturer). */
const val DEFAULT_CHANNELS = "kawasaki.json"

fun loadPidDefs(context: Context): List<PidDef> = parsePidDefs(readDefaults(context))

fun readDefaults(context: Context): String =
    context.assets.open(DEFAULT_CHANNELS).bufferedReader().use { it.readText() }

fun parsePidDefs(json: String): List<PidDef> {
    val items = JSONObject(json).getJSONArray("pids")
    return (0 until items.length()).map { i ->
        val o = items.getJSONObject(i)
        val pid = parsePid(o.get("pid"))
        PidDef(
            name = o.optString("name").trim().ifEmpty { "PID 0x%02X".format(pid) },
            pid = pid,
            formula = Formula(o.optString("formula").trim().ifEmpty { "raw" }),
            units = o.optString("units", "raw"),
            enabled = o.optBoolean("enabled", true),
            every = maxOf(1, o.optInt("every", 1)),
            verified = o.optBoolean("verified", false),
            notes = o.optString("notes"),
            gaugeMin = o.optDoubleOrNull("gauge_min"),
            gaugeMax = o.optDoubleOrNull("gauge_max"),
            priority = o.optBoolean("priority", false),
        )
    }
}

/** Like kline.py's parse_pid: '09', '0x09' and 9 all mean 0x09. */
fun parsePid(value: Any): Int {
    val pid = if (value is Number) value.toInt() else value.toString().trim().lowercase().replace("0x", "").toInt(16)
    require(pid in 0..0xFF) { "PID $value is outside 0x00-0xFF" }
    return pid
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
