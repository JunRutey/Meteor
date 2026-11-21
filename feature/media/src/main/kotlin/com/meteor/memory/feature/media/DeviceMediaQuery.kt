package com.meteor.memory.feature.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.meteor.memory.core.storage.MediaKind
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** A month's worth of device media, newest month first. */
internal data class DeviceMediaMonth(
    val yearMonth: YearMonth,
    val items: List<PickedMedia>,
)

/**
 * `MediaStore` queries behind [MediaPickerScreen].
 *
 * Kept separate from the composable so the cursor mapping — including the
 * `DATE_TAKEN` vs `DATE_ADDED` precedence and the older `DATA`-less volume API —
 * is one small, reviewable function.
 */
internal class DeviceMediaQuery(private val contentResolver: ContentResolver) {

    /**
     * Lists device media of [kinds], newest first.
     *
     * Every type is queried separately: the columns and the collection URI differ
     * per type, and a union projection would silently drop audio.
     */
    fun query(kinds: Set<MediaKind>, limitPerKind: Int = PER_KIND_LIMIT): List<PickedMedia> {
        val effective = kinds.ifEmpty { MediaKind.entries.toSet() }
        val all = mutableListOf<PickedMedia>()
        effective.forEach { kind ->
            all += runCatching { queryKind(kind, limitPerKind) }.getOrDefault(emptyList())
        }
        return all.sortedWith(
            compareByDescending<PickedMedia> { it.sourceDate ?: Instant.EPOCH }
                .thenByDescending { it.displayName },
        )
    }

    /** Groups [items] by source month for the picker's section headers. */
    fun months(items: List<PickedMedia>, zone: ZoneId = ZoneId.systemDefault()): List<DeviceMediaMonth> {
        val unknown = mutableListOf<PickedMedia>()
        val grouped = mutableMapOf<YearMonth, MutableList<PickedMedia>>()
        items.forEach { item ->
            val month = MediaImportLogic.monthBucket(item.sourceDate, zone)
            if (month == null) unknown += item else grouped.getOrPut(month) { mutableListOf() } += item
        }
        val ordered = grouped.entries
            .sortedByDescending { it.key }
            .map { (month, entries) -> DeviceMediaMonth(month, entries) }
            .toMutableList()
        // Files without any timestamp still need to be reachable; they go last
        // rather than being dropped.
        if (unknown.isNotEmpty()) ordered += DeviceMediaMonth(YearMonth.of(0, 1), unknown)
        return ordered
    }

    private fun queryKind(kind: MediaKind, limit: Int): List<PickedMedia> {
        val collection = collectionFor(kind)
        val projection = projectionFor(kind)
        val sortOrder = "$COLUMN_DATE_ADDED DESC"
        val items = mutableListOf<PickedMedia>()
        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            while (cursor.moveToNext() && items.size < limit) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val uri = ContentUris.withAppendedId(collection, id)
                val displayName = cursor.getStringOrNull(COLUMN_DISPLAY_NAME) ?: continue
                val mimeType = cursor.getStringOrNull(COLUMN_MIME_TYPE)
                    ?: MediaImportLogic.mimeTypeForFileName(displayName)
                    ?: continue
                val resolvedKind = MediaImportLogic.kindForMimeType(mimeType) ?: continue
                if (resolvedKind != kind) continue
                val sizeBytes = cursor.getLongOrNull(COLUMN_SIZE) ?: 0L
                val dateTaken = cursor.getLongOrNull(COLUMN_DATE_TAKEN)
                val dateAddedSeconds = cursor.getLongOrNull(COLUMN_DATE_ADDED)
                items += PickedMedia(
                    uri = uri,
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    kind = kind,
                    sourceDate = MediaImportLogic.sourceDateFromMediaStore(dateTaken, dateAddedSeconds),
                )
            }
        }
        return items
    }

    private fun collectionFor(kind: MediaKind): Uri = when (kind) {
        MediaKind.IMAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        MediaKind.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        MediaKind.AUDIO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun projectionFor(kind: MediaKind): Array<String> = if (kind == MediaKind.IMAGE) {
        arrayOf(
            COLUMN_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_MIME_TYPE,
            COLUMN_SIZE,
            COLUMN_DATE_TAKEN,
            COLUMN_DATE_ADDED,
        )
    } else {
        arrayOf(
            COLUMN_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_MIME_TYPE,
            COLUMN_SIZE,
            COLUMN_DATE_ADDED,
        )
    }

    private companion object {
        /** Enough to scroll a long while without loading an entire library. */
        const val PER_KIND_LIMIT = 600

        // Column names as literals instead of `MediaStore.MediaColumns.*`:
        // `MediaColumns` only exists from API 29 while minSdk is 26, and these
        // strings are the stable `OpenableColumns`/`MediaStore` contract anyway.
        const val COLUMN_ID = "_id"
        const val COLUMN_DISPLAY_NAME = "_display_name"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_SIZE = "_size"
        const val COLUMN_DATE_ADDED = "date_added"
        const val COLUMN_DATE_TAKEN = "datetaken"
    }
}

private fun android.database.Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}
