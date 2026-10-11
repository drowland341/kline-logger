package com.rowlandrestorations.klinelogger.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.rowlandrestorations.klinelogger.USB_PERMISSION"

/** Shows Android's "Allow access to the USB device?" prompt if needed. Returns true once access is granted. */
suspend fun requestUsbPermission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
    if (usb.hasPermission(device)) return true
    return suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                context.unregisterReceiver(this)
                if (cont.isActive) cont.resume(usb.hasPermission(device))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cont.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
        // The system adds the result extras, so the intent must be mutable; explicit so that is allowed on Android 14.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }
}
