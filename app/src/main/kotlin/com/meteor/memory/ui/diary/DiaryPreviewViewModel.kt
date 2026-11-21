package com.meteor.memory.ui.diary

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.feature.editor.MarkdownEditing
import com.meteor.memory.ui.common.decodeImages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

/** 只读预览，不写任何东西。 */
data class DiaryPreviewState(
    /** 日记所属的那天（文件名上的日期），页头主日期用它。 */
    val date: LocalDate,
    /** 写下这份记录的时刻（本地化）；记录还没写过时为空。 */
    val writtenAtLabel: String? = null,
    /** 补写：写于另一天。主日期只剩那天本身，写入时刻挪到「记录于」。 */
    val backfilled: Boolean = false,
    val body: String = "",
    val exists: Boolean = false,
    val images: Map<String, ImageBitmap> = emptyMap(),
    val missingImages: Set<String> = emptySet(),
    val loading: Boolean = true,
)

@HiltViewModel
class DiaryPreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val diaryQueries: DiaryQueries,
    private val imageStore: ImageStore,
    private val appLog: AppLog,
) : ViewModel() {

    private val date: LocalDate = LocalDate.parse(checkNotNull(savedStateHandle.get<String>(ARG_DATE)))

    private val _state = MutableStateFlow(DiaryPreviewState(date = date))
    val state: StateFlow<DiaryPreviewState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val document = withContext(Dispatchers.IO) { diaryQueries.read(date) }
            appLog.debug(TAG, "preview $date exists=${document != null}")
            val writtenAt = document?.let(DiaryDates::writtenAt)
            _state.update {
                it.copy(
                    writtenAtLabel = writtenAt?.let(::formatAt),
                    backfilled = writtenAt != null && DiaryDates.isBackfilled(date, writtenAt),
                    body = document?.body.orEmpty(),
                    exists = document != null,
                    loading = false,
                )
            }
            document?.let { refreshImages(it.body) }
        }
    }

    private fun refreshImages(body: String) {
        val known = _state.value.images.keys + _state.value.missingImages
        val tokens = MarkdownEditing.imagesOf(body).map { it.token }.filter { it !in known }
        if (tokens.isEmpty()) return
        viewModelScope.launch {
            val decoded = decodeImages(imageStore, tokens)
            _state.update {
                it.copy(
                    images = it.images + decoded.bitmaps,
                    missingImages = it.missingImages + decoded.missing,
                )
            }
        }
    }

    private fun formatAt(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)

    companion object {
        const val ARG_DATE = "date"
        private const val TAG = "DiaryPreview"
    }
}
