package com.rowlandrestorations.klinelogger.usb

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.rowlandrestorations.klinelogger.kline.KLineException
import com.rowlandrestorations.klinelogger.kline.Transport
import java.io.IOException

/**
 * K-line cable on USB OTG through usb-serial-for-android.
 *
 * A SerialInputOutputManager thread reads the port continuously into [rx], the way the OS buffers
 * a COM port on Windows. Short-timeout bulkTransfer reads can lose bytes, so KLine never touches
 * the USB endpoint directly; it reads from [rx] instead.
 */
class UsbSerialTransport private constructor(
    private val port: UsbSerialPort,
    initialBaud: Int,
) : Transport {

    override var baudRate = initialBaud
        private set

    private val lock = Object()
    private var rx = ByteArray(1024)
    private var rxSize = 0
    @Volatile private var failure: Exception? = null

    private val io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            synchronized(lock) {
                if (rxSize + data.size > rx.size) rx = rx.copyOf(maxOf(rx.size * 2, rxSize + data.size))
                data.copyInto(rx, rxSize)
                rxSize += data.size
                lock.notifyAll()
            }
        }

        override fun onRunError(e: Exception) {
            failure = e
            synchronized(lock) { lock.notifyAll() }
        }
    })

    private fun check() {
        failure?.let { throw IOException("Lost the USB cable: ${it.message}", it) }
    }

    override fun setBaudRate(baud: Int) {
        check()
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        baudRate = baud
    }

    override fun setBreak(on: Boolean) {
        check()
        try {
            port.setBreak(on)
        } catch (e: UnsupportedOperationException) {
            throw KLineException("This cable's chip can't hold the line low. Try the 360 baud wake-up method.")
        }
    }

    override fun write(data: ByteArray) {
        check()
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun read(max: Int, timeoutMs: Long): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (rxSize == 0) {
                check()
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return ByteArray(0)
                lock.wait(left)
            }
            val n = minOf(max, rxSize)
            val out = rx.copyOf(n)
            rx.copyInto(rx, 0, n, rxSize)
            rxSize -= n
            return out
        }
    }

    override fun purgeInput() {
        check()
        synchronized(lock) { rxSize = 0 }
    }

    override fun close() {
        io.stop()
        try {
            port.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 1000

        /** Opens [driver]'s first port. The app must already hold USB permission for its device. */
        fun open(usb: UsbManager, driver: UsbSerialDriver, baud: Int, dtr: Boolean, rts: Boolean): UsbSerialTransport {
            val connection = usb.openDevice(driver.device)
                ?: throw IOException("Android would not open the USB cable (permission missing?)")
            val port = driver.ports[0]
            port.open(connection)
            try {
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                try {
                    port.setDTR(dtr)
                    port.setRTS(rts)
                } catch (_: UnsupportedOperationException) {
                }
                // FTDI holds received bytes for up to 16 ms by default, which adds a delay to every request.
                if (port is FtdiSerialDriver.FtdiSerialPort) port.setLatencyTimer(1)
                val transport = UsbSerialTransport(port, baud)
                transport.io.start()
                return transport
            } catch (e: Exception) {
                try {
                    port.close()
                } catch (_: IOException) {
                }
                throw e
            }
        }
    }
}
