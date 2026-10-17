package com.rowlandrestorations.klinelogger.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Runtime permissions the Bluetooth adapters need on this Android version. */
fun bluetoothPermissions(scan: Boolean): List<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (scan) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.BLUETOOTH_CONNECT)
    scan -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)     // BLE scans needed location before Android 12
    else -> emptyList()
}

val LOCATION_PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

fun hasPermissions(context: Context, perms: List<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Returns a function that asks for permissions and reports whether all were granted. */
@Composable
fun rememberPermissionRequest(onResult: (Boolean) -> Unit): (List<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(result.values.all { it })
    }
    return { perms -> if (perms.isEmpty()) onResult(true) else launcher.launch(perms.toTypedArray()) }
}

/** Opens the Share sheet for a log file, so it can go to Drive, email, Files, a laptop, ... */
fun shareFile(context: Context, file: File, mime: String = "text/csv") {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't share ${file.name}: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** Opens a system settings page (Bluetooth, Wi-Fi), ignoring phones that lack it. */
fun openSettings(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
