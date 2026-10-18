package com.rowlandrestorations.klinelogger.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.rowlandrestorations.klinelogger.logging.LogFile
import java.io.File
import java.io.IOException

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

/**
 * Opens the Share sheet for a saved log, so it can go to Drive, email, Files, a laptop, ... Always shared
 * through the FileProvider, so the receiving app sees the real file name. A log in Documents/KlineLogger
 * is copied into the cache first: its MediaStore URI shows up in other apps as a bare number.
 */
fun shareLog(context: Context, log: LogFile) {
    try {
        val file = log.file ?: cacheCopy(context, log)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        shareUri(context, uri, log.name)
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't share ${log.name}: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** Copies a MediaStore log into cache/share (cleared each time, so copies don't pile up). */
private fun cacheCopy(context: Context, log: LogFile): File {
    val dir = File(context.cacheDir, "share")
    dir.deleteRecursively()
    dir.mkdirs()
    val copy = File(dir, log.name)
    val uri = log.uri ?: throw IOException("Nothing to share")
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't read ${log.name}")
    input.use { src -> copy.outputStream().use { src.copyTo(it) } }
    return copy
}

private fun shareUri(context: Context, uri: Uri, name: String, mime: String = "text/csv") {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser needs the grant too, or some targets can't read the file.
    send.clipData = ClipData.newRawUri(name, uri)
    context.startActivity(
        Intent.createChooser(send, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

/** Opens a system settings page (Bluetooth, Wi-Fi), ignoring phones that lack it. */
fun openSettings(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
