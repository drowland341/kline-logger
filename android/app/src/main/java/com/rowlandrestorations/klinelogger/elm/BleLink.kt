package com.rowlandrestorations.klinelogger.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Known ELM327 BLE serial profiles: service, notify characteristic, write characteristic. */
private val KNOWN_PROFILES = listOf(
    Triple("0000fff0", "0000fff1", "0000fff2"),     // most clones (Veepeak, Konnwei, ...)
    Triple("0000ffe0", "0000ffe1", "0000ffe1"),     // HM-10 style: one characteristic both ways
    Triple("000018f0", "00002af0", "00002af1"),     // Vgate iCar Pro BLE
    Triple("e7810a71", "bef8d6c9", "bef8d6c9"),     // OBDLink CX / MX+
)

/** Standard Bluetooth SIG services (Generic Access, Device Information, ...) never carry the serial stream. */
private fun isStandardService(uuid: UUID): Boolean {
    val s = uuid.toString().lowercase()
    if (!s.endsWith("-0000-1000-8000-00805f9b34fb")) return false
    val short = s.substring(4, 8).toInt(16)
    return short in 0x1800..0x18FF && short != 0x18F0
}

/**
 * Scans for Bluetooth LE devices with a name, calling [onFound] for each new one. Returns a function
 * that stops the scan. Needs BLUETOOTH_SCAN (Android 12+) or location permission (older).
 */
@SuppressLint("MissingPermission")
fun scanBle(context: Context, onFound: (BtDevice) -> Unit, onError: (String) -> Unit): () -> Unit {
    val bt = bluetoothAdapter(context)
    val scanner = bt?.bluetoothLeScanner
    if (bt == null || !bt.isEnabled || scanner == null) {
        onError("Bluetooth is off. Turn it on and try again.")
        return {}
    }
    val seen = HashSet<String>()
    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: try {
                result.device.name
            } catch (_: SecurityException) {
                null
            } ?: return
            if (seen.add(result.device.address)) onFound(BtDevice(result.device.address, name))
        }

        override fun onScanFailed(errorCode: Int) {
            onError("Bluetooth scan failed (code $errorCode). Turn Bluetooth off and on, then try again.")
        }
    }
    try {
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    } catch (e: SecurityException) {
        onError("Bluetooth permission was denied.")
        return {}
    }
    return {
        try {
            scanner.stopScan(cb)
        } catch (_: Exception) {
        }
    }
}

/**
 * Bluetooth LE adapters (Veepeak BLE, Vgate iCar Pro, OBDLink CX, ...). The serial stream rides on a
 * notify characteristic (adapter to phone) and a write characteristic (phone to adapter). Known profiles
 * are picked by UUID; anything else falls back to the iOS app's rule: a characteristic that both writes
 * and notifies, else the first writable plus the first notifying one.
 */
@SuppressLint("MissingPermission")
class BleLink private constructor(private val device: BtDevice) : QueuedByteLink() {
    override val description = "Bluetooth LE · ${device.name}"

    private lateinit var gatt: BluetoothGatt
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private val ready = CountDownLatch(1)
    @Volatile private var setupError: String? = null
    private val writeDone = Semaphore(0)
    @Volatile private var closed = false

    /** Every GATT service/characteristic found, for the Connection screen when nothing usable turns up. */
    var profile: String = ""
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.discoverServices()) setupFailed("Couldn't read the adapter's Bluetooth profile.")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setupFailed("The adapter disconnected (status $status).")
                fail(IOException(if (closed) "Connection closed." else "Lost the Bluetooth LE adapter."))
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return setupFailed("Couldn't read the adapter's Bluetooth profile (status $status).")
            profile = g.services.joinToString("\n") { s ->
                s.uuid.toString() + s.characteristics.joinToString("") { "\n  ${it.uuid} [${props(it.properties)}]" }
            }
            val (notify, write) = pickCharacteristics(g)
                ?: return setupFailed("Couldn't find a serial data channel on this adapter's Bluetooth profile.")
            writeChar = write
            writeType = if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.setCharacteristicNotification(notify, true)
            val desc = notify.getDescriptor(CCCD)
            if (desc == null) {
                ready.countDown()            // some clones notify without a CCCD
                return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
            if (!started) setupFailed("Couldn't subscribe to the adapter's data channel.")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.countDown()
            else setupFailed("Couldn't subscribe to the adapter's data channel (status $status).")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value)
        }

        @Deprecated("Called instead of the 3-argument version before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { deliver(it.copyOf()) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }
    }

    private fun setupFailed(msg: String) {
        if (setupError == null) setupError = msg
        ready.countDown()
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((svc, n, w) in KNOWN_PROFILES) {
            val service = g.services.firstOrNull { it.uuid.toString().startsWith(svc) } ?: continue
            val notify = service.characteristics.firstOrNull { it.uuid.toString().startsWith(n) } ?: continue
            val write = service.characteristics.firstOrNull { it.uuid.toString().startsWith(w) } ?: continue
            return notify to write
        }
        val all = g.services.filterNot { isStandardService(it.uuid) }.flatMap { it.characteristics }
        val canWrite = { c: BluetoothGattCharacteristic ->
            c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }
        val canNotify = { c: BluetoothGattCharacteristic ->
            c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }
        all.firstOrNull { canWrite(it) && canNotify(it) }?.let { return it to it }
        val write = all.firstOrNull(canWrite) ?: return null
        val notify = all.firstOrNull(canNotify) ?: return null
        return notify to write
    }

    override fun write(data: ByteArray) {
        val ch = writeChar ?: throw IOException("Not connected.")
        var i = 0
        while (i < data.size) {
            val chunk = data.copyOfRange(i, minOf(i + CHUNK, data.size))
            writeDone.drainPermits()
            val started = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(ch, chunk, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                ch.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
            if (!started) throw IOException("Couldn't send to the Bluetooth LE adapter.")
            if (!writeDone.tryAcquire(2, TimeUnit.SECONDS)) throw IOException("The Bluetooth LE adapter stopped accepting data.")
            i += CHUNK
        }
    }

    override fun close() {
        closed = true
        try {
            gatt.disconnect()
            gatt.close()
        } catch (_: Exception) {
        }
        fail(IOException("Connection closed."))
    }

    companion object {
        private const val CHUNK = 20      // the default ATT MTU leaves 20 bytes per write

        /** Connects and subscribes; blocks up to [timeoutMs]. */
        @Throws(IOException::class)
        fun open(context: Context, device: BtDevice, timeoutMs: Long = 15_000): BleLink {
            val bt = bluetoothAdapter(context) ?: throw IOException("This phone has no Bluetooth.")
            if (!bt.isEnabled) throw IOException("Bluetooth is off. Turn it on and try again.")
            val link = BleLink(device)
            val dev: BluetoothDevice = bt.getRemoteDevice(device.address)
            link.gatt = dev.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("Couldn't start a Bluetooth LE connection.")
            if (!link.ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                link.close()
                throw IOException("${device.name} didn't answer. Make sure it's powered (key on) and close to the phone.")
            }
            link.setupError?.let { msg ->
                link.close()
                throw IOException(if (link.profile.isEmpty()) msg else "$msg Services found:\n${link.profile}")
            }
            return link
        }

        private fun props(p: Int): String = buildList {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResp")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }.joinToString(", ").ifEmpty { "-" }
    }
}
