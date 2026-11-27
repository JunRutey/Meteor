package com.meteor.memory.ui.gallery

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AlbumRepository
import com.meteor.memory.core.storage.AlbumSortMode
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.feature.gallery.GalleryDay
import com.meteor.memory.feature.gallery.GalleryFilter
import com.meteor.memory.feature.gallery.GallerySortMode
import com.meteor.memory.feature.media.MediaImporter
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.ui.attachments.AttachmentImportCoordinator
import com.meteor.memory.ui.attachments.AttachmentImportSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val albumRepository: AlbumRepository,
    private val settingsRepository: SettingsRepository,
    private val importCoordinator: AttachmentImportCoordinator,
    private val mediaStore: MediaStore,
    private val appLog: AppLog,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val filterDate: LocalDate? =
        savedStateHandle.get<String>(ARG_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private val _days = MutableStateFlow(emptyList<GalleryDay>())
    val days: StateFlow<List<GalleryDay>> = _days.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _filterLabel = MutableStateFlow<String?>(null)
    val filterLabel: StateFlow<String?> = _filterLabel.asStateFlow()

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    private val _sortMode = MutableStateFlow(GallerySortMode.IMPORT_DATE)
    val sortMode: StateFlow<GallerySortMode> = _sortMode.asStateFlow()

    /** True while a batch of imported files is being encrypted into the archive. */
    private val _importingFile = MutableStateFlow(false)
    val importingFile: StateFlow<Boolean> = _importingFile.asStateFlow()

    private val _importProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val importProgress: StateFlow<Pair<Int, Int>?> = _importProgress.asStateFlow()

    private val _importSummary = MutableStateFlow<AttachmentImportSummary?>(null)
    val importSummary: StateFlow<AttachmentImportSummary?> = _importSummary.asStateFlow()

    /** True while the device media picker's selection is being encrypted in. */
    private val _addingMedia = MutableStateFlow(false)
    val addingMedia: StateFlow<Boolean> = _addingMedia.asStateFlow()

    /** Done / total of the running media import; null when nothing is running. */
    private val _addMediaProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val addMediaProgress: StateFlow<Pair<Int, Int>?> = _addMediaProgress.asStateFlow()

    /** Imported / failed of the last media import; null until one finished. */
    private val _addMediaSummary = MutableStateFlow<Pair<Int, Int>?>(null)
    val addMediaSummary: StateFlow<Pair<Int, Int>?> = _addMediaSummary.asStateFlow()

    private var refreshInProgress = false
    private var refreshPending = false

    init {
        // Load the persisted sort choice, then scan.
        viewModelScope.launch {
            runCatching { settingsRepository.settings.first() }
                .onSuccess { settings ->
                    _sortMode.value = when (settings.albumSortMode) {
                        AlbumSortMode.SOURCE_DATE.name -> GallerySortMode.SOURCE_DATE
                        else -> GallerySortMode.IMPORT_DATE
                    }
                }
                .onFailure { error -> appLog.warn(TAG, "album sort preference unreadable", error) }
            refresh()
        }
    }

    fun setFilter(filter: GalleryFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        refresh()
    }

    fun setSortMode(mode: GallerySortMode) {
        if (_sortMode.value == mode) return
        _sortMode.value = mode
        viewModelScope.launch {
            runCatching {
                settingsRepository.setAlbumSortMode(AlbumMapping.toStorageSort(mode).name)
            }.onFailure { error -> appLog.warn(TAG, "album sort preference write failed", error) }
            refresh()
        }
    }

    /** Deletes the encrypted files behind [refs], then rebuilds the album. */
    fun delete(refs: List<String>) {
        if (refs.isEmpty()) return
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                refs.count { ref -> runCatching { albumRepository.delete(ref) }.getOrDefault(false) }
            }
            appLog.info(TAG, "deleted $removed/${refs.size} media")
            refresh()
        }
    }

    /**
     * Imports picked documents into the archive (ADR-065).
     *
     * This is the former 记录 page's import path, now reached from the album.
     * Files land unowned (`personId = null`); the person detail page imports
     * under its own person instead. The text index is refreshed by the
     * coordinator so search sees the new content immediately.
     */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _importingFile.value) return
        viewModelScope.launch {
            _importingFile.value = true
            _importProgress.value = 0 to uris.size
            _importSummary.value = null
            val summary = importCoordinator.importAll(
                uris = uris,
                personId = null,
            ) { done, total ->
                _importProgress.value = done to total
            }
            _importingFile.value = false
            _importProgress.value = null
            _importSummary.value = summary
            refresh()
        }
    }

    /** Clears the last import result once the banner was shown. */
    fun clearImportSummary() {
        _importSummary.value = null
    }

    /**
     * Imports device media (images / video / audio) into the archive (ADR-068).
     *
     * This is the album's own 「添加媒体」 entry: the files come from the system
     * picker and land unowned (`personId = null`), the same ownership the old
     * calendar 「存张记忆」 used. [MediaImporter] already skips a file whose hash is
     * in the archive, so re-picking a photo is not a duplicate.
     *
     * **Serial on purpose**: several large videos imported at once fight for IO
     * and for the media payload key, and the progress banner could not say how
     * far along a parallel batch is. One at a time also keeps peak memory at one
     * file. [MediaImporter.import] returns null per unreadable/unwanted file
     * instead of throwing, so one rejection does not abandon the rest of the batch.
     */
    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty() || _addingMedia.value) return
        viewModelScope.launch {
            _addingMedia.value = true
            _addMediaSummary.value = null
            _addMediaProgress.value = 0 to uris.size
            var imported = 0
            var failed = 0
            val completed = try {
                withContext(Dispatchers.IO) {
                    val importer = MediaImporter(context.contentResolver, mediaStore)
                    for ((index, uri) in uris.withIndex()) {
                        val stored = runCatching { importer.import(uri, personId = null) }
                            .onFailure { appLog.warn(TAG, "media import failed: $uri", it) }
                            .getOrNull()
                        if (stored != null) imported++ else failed++
                        _addMediaProgress.value = (index + 1) to uris.size
                    }
                }
                true
            } catch (cancelled: CancellationException) {
                // 页面被销毁时 viewModelScope 一起取消：批量导入到此为止，
                // 不再对外报告一个没跑完的结果。
                throw cancelled
            } catch (error: Exception) {
                appLog.warn(TAG, "media import batch failed", error)
                false
            } finally {
                _addingMedia.value = false
                _addMediaProgress.value = null
            }

            if (completed) {
                _addMediaSummary.value = imported to failed
                appLog.info(TAG, "media import finished: $imported ok, $failed failed")
            }
            refresh()
        }
    }

    /** Clears the last media-import result once the banner was dismissed. */
    fun clearAddMediaSummary() {
        _addMediaSummary.value = null
    }

    fun refresh() {
        if (refreshInProgress) {
            refreshPending = true
            return
        }
        refreshInProgress = true
        viewModelScope.launch {
            if (_days.value.isEmpty()) _loading.value = true
            try {
                val locale = Locale.getDefault()
                val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .withZone(ZoneId.systemDefault())
                _filterLabel.value = filterDate?.let { date ->
                    dayFormat.format(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
                }
                val zone = ZoneId.systemDefault()
                val mode = AlbumMapping.toStorageSort(_sortMode.value)
                val kinds = AlbumMapping.toKinds(_filter.value)
                val days = withContext(Dispatchers.IO) {
                    // Narrow by kind in the repository, then cap: taking the first
                    // MAX_ITEMS before the kind filter emptied the grid once the
                    // archive grew past the cap (the newest 120 items could all be
                    // images, leaving the 「文件」 chip with nothing to show).
                    val entries = albumRepository.entries(kinds = kinds)
                    val sorted = albumRepository.sorted(entries, mode)
                    val filtered = if (filterDate == null) {
                        sorted
                    } else {
                        sorted.filter { entry ->
                            entry.importedAt.atZone(zone).toLocalDate() == filterDate
                        }
                    }.take(MAX_ITEMS)
                    AlbumMapping.groupByDay(filtered, { date -> dayFormat.format(date) }, zone)
                }
                _days.value = days
                appLog.debug(TAG, "album loaded ${days.sumOf { it.items.size }} media")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLog.warn(TAG, "album refresh failed", error)
            } finally {
                refreshInProgress = false
                _loading.value = false
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }
        }
    }

    companion object {
        const val ARG_DATE = "date"
        const val TAG = "Gallery"
        const val MAX_ITEMS = 120
    }
}
