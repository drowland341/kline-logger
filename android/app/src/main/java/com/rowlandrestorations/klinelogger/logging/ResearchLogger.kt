package com.rowlandrestorations.klinelogger.logging

import com.rowlandrestorations.klinelogger.channels.PidDef
import com.rowlandrestorations.klinelogger.kline.hexs
import com.rowlandrestorations.klinelogger.rawValue
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Research log CSV, in the same layout as the iOS app's ResearchLogger so logs from either phone line up:
 * time_s, every channel (native units), gps_mph, then each scanned PID as hex and as a decimal number.
 * Written a row at a time and flushed, so a crash or a dead battery on the water keeps the run.
 */
class ResearchLogger private constructor(
    val file: File,
    private val channels: List<PidDef>,
    private val pids: List<Int>,
    private val out: BufferedWriter,
) : Closeable {
    var rows = 0
        private set
    var lastRowSeconds = Double.NEGATIVE_INFINITY
        private set

    fun write(seconds: Double, latest: Map<Int, Double?>, gpsMph: Double?, raw: Map<Int, ByteArray>) {
        val fields = ArrayList<String>(2 + channels.size + pids.size * 2)
        fields += "%.2f".format(Locale.US, seconds)
        channels.mapTo(fields) { ch -> latest[ch.pid]?.let { "%.4g".format(Locale.US, it) }.orEmpty() }
        fields += gpsMph?.let { "%.1f".format(Locale.US, it) }.orEmpty()
        pids.mapTo(fields) { pid -> raw[pid]?.let(::hexs).orEmpty() }
        pids.mapTo(fields) { pid -> raw[pid]?.let { "%.0f".format(Locale.US, rawValue(it)) }.orEmpty() }
        out.write(fields.joinToString(",") + "\n")
        out.flush()
        rows++
        lastRowSeconds = seconds
    }

    override fun close() = out.close()

    companion object {
        fun start(dir: File, channels: List<PidDef>, pids: List<Int>, tag: String): ResearchLogger {
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(dir, "research-${stamp}_$tag.csv")
            val out = file.bufferedWriter()
            val header = listOf("time_s") +
                channels.map { it.name.replace(",", " ") } +
                "gps_mph" +
                pids.map { "pid_0x%02X_hex".format(it) } +
                pids.map { "pid_0x%02X_dec".format(it) }
            out.write(header.joinToString(",") + "\n")
            out.flush()
            return ResearchLogger(file, channels, pids, out)
        }
    }
}
