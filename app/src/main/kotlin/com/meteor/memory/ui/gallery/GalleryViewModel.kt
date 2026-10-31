package com.meteor.memory.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.feature.gallery.GalleryDay
import com.meteor.memory.feature.gallery.GalleryItem
import dagger.hilt.android.lifecycle.HiltViewModel
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
        viewModelScope.launch {
            _loading.value = true
            val locale = Locale.getDefault()
            val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
            _filterLabel.value = filterDate?.let { date ->
                dayFormat.format(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
            }
            val zone = ZoneId.systemDefault()

            val decoded = withContext(Dispatchers.IO) {
                imageStore.list().take(MAX_ITEMS).mapNotNull { stored ->
                    val day = stored.modifiedAt.atZone(zone).toLocalDate()
                    if (filterDate != null && day != filterDate) return@mapNotNull null
                    val bytes = imageStore.load(stored.token) ?: return@mapNotNull null
                    // Half resolution is plenty for a 3-column grid.
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        ?: return@mapNotNull null
                    Decoded(
                        date = day,
                        item = GalleryItem(token = stored.token, bitmap = bitmap.asImageBitmap()),
                        label = dayFormat.format(stored.modifiedAt),
                    )
                }
            }

            appLog.debug(TAG, "gallery loaded ${decoded.size} images")
            _days.value = decoded
                .groupBy { it.date }
                .toSortedMap(compareByDescending<LocalDate> { it })
                .map { (_, entries) -> GalleryDay(label = entries.first().label, items = entries.map { it.item }) }
            _loading.value = false
        }
    }

    private class Decoded(val date: LocalDate, val item: GalleryItem, val label: String)

    companion object {
        const val ARG_DATE = "date"
        const val TAG = "Gallery"
        const val MAX_ITEMS = 120
    }
}
