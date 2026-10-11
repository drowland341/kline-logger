package com.rowlandrestorations.klinelogger.channels

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's channel list. Starts as a copy of the bundled pids.json and is saved back in the
 * desktop logger's format, so the file can be copied between the two apps.
 */
class ChannelStore(private val context: Context) {
    val file = File(context.getExternalFilesDir(null) ?: context.filesDir, PID_FILE)

    /** The saved channels, or the bundled defaults if there is no saved file yet. */
    fun load(): List<PidDef> = if (file.exists()) parsePidDefs(file.readText()) else defaults()

    fun defaults(): List<PidDef> = loadPidDefs(context)

    fun save(channels: List<PidDef>) {
        val doc = JSONObject()
            .put("_readme", JSONArray(README))
            .put("defaults_revision", DEFAULTS_REVISION)
            .put("pids", JSONArray(channels.map { it.toJson() }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(doc.toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        // Same header kline.py writes, so the desktop app sees a file it knows.
        const val DEFAULTS_REVISION = 1
        val README = listOf(
            "Channel definitions for kawasaki_logger.py. You can edit these in the app's Channels tab.",
            "pid: hex local identifier sent as '21 <pid>'. every: read on every Nth update (1 = every time).",
            "formula variables: A B C D... (data bytes), raw, sraw, n, d. Functions: abs min max round signed(v,bits) math.*",
            "verified: set true once a channel's scaling has been checked against a known-good reading.",
            "gauge_min / gauge_max: range of the tile's gauge bar. null = scale to the values seen this session.",
        )
    }
}

fun PidDef.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("pid", "0x%02X".format(pid))
    .put("formula", formula.source)
    .put("units", units)
    .put("enabled", enabled)
    .put("every", every)
    .put("verified", verified)
    .put("notes", notes)
    .put("gauge_min", gaugeMin ?: JSONObject.NULL)
    .put("gauge_max", gaugeMax ?: JSONObject.NULL)
