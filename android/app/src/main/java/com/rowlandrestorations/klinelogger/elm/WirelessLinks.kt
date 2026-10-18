package com.rowlandrestorations.klinelogger.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** A Bluetooth device the user can pick on the Connection screen. */
data class BtDevice(val address: String, val name: String)

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Names cheap and branded ELM327 adapters advertise, used to list likely adapters first. */
private val ADAPTER_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI", "KW", "SCAN", "CAR", "LINK")

fun looksLikeAdapter(name: String): Boolean = ADAPTER_HINTS.any { name.uppercase().contains(it) }

fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/** Paired (bonded) Bluetooth devices, likely adapters first. Needs BLUETOOTH_CONNECT on Android 12+. */
@SuppressLint("MissingPermission")
fun pairedDevices(context: Context): List<BtDevice> {
    val bt = bluetoothAdapter(context) ?: return emptyList()
    return try {
        bt.bondedDevices.orEmpty()
            .map { BtDevice(it.address, it.name ?: it.address) }
            .sortedWith(compareByDescending<BtDevice> { looksLikeAdapter(it.name) }.thenBy { it.name.lowercase() })
    } catch (e: SecurityException) {
        emptyList()
    }
}

/**
 * Classic Bluetooth (SPP) adapters: by far the most common ELM327s. They must be paired in Android's
 * Bluetooth settings first (PIN is usually 1234 or 0000).
 */
@SuppressLint("MissingPermission")
@Throws(IOException::class)
fun openBluetoothClassic(context: Context, device: BtDevice): ByteLink {
    val bt = bluetoothAdapter(context) ?: throw IOException("This phone has no Bluetooth.")
    if (!bt.isEnabled) throw IOException("Bluetooth is off. Turn it on and try again.")
    val dev: BluetoothDevice = bt.getRemoteDevice(device.address)
    try {
        bt.cancelDiscovery()          // discovery slows connects to a crawl
    } catch (_: SecurityException) {
    }
    // Clones differ in which socket they accept, so try the standard one, then the fallbacks.
    val attempts: List<() -> BluetoothSocket> = listOf(
        { dev.createRfcommSocketToServiceRecord(SPP_UUID) },
        { dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
    )
    var last: Exception? = null
    for (make in attempts) {
        var socket: BluetoothSocket? = null
        try {
            socket = make()
            socket.connect()
            val s = socket
            return StreamByteLink("Bluetooth · ${device.name}", s.inputStream, s.outputStream) { s.close() }
        } catch (e: Exception) {
            last = e
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }
    throw IOException(
        "Couldn't connect to ${device.name}. Make sure it's powered (key on) and not connected to another phone or app. (${last?.message})",
        last,
    )
}

/**
 * Wi-Fi adapters: the phone joins the adapter's own network (often "WiFi_OBDII" or "OBDCHECK"), which has no
 * internet. Android would route a plain socket over mobile data instead, so bind it to the Wi-Fi network.
 */
@Throws(IOException::class)
fun openWifi(context: Context, host: String, port: Int): ByteLink {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Suppress("DEPRECATION")
    val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        ?: throw IOException("Not on Wi-Fi. Join the adapter's Wi-Fi network (often WiFi_OBDII) in Android settings first.")
    val socket = wifi.socketFactory.createSocket() as Socket
    try {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 5_000)
    } catch (e: IOException) {
        socket.close()
        throw IOException(
            "Couldn't reach the adapter at $host:$port. Check that the phone is joined to the adapter's Wi-Fi " +
                "and the address matches its manual. (${e.message})",
            e,
        )
    }
    return StreamByteLink("Wi-Fi · $host:$port", socket.getInputStream(), socket.getOutputStream()) { socket.close() }
}
