package com.meteor.memory.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.ImageStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

data class GalleryThumbnail(
    val token: String,
    val bitmap: ImageBitmap,
    val caption: String,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val imageStore: ImageStore,
    private val appLog: AppLog,
) : ViewModel() {

    private val _items = MutableStateFlow(emptyList<GalleryThumbnail>())
    val items: StateFlow<List<GalleryThumbnail>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val locale = Locale.getDefault()
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())

            val decoded = withContext(Dispatchers.IO) {
                imageStore.list().take(MAX_ITEMS).mapNotNull { stored ->
                    val bytes = imageStore.load(stored.token) ?: return@mapNotNull null
                    // Half resolution is plenty for a 3-column grid.
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        ?: return@mapNotNull null
                    GalleryThumbnail(
                        token = stored.token,
                        bitmap = bitmap.asImageBitmap(),
                        caption = formatter.format(stored.modifiedAt),
                    )
                }
            }

            appLog.debug(TAG, "gallery loaded ${decoded.size} images")
            _items.value = decoded
            _loading.value = false
        }
    }

    private companion object {
        const val TAG = "Gallery"
        const val MAX_ITEMS = 60
    }
}
