package com.rowlandrestorations.klinelogger.elm

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The serial stream to an ELM327 adapter, whatever the radio: a Bluetooth SPP socket, a BLE
 * characteristic pair, or a TCP socket over the adapter's Wi-Fi. Same idea as the iOS app's ELM327Link.
 * Every method blocks and must be called from a background thread.
 */
interface ByteLink : Closeable {
    /** Shown on the Connection screen, e.g. "Bluetooth · OBDII". */
    val description: String

    @Throws(IOException::class)
    fun write(data: ByteArray)

    /** The next chunk received, waiting at most [timeoutMs]. Empty on timeout; throws once the link has dropped. */
    @Throws(IOException::class)
    fun read(timeoutMs: Long): ByteArray

    /** Throw away everything received so far. */
    fun purgeInput()
}

/** A [ByteLink] whose incoming bytes arrive on another thread (a stream pump or BLE callbacks). */
abstract class QueuedByteLink : ByteLink {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var failure: IOException? = null

    protected fun deliver(bytes: ByteArray) {
        if (bytes.isNotEmpty()) queue.offer(bytes)
    }

    /** Marks the link dead: reads drain what already arrived, then throw [e]. */
    protected fun fail(e: IOException) {
        if (failure == null) failure = e
        queue.offer(WAKE)
    }

    override fun read(timeoutMs: Long): ByteArray {
        val first = queue.poll()
        if (first != null && first !== WAKE) return first
        failure?.let { throw it }
        val chunk = queue.poll(maxOf(1, timeoutMs), TimeUnit.MILLISECONDS) ?: return EMPTY
        if (chunk === WAKE) {
            failure?.let { throw it }
            return EMPTY
        }
        return chunk
    }

    override fun purgeInput() {
        queue.removeIf { it !== WAKE }
    }

    private companion object {
        val WAKE = ByteArray(0)
        val EMPTY = ByteArray(0)
    }
}

/** A link over a pair of blocking streams (Bluetooth SPP or TCP), with a thread pumping the input side. */
class StreamByteLink(
    override val description: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
) : QueuedByteLink() {
    @Volatile private var closed = false

    private val pump = Thread({
        val buf = ByteArray(256)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) throw IOException("The adapter closed the connection.")
                deliver(buf.copyOf(n))
            }
        } catch (e: IOException) {
            fail(if (closed) IOException("Connection closed.") else IOException("Lost the adapter: ${e.message}", e))
        }
    }, "elm-reader").apply {
        isDaemon = true
        start()
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun close() {
        closed = true
        try {
            onClose()
        } catch (_: IOException) {
        }
        pump.interrupt()
    }
}
