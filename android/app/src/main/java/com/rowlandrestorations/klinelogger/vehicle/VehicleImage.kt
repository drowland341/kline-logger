package com.rowlandrestorations.klinelogger.vehicle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * The rider's own ski picture for the Digital dashboard: picked from the gallery, background cut out, trimmed,
 * and kept as a PNG in the app's private storage so it survives restarts.
 */
object VehicleImage {
    private const val MAX_SIDE = 900

    private fun file(context: Context) = File(context.filesDir, "dash_vehicle.png")

    fun load(context: Context): Bitmap? = file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** Reads [uri], cuts out its background, trims it and saves it. Slow: call off the main thread. */
    @Throws(IOException::class)
    fun import(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That file isn't a picture the app can read.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Couldn't open that picture.")

        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded
        val w = sized.width
        val h = sized.height
        val px = IntArray(w * h)
        sized.copy(Bitmap.Config.ARGB_8888, false).getPixels(px, 0, w, 0, 0, w, h)

        val cut = BackgroundCut.cut(px, w, h)
        val box = BackgroundCut.visibleBounds(cut, w, h) ?: throw IOException("Nothing was left after removing the background.")
        val full = Bitmap.createBitmap(cut, w, h, Bitmap.Config.ARGB_8888)
        val trimmed = Bitmap.createBitmap(full, box[0], box[1], box[2] - box[0], box[3] - box[1])

        file(context).outputStream().use { out ->
            if (!trimmed.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Couldn't save the picture.")
        }
        return trimmed
    }
}
