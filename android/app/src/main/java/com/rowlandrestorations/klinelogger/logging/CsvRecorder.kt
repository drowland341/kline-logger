package com.rowlandrestorations.klinelogger.logging

import com.rowlandrestorations.klinelogger.channels.PidDef
import java.io.BufferedWriter
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes one CSV row per polling cycle: elapsed seconds, then each channel's latest value. */
class CsvRecorder private constructor(
    val log: LogFile,
    val channels: List<PidDef>,     // the columns, fixed when recording starts even if channels are edited later
    private val out: BufferedWriter,
) : Closeable {
    var rows = 0
        private set

    fun write(seconds: Double, values: List<Double?>) {
        val line = StringBuilder("%.3f".format(Locale.US, seconds))
        for (v in values) {
            line.append(',')
            if (v != null) line.append("%.4g".format(Locale.US, v))
        }
        out.write(line.append('\n').toString())
        out.flush()     // a few rows a second: flushing each keeps the log intact if the app is killed mid-run
        rows++
    }

    override fun close() = out.close()

    companion object {
        fun start(store: LogStore, channels: List<PidDef>, tag: String): CsvRecorder {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val output = store.create("${stamp}_$tag.csv")
            val out = output.stream.bufferedWriter()
            val header = listOf("time_s") + channels.map { ch ->
                if (ch.displayUnits.isEmpty()) ch.name else "${ch.name} (${ch.displayUnits})"
            }
            out.write(header.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n")
            return CsvRecorder(output.log, channels, out)
        }
    }
}
