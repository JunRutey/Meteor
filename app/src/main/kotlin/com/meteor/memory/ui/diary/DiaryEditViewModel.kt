package com.meteor.memory.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.time.TimeModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

data class DiaryEditorState(
    val dateLabel: String,
    val text: String,
    /** Localized `meteor-sig` watermark, shown as metadata — never in the body. */
    val signedAtLabel: String? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DiaryEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val diaryQueries: DiaryQueries,
    private val timeModule: TimeModule,
    private val appLog: AppLog,
) : ViewModel() {

    private val date: LocalDate = LocalDate.parse(checkNotNull(savedStateHandle.get<String>(ARG_DATE)))

    private val _state = MutableStateFlow(
        DiaryEditorState(
            dateLabel = timeModule.formatDate(date, Locale.getDefault()),
            text = "",
        ),
    )
    val state: StateFlow<DiaryEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val document = withContext(Dispatchers.IO) { diaryQueries.read(date) }
            _state.value = _state.value.copy(
                text = document?.body.orEmpty(),
                signedAtLabel = document?.signedAt?.let(::formatStamp),
            )
        }
    }

    fun onTextChanged(text: String) {
        _state.value = _state.value.copy(text = text, saved = false, error = null)
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.isSaving) return
        _state.value = snapshot.copy(isSaving = true, error = null)

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { diaryQueries.save(date, snapshot.text) }
            }
            result.fold(
                onSuccess = { appLog.info(TAG, "diary saved: $date") },
                onFailure = { appLog.error(TAG, "diary save failed: $date", it) },
            )

            // Re-read so the watermark reflects the stamp actually written.
            val stored = if (result.isSuccess) {
                withContext(Dispatchers.IO) { runCatching { diaryQueries.read(date) }.getOrNull() }
            } else {
                null
            }

            _state.value = _state.value.copy(
                isSaving = false,
                saved = result.isSuccess,
                error = result.exceptionOrNull()?.message,
                signedAtLabel = stored?.signedAt?.let(::formatStamp) ?: _state.value.signedAtLabel,
            )
        }
    }

    private fun formatStamp(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)

    companion object {
        const val ARG_DATE = "date"
        private const val TAG = "DiaryEdit"
    }
}
