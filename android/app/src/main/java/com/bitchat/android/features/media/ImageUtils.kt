package com.bitchat.android.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

object ImageUtils {
    private const val MAX_DECODE_PIXELS = 4_194_304L

    fun downscaleAndSaveToAppFiles(context: Context, uri: Uri, maxDim: Int = 512, quality: Int = 85): String? =
        prepareOutgoing(context, maxDim, quality) {
            decodeSampled(maxDim, maxDim) { context.contentResolver.openInputStream(uri) }
        }

    fun downscalePathAndSaveToAppFiles(context: Context, path: String, maxDim: Int = 512, quality: Int = 85): String? =
        prepareOutgoing(context, maxDim, quality) { decodeSampledBitmap(path, maxDim, maxDim) }

    /** Decode only the pixels needed by the destination; callers run this on a media worker. */
    fun decodeSampledBitmap(path: String, maxWidth: Int, maxHeight: Int): Bitmap? =
        decodeSampled(maxWidth, maxHeight) { File(path).inputStream() }

    fun loadBitmapWithExifOrientation(path: String): Bitmap? =
        decodeSampledBitmap(path, Int.MAX_VALUE, Int.MAX_VALUE)

    private fun prepareOutgoing(context: Context, maxDim: Int, quality: Int, decode: () -> Bitmap?): String? {
        if (maxDim <= 0 || quality !in 0..100) return null
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        var output: File? = null
        return try {
            val bitmap = decode() ?: return null
            decoded = bitmap
            val scale = minOf(1.0, maxDim.toDouble() / maxOf(bitmap.width, bitmap.height))
            scaled = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            val directory = File(context.filesDir, "images/outgoing")
            check(directory.isDirectory || directory.mkdirs())
            // A timestamp alone can collide when two preparations finish in one millisecond.
            output = File.createTempFile("img_", ".jpg", directory)
            output.outputStream().use { check(requireNotNull(scaled).compress(Bitmap.CompressFormat.JPEG, quality, it)) }
            output.absolutePath
        } catch (_: Exception) {
            output?.delete()
            null
        } finally {
            if (scaled !== decoded) scaled?.recycle()
            decoded?.recycle()
        }
    }

    private fun decodeSampled(maxWidth: Int, maxHeight: Int, open: () -> InputStream?): Bitmap? {
        if (maxWidth <= 0 || maxHeight <= 0) return null
        var decoded: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val header = open() ?: return null
            header.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val orientation = try {
                open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val rotated = orientation in setOf(ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
                    if (rotated) maxHeight else maxWidth, if (rotated) maxWidth else maxHeight)
            }
            val bitmap = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            decoded = bitmap
            val matrix = orientationMatrix(orientation)
            val oriented = if (matrix.isIdentity) bitmap else
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            decoded = null // Ownership transfers to the caller.
            oriented
        } catch (_: Exception) {
            null
        } finally {
            decoded?.recycle()
        }
    }

    internal fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        require(width > 0 && height > 0 && maxWidth > 0 && maxHeight > 0)
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        var sample = 1
        while (sample <= (1 shl 29) && sample * 2.0 <= 1.0 / scale) sample *= 2
        // The extra budget also bounds panoramas and unusually large received images.
        while (((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > MAX_DECODE_PIXELS) {
            sample *= 2
        }
        return sample
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
}
