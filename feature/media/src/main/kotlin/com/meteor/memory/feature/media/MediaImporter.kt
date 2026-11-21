package com.meteor.memory.feature.media

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaMetaInput
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.StoredMedia
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

/**
 * Imports one device file into the encrypted archive (ADR-052 §4).
 *
 * The pipeline is: resolve display name/MIME/size → decide the [MediaKind] (an
 * unknown MIME is refused, never guessed) → read the source date from EXIF or
 * `MediaMetadataRetriever` → derive a PNG thumbnail → hand everything to
 * [MediaStore.save].
 *
 * **Synchronous and UI-free**: the caller runs [import] on `Dispatchers.IO`.
 */
class MediaImporter(
    private val contentResolver: ContentResolver,
    private val mediaStore: MediaStore,
) {

    /**
     * Reads [uri], extracts metadata plus a thumbnail and stores both.
     *
     * Returns null when the file is not a media type Meteor understands, when the
     * provider cannot be read, or when the store rejects the write. Nothing is
     * half-written on failure: the store finalises atomically.
     */
    fun import(uri: Uri, personId: String?): StoredMedia? {
        val displayName = queryDisplayName(uri) ?: DEFAULT_FILE_NAME
        val declaredMime = contentResolver.getType(uri)
        val mimeType = declaredMime
            ?.takeIf { it.isNotBlank() }
            ?: MediaImportLogic.mimeTypeForFileName(displayName)
            ?: return null
        val kind = MediaImportLogic.kindForMimeType(mimeType) ?: return null
        val storeDate = queryStoreDate(uri)

        return when (kind) {
            MediaKind.IMAGE -> importImage(uri, displayName, mimeType, personId, storeDate)
            MediaKind.VIDEO, MediaKind.AUDIO ->
                importTimedMedia(uri, displayName, mimeType, kind, personId, storeDate)
        }
    }

    private fun importImage(
        uri: Uri,
        displayName: String,
        mimeType: String,
        personId: String?,
        storeDate: StoreDate,
    ): StoredMedia? {
        // Images are the one case where a whole in-memory read is right: both the
        // EXIF pass and the thumbnail need the pixels, and photos are small
        // relative to videos.
        val bytes = readBytes(uri) ?: return null
        val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull()
        val sourceDate = exif?.let { readExifSourceDate(it) } ?: storeDate.sourceDate
        val dimensions = exif?.let { readExifSize(it, bytes) }
        val thumbnail = runCatching { MediaBitmaps.thumbnailFromImageBytes(bytes) }.getOrNull()

        val meta = MediaMetaInput(
            kind = MediaKind.IMAGE,
            fileName = displayName,
            mimeType = mimeType,
            personId = personId,
            sourceDate = sourceDate,
            durationMs = null,
            width = dimensions?.first,
            height = dimensions?.second,
        )
        return runCatching {
            ByteArrayInputStream(bytes).use { input -> mediaStore.save(input, meta, thumbnail) }
        }.getOrNull()
    }

    private fun importTimedMedia(
        uri: Uri,
        displayName: String,
        mimeType: String,
        kind: MediaKind,
        personId: String?,
        storeDate: StoreDate,
    ): StoredMedia? {
        val probed = probeWithRetriever(uri, kind)
        val sourceDate = probed?.sourceDate ?: storeDate.sourceDate
        val thumbnail = if (kind == MediaKind.VIDEO) probed?.framePng else null

        val meta = MediaMetaInput(
            kind = kind,
            fileName = displayName,
            mimeType = mimeType,
            personId = personId,
            sourceDate = sourceDate,
            durationMs = probed?.durationMs,
            width = if (kind == MediaKind.VIDEO) probed?.width else null,
            height = if (kind == MediaKind.VIDEO) probed?.height else null,
        )
        // Streamed straight from the provider into the encrypted store: a 2 GB
        // video never lands in the heap.
        val input = runCatching { contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        return runCatching { input.use { source -> mediaStore.save(source, meta, thumbnail) } }.getOrNull()
    }

    private fun probeWithRetriever(uri: Uri, kind: MediaKind): Probed? {
        val retriever = MediaMetadataRetriever()
        val descriptor = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        return try {
            val opened = descriptor?.let { runCatching { retriever.setDataSource(it.fileDescriptor) }.isSuccess }
                ?: false
            if (!opened) return null

            val date = MediaImportLogic.parseRetrieverDate(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
            )
            val durationMs = MediaImportLogic.parseDurationMs(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
            )
            val width = MediaImportLogic.parseDimension(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
            )
            val height = MediaImportLogic.parseDimension(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            )
            val rotation = MediaImportLogic.parseDimension(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            ) ?: 0
            val oriented = MediaImportLogic.orientedVideoSize(width, height, rotation)

            val framePng = if (kind == MediaKind.VIDEO) {
                runCatching {
                    val frame = retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: return@runCatching null
                    val scaled = MediaBitmaps.scaleToMaxEdge(frame, MediaBitmaps.THUMBNAIL_MAX_EDGE)
                    val png = MediaBitmaps.encodePng(scaled)
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                    png
                }.getOrNull()
            } else {
                null
            }

            Probed(
                sourceDate = date,
                durationMs = durationMs,
                width = oriented?.first,
                height = oriented?.second,
                framePng = framePng,
            )
        } catch (failure: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { descriptor?.close() }
        }
    }

    private fun readExifSourceDate(exif: ExifInterface): Instant? =
        MediaImportLogic.sourceDateFromExif(
            dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            dateTimeDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
        )

    /**
     * Pixel size of the source image, orientation-corrected: the ExifInterface
     * tags describe the stored buffer, while the user sees the rotated view.
     * Falls back to bounds-decoding the source when the tags are missing.
     */
    private fun readExifSize(exif: ExifInterface, bytes: ByteArray): Pair<Int, Int>? {
        val tagWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        val tagHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        if (tagWidth > 0 && tagHeight > 0) return orientedPair(tagWidth, tagHeight, exif)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return orientedPair(bounds.outWidth, bounds.outHeight, exif)
    }

    private fun orientedPair(width: Int, height: Int, exif: ExifInterface): Pair<Int, Int> {
        val orientation = runCatching {
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return if (swapped) height to width else width to height
    }

    private fun readBytes(uri: Uri): ByteArray? =
        runCatching { contentResolver.openInputStream(uri)?.use(InputStream::readBytes) }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? {
        val name = queryColumn(uri, COLUMN_DISPLAY_NAME)
        return name?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
    }

    private fun queryStoreDate(uri: Uri): StoreDate {
        val projection = arrayOf(COLUMN_DATE_TAKEN, COLUMN_DATE_ADDED)
        return runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use StoreDate(null)
                val takenIndex = cursor.getColumnIndex(COLUMN_DATE_TAKEN)
                val addedIndex = cursor.getColumnIndex(COLUMN_DATE_ADDED)
                val taken = takenIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                val added = addedIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                StoreDate(MediaImportLogic.sourceDateFromMediaStore(taken, added))
            }
        }.getOrNull() ?: StoreDate(null)
    }

    private fun queryColumn(uri: Uri, column: String): String? = runCatching {
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
        }
    }.getOrNull()

    private data class StoreDate(val sourceDate: Instant?)

    private data class Probed(
        val sourceDate: Instant?,
        val durationMs: Long?,
        val width: Int?,
        val height: Int?,
        val framePng: ByteArray?,
    )

    private companion object {
        /** First keyframe: cheap, synchronous, and good enough for a cover. */
        const val FRAME_AT_US = 1_000_000L
        const val DEFAULT_FILE_NAME = "media"

        // Literals rather than `MediaStore.MediaColumns.*`, which only exists
        // from API 29 while minSdk is 26. These are the stable column names.
        const val COLUMN_DATE_TAKEN = "datetaken"
        const val COLUMN_DATE_ADDED = "date_added"
        const val COLUMN_DISPLAY_NAME = "_display_name"
    }
}
