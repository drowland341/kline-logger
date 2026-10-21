package com.rowlandrestorations.klinelogger.logging

import com.rowlandrestorations.klinelogger.kline.Direction
import java.io.BufferedWriter
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes every frame on the bus as it happens: seconds since the session started (the same clock as the
 * channel CSV), TX/RX (or NOTE for an ELM327 AT command and its answer), and the bytes in hex.
 */
class TrafficRecorder private constructor(
    val log: LogFile,
    private val out: BufferedWriter,
    private val sessionStart: Long,     // System.nanoTime() when the session began
) : Closeable {
    var lines = 0
        private set

    @Synchronized
    fun write(dir: Direction, data: String) {
        val seconds = (System.nanoTime() - sessionStart) / 1e9
        out.write("%.3f,%s,\"%s\"\n".format(Locale.US, seconds, dir.name, data.replace("\"", "\"\"")))
        out.flush()     // keeps the file intact if the app is killed mid-run
        lines++
    }

    @Synchronized
    override fun close() = out.close()

    companion object {
        fun start(store: LogStore, tag: String, sessionStart: Long): TrafficRecorder {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val output = store.create("traffic-${stamp}_$tag.csv")
            val out = output.stream.bufferedWriter()
            out.write("time_s,direction,data\n")
            return TrafficRecorder(output.log, out, sessionStart)
        }
    }
}
