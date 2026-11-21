package com.meteor.memory.ui.gallery

import com.meteor.memory.core.storage.AlbumEntry
import com.meteor.memory.core.storage.AlbumKind
import com.meteor.memory.core.storage.AlbumSortMode
import com.meteor.memory.feature.gallery.GalleryDay
import com.meteor.memory.feature.gallery.GalleryFilter
import com.meteor.memory.feature.gallery.GalleryItem
import com.meteor.memory.feature.gallery.GalleryKind
import com.meteor.memory.feature.gallery.GallerySortMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure mapping between `core-storage` album types and the `feature-gallery` UI
 * model, plus the day-grouping logic.
 *
 * Everything here is side-effect free so it can be unit tested without Android.
 * `:app` is the only layer that knows `core-storage`, so `feature-gallery`
 * stays free of a storage dependency (ADR-052 §5「C」).
 */
object AlbumMapping {

    fun toUiKind(kind: AlbumKind): GalleryKind = when (kind) {
        AlbumKind.IMAGE -> GalleryKind.IMAGE
        AlbumKind.VIDEO -> GalleryKind.VIDEO
        AlbumKind.AUDIO -> GalleryKind.AUDIO
        AlbumKind.FILE -> GalleryKind.FILE
    }

    fun toStorageSort(mode: GallerySortMode): AlbumSortMode = when (mode) {
        GallerySortMode.SOURCE_DATE -> AlbumSortMode.SOURCE_DATE
        GallerySortMode.IMPORT_DATE -> AlbumSortMode.IMPORT_DATE
    }

    fun toUiSort(mode: AlbumSortMode): GallerySortMode = when (mode) {
        AlbumSortMode.SOURCE_DATE -> GallerySortMode.SOURCE_DATE
        AlbumSortMode.IMPORT_DATE -> GallerySortMode.IMPORT_DATE
    }

    fun toKinds(filter: GalleryFilter): Set<AlbumKind>? = when (filter) {
        GalleryFilter.ALL -> null
        GalleryFilter.IMAGES -> setOf(AlbumKind.IMAGE)
        GalleryFilter.VIDEOS -> setOf(AlbumKind.VIDEO)
        GalleryFilter.AUDIO -> setOf(AlbumKind.AUDIO)
        GalleryFilter.FILES -> setOf(AlbumKind.FILE)
    }

    fun toItem(entry: AlbumEntry): GalleryItem = GalleryItem(
        ref = entry.ref,
        kind = toUiKind(entry.kind),
        durationMs = entry.durationMs,
        fileName = entry.fileName,
        hasThumbnail = entry.hasThumbnail,
        sizeBytes = entry.sizeBytes,
    )

    /**
     * Groups [entries] (already sorted by the repository) into
     * `GalleryDay`s keyed by the import-date, newest first. This preserves the
     * existing day-grouped layout for every sort mode.
     */
    fun groupByDay(
        entries: List<AlbumEntry>,
        dayLabel: (LocalDate) -> String,
        zone: ZoneId,
    ): List<GalleryDay> {
        if (entries.isEmpty()) return emptyList()
        return entries
            .groupBy { it.importedAt.atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending<LocalDate> { it })
            .map { (date, dayEntries) ->
                GalleryDay(
                    label = dayLabel(date),
                    items = dayEntries.map(::toItem),
                )
            }
    }
}
