package com.meteor.memory.feature.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * Bitmap plumbing shared by the thumbnail, the importer and the video cover.
 *
 * Decoding is always sampled: a 12 MP JPEG must never be inflated at full size
 * just to fill a 96dp tile.
 */
internal object MediaBitmaps {

    /** Longest edge used for cached/derived thumbnails (ADR-052 §4: ≤512px). */
    const val THUMBNAIL_MAX_EDGE = MediaImportLogic.MAX_THUMBNAIL_EDGE

    /** Longest edge acceptable for a decoded in-memory copy, whatever the caller wants. */
    private const val DECODE_CEILING = 2048

    fun sampledOptions(bytes: ByteArray, maxEdge: Int): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return optionsFor(bounds.outWidth, bounds.outHeight, maxEdge)
    }

    fun optionsFor(width: Int, height: Int, maxEdge: Int): BitmapFactory.Options {
        val longestEdge = maxOf(width, height)
        var sampleSize = 1
        if (longestEdge > 0 && maxEdge > 0) {
            while (longestEdge / sampleSize > maxEdge && sampleSize <= Int.MAX_VALUE / 2) {
                sampleSize *= 2
            }
        }
        return BitmapFactory.Options().apply { inSampleSize = sampleSize }
    }

    /** Decodes [bytes] so that no edge exceeds [maxEdge]; null when unreadable. */
    fun decode(bytes: ByteArray, maxEdge: Int = DECODE_CEILING): Bitmap? =
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOptions(bytes, maxEdge))
        }.getOrNull()

    /**
     * Applies the EXIF orientation of [bytes] to [bitmap].
     *
     * A phone photo is usually stored sideways plus an orientation flag; without
     * this the thumbnail of a portrait shot shows up rotated (ADR-052 §4).
     */
    fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return rotateForOrientation(bitmap, orientation)
    }

    fun rotateForOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** Scales [bitmap] down so its longest edge is at most [maxEdge]. */
    fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= maxEdge || longestEdge <= 0) return bitmap
        val ratio = maxEdge.toFloat() / longestEdge.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bitmap, width, height, true) }
            .getOrDefault(bitmap)
    }

    fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        output.toByteArray()
    }

    /**
     * Builds the stored PNG thumbnail: orientation-corrected, scaled to
     * [THUMBNAIL_MAX_EDGE] and re-encoded.
     */
    fun thumbnailFromImageBytes(bytes: ByteArray): ByteArray? {
        val decoded = decode(bytes, maxEdge = DECODE_CEILING) ?: return null
        val upright = applyExifOrientation(bytes, decoded)
        val scaled = scaleToMaxEdge(upright, THUMBNAIL_MAX_EDGE)
        val png = encodePng(scaled)
        if (scaled !== upright) scaled.recycle()
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return png
    }

    private const val PNG_QUALITY = 100
}
