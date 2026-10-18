package com.rowlandrestorations.klinelogger.logging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * One saved log. [uri] is set for logs in Documents/KlineLogger (MediaStore); [file] for logs in the
 * app-private fallback folder.
 */
data class LogFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val uri: Uri? = null,
    val file: File? = null,
) {
    val inDocuments: Boolean get() = uri != null

    /** Identifies the log across list refreshes (size and date change while it's written). */
    val key: String get() = uri?.toString() ?: file?.path ?: name
}

/** A log being written: where it lives and the stream to write it to. */
class LogOutput(val log: LogFile, val stream: OutputStream)

/**
 * Where CSV logs go. On Android 10+ they're saved in Documents/KlineLogger through MediaStore, so they show
 * up in the Files app and on a PC over USB with no storage permission. Older Android, or a phone where
 * MediaStore refuses the file, falls back to the app-private folder (Android/data/.../files/logs), which
 * the list, Share and Delete still cover.
 */
class LogStore(context: Context) {
    private val resolver = context.contentResolver
    val privateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")

    /** Shown in the UI so people know where to look in the Files app. */
    val folderLabel: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Documents/$FOLDER" else privateDir.path

    /** Creates [name] and opens it for writing. Throws IOException if neither location works. */
    @Throws(IOException::class)
    fun create(name: String): LogOutput {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return createInDocuments(name)
            } catch (_: Exception) {
                // MediaStore can refuse (storage full, odd OEM builds): keep logging in the private folder.
            }
        }
        privateDir.mkdirs()
        val file = File(privateDir, name)
        return LogOutput(LogFile(name, 0, System.currentTimeMillis(), file = file), file.outputStream())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createInDocuments(name: String): LogOutput {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER")
        }
        // Not marked IS_PENDING: logs are written a row at a time so a crash keeps the run, and pending
        // files are hidden from the Files app and deleted by Android after a week.
        val uri = resolver.insert(documentsCollection(), values) ?: throw IOException("MediaStore refused $name")
        val stream = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't open $name")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore may rename on a clash ("name (1).csv"): show the name the Files app shows.
        val shown = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return LogOutput(LogFile(shown, 0, System.currentTimeMillis(), uri = uri), stream)
    }

    /**
     * Every log this app saved, newest first: its files in Documents/KlineLogger plus anything in the private
     * folder. Logs from before an uninstall stay in Documents but belong to the old install, so Android
     * no longer lists them here; they're still in the Files app.
     */
    fun list(): List<LogFile> {
        val logs = ArrayList<LogFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                logs += listDocuments()
            } catch (_: Exception) {
            }
        }
        privateDir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.mapTo(logs) {
            LogFile(it.name, it.length(), it.lastModified(), file = it)
        }
        return logs.sortedByDescending { it.modifiedMs }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listDocuments(): List<LogFile> {
        val collection = documentsCollection()
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
        )
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_DOCUMENTS}/$FOLDER%")
        return resolver.query(collection, cols, where, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".csv")) continue
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    // MediaStore fills in size and date a moment after a file closes; until then they read 0.
                    val seconds = maxOf(c.getLong(3), c.getLong(4))
                    val size = c.getLong(2).takeIf { it > 0 } ?: actualSize(uri)
                    add(LogFile(name, size, if (seconds > 0) seconds * 1000 else System.currentTimeMillis(), uri = uri))
                }
            }
        }.orEmpty()
    }

    private fun actualSize(uri: Uri): Long = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0
    } catch (_: Exception) {
        0
    }

    /** Deletes [log]. Returns false if Android wouldn't let it go. */
    fun delete(log: LogFile): Boolean = try {
        when {
            log.uri != null -> resolver.delete(log.uri, null, null) > 0
            log.file != null -> log.file.delete()
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun documentsCollection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    companion object {
        const val FOLDER = "KlineLogger"
        const val MIME = "text/csv"
    }
}
