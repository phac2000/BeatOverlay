package com.example.beatoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picture the user picked (PNG, JPG, WebP or SVG) into one small PNG inside the app's own
 * files, so the overlay can draw it quickly and never needs access to the original file again.
 */
object MarkerImage {

    private const val MAX_SIDE = 256
    private const val MAX_BYTES = 12 * 1024 * 1024

    /** Returns null on success, or a message describing what went wrong. Call from a background thread. */
    fun importFromBytes(ctx: Context, bytes: ByteArray): String? {
        return try {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
            val bmp = (if (head.contains("<svg")) renderSvg(bytes) else decodeRaster(bytes))
                ?: return "Could not read that picture."
            File(ctx.filesDir, MARKER_FILE).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            null
        } catch (_: OutOfMemoryError) {
            "That picture is too big to load."
        } catch (_: Exception) {
            "Could not read that picture."
        }
    }

    /** Returns null on success, or a message describing what went wrong. Call from a background thread. */
    fun import(ctx: Context, uri: Uri): String? {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                ?: return "That file is too large (12 MB at most)."
            importFromBytes(ctx, bytes)
        } catch (_: Exception) {
            "Could not read that picture."
        }
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeRaster(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val side = max(raw.width, raw.height)
        if (side <= MAX_SIDE) return raw
        val s = MAX_SIDE.toFloat() / side
        return Bitmap.createScaledBitmap(
            raw, max(1, (raw.width * s).roundToInt()), max(1, (raw.height * s).roundToInt()), true
        )
    }

    private fun renderSvg(bytes: ByteArray): Bitmap? {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        val picture = svg.renderToPicture()
        val pw = if (picture.width > 0) picture.width else MAX_SIDE
        val ph = if (picture.height > 0) picture.height else MAX_SIDE
        val s = MAX_SIDE.toFloat() / max(pw, ph)
        val bw = max(1, (pw * s).roundToInt())
        val bh = max(1, (ph * s).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPicture(picture, RectF(0f, 0f, bw.toFloat(), bh.toFloat()))
        return bmp
    }
}