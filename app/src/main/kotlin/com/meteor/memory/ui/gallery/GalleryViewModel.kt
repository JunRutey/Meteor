package com.meteor.memory.ui.gallery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AlbumRepository
import com.meteor.memory.core.storage.AlbumSortMode
import com.meteor.memory.feature.gallery.GalleryDay
import com.meteor.memory.feature.gallery.GalleryFilter
import com.meteor.memory.feature.gallery.GallerySortMode
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val appLog: AppLog,
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
                    val entries = albumRepository.entries()
                    val sorted = albumRepository.sorted(entries, mode)
                    val filtered = if (filterDate == null) {
                        sorted
                    } else {
                        sorted.filter { entry ->
                            entry.importedAt.atZone(zone).toLocalDate() == filterDate
                        }
                    }.take(MAX_ITEMS)
                    // Apply the kind filter in-memory: the repository's `entries`
                    // already returns all kinds; the chip narrows them here so
                    // the count-based pagination stays deterministic.
                    val byKind = if (kinds == null) filtered else filtered.filter { it.kind in kinds }
                    AlbumMapping.groupByDay(byKind, { date -> dayFormat.format(date) }, zone)
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
