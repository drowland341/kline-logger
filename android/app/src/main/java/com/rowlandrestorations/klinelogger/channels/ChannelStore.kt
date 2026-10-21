package com.rowlandrestorations.klinelogger.channels

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's channel list. Starts as a copy of the built-in channels (shared/channels in the repo) and
 * is saved back in the desktop logger's format, so the file can be copied between the apps.
 */
class ChannelStore(private val context: Context) {
    val file = File(context.getExternalFilesDir(null) ?: context.filesDir, PID_FILE)

    private val bundled by lazy { JSONObject(readDefaults(context)) }

    /**
     * Raised in the shared channel file whenever the built-in channels change in a way users should pick
     * up. Compared with the number saved in the user's pids.json to offer the new defaults once.
     */
    val defaultsRevision: Int get() = bundled.optInt("defaults_revision", 0)

    /** What changed in the latest defaults, for the update offer. */
    val defaultsNote: String get() = bundled.optString("defaults_note").ifEmpty { "corrected channel scalings" }

    /** The saved channels, or the bundled defaults if there is no saved file yet. */
    fun load(): List<PidDef> = if (file.exists()) parsePidDefs(file.readText()) else defaults()

    fun defaults(): List<PidDef> = loadPidDefs(context)

    /**
     * Like kline.py's pids_revision: the defaults revision stamped in the saved file, 0 for files with
     * no stamp. No file, or one that can't be read, counts as current so it never triggers the update offer.
     */
    fun savedRevision(): Int = try {
        if (file.exists()) JSONObject(file.readText()).optInt("defaults_revision", 0) else defaultsRevision
    } catch (e: Exception) {
        defaultsRevision
    }

    fun save(channels: List<PidDef>) {
        val doc = JSONObject()
            .put("_readme", JSONArray(README))
            .put("defaults_revision", defaultsRevision)
            .put("pids", JSONArray(channels.map { it.toJson() }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(doc.toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        // Same header kline.py writes, so the desktop app sees a file it knows.
        private val README = listOf(
            "Channel definitions for kawasaki_logger.py. You can edit these in the app's Channels tab.",
            "pid: hex local identifier sent as '21 <pid>'. every: read on every Nth update (1 = every time).",
            "formula variables: A B C D... (data bytes), raw, sraw, n, d. Functions: abs min max round signed(v,bits) math.*",
            "verified: set true once a channel's scaling has been checked against a known-good reading.",
            "gauge_min / gauge_max: range of the tile's gauge bar. null = scale to the values seen this session.",
            "priority: phone apps only. true reads the channel on every update, ahead of the rest. Ignored by the Windows app.",
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
    .apply { if (priority) put("priority", true) }
