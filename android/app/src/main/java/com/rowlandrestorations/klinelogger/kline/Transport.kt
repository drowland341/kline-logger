package com.rowlandrestorations.klinelogger.kline

import java.io.Closeable
import java.io.IOException

/**
 * Byte-level access to a K-line cable: the part of pyserial that kline.py relies on.
 * Every method blocks and must be called from a background thread.
 * Implementations throw IOException when the cable itself fails (unplugged, reset, lost power).
 */
interface Transport : Closeable {
    val baudRate: Int

    @Throws(IOException::class)
    fun setBaudRate(baud: Int)

    /** Hold the line low (true) or release it (false). */
    @Throws(IOException::class)
    fun setBreak(on: Boolean)

    @Throws(IOException::class)
    fun write(data: ByteArray)

    /** Up to [max] received bytes, waiting at most [timeoutMs] for the first one. Empty on timeout. */
    @Throws(IOException::class)
    fun read(max: Int, timeoutMs: Long): ByteArray

    /** Throw away everything received so far. */
    @Throws(IOException::class)
    fun purgeInput()
}
