package com.meteor.memory.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.StoredImage
import com.meteor.memory.feature.gallery.GalleryDay
import com.meteor.memory.feature.gallery.GalleryItem
import com.meteor.memory.ui.common.sampledImageOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val imageStore: ImageStore,
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

    private var refreshInProgress = false
    private var refreshPending = false
    private var loadedSnapshot: List<StoredImage>? = null

    init {
        refresh()
    }

    /** Deletes the encrypted files behind [tokens], then rebuilds the album. */
    fun delete(tokens: List<String>) {
        if (tokens.isEmpty()) return
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                tokens.count { token ->
                    runCatching { imageStore.delete(token) }.getOrDefault(false)
                }
            }
            appLog.info(TAG, "deleted $removed/${tokens.size} images")
            refresh()
        }
    }

    fun refresh() {
        // A scan is already running: remember that another one is wanted instead
        // of dropping the request. Dropping it would leave the grid stale — for
        // example when the album is resumed, or an image is deleted, while the
        // first scan is still decoding.
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
                val snapshot = withContext(Dispatchers.IO) {
                    imageStore.list().take(MAX_ITEMS)
                }
                if (loadedSnapshot == snapshot) return@launch

                val decoded = withContext(Dispatchers.IO) {
                    snapshot.mapNotNull { stored ->
                        val day = stored.modifiedAt.atZone(zone).toLocalDate()
                        if (filterDate != null && day != filterDate) return@mapNotNull null
                        val bytes = imageStore.load(stored.token) ?: return@mapNotNull null
                        val options = sampledImageOptions(bytes, THUMBNAIL_MAX_EDGE)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            ?: return@mapNotNull null
                        Decoded(
                            date = day,
                            item = GalleryItem(token = stored.token, bitmap = bitmap.asImageBitmap()),
                            label = dayFormat.format(stored.modifiedAt),
                        )
                    }
                }

                loadedSnapshot = snapshot
                appLog.debug(TAG, "gallery loaded ${decoded.size} images")
                _days.value = decoded
                    .groupBy { it.date }
                    .toSortedMap(compareByDescending<LocalDate> { it })
                    .map { (_, entries) ->
                        GalleryDay(label = entries.first().label, items = entries.map { it.item })
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLog.warn(TAG, "gallery refresh failed", error)
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

    private class Decoded(val date: LocalDate, val item: GalleryItem, val label: String)

    companion object {
        const val ARG_DATE = "date"
        const val TAG = "Gallery"
        const val MAX_ITEMS = 120
        private const val THUMBNAIL_MAX_EDGE = 384
    }
}
