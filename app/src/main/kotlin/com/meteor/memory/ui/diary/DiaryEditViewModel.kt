package com.meteor.memory.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.editor.EditorModule
import com.meteor.memory.ui.search.SearchService
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
    private val searchService: SearchService,
    private val editorModule: EditorModule,
    private val storageRouter: StorageRouter,
) : ViewModel() {

    private val date: LocalDate = LocalDate.parse(checkNotNull(savedStateHandle.get<String>(ARG_DATE)))

    private val _state = MutableStateFlow(
        DiaryEditorState(
            dateLabel = timeModule.formatDate(date, Locale.getDefault()),
            text = "",
        ),
    )
    val state: StateFlow<DiaryEditorState> = _state.asStateFlow()

    /** The record being edited; created lazily for a brand-new day. */
    private var document: Document = newDocument()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { diaryQueries.read(date) }
            if (loaded != null) document = loaded
            appLog.debug(TAG, "opened $date chars=${loaded?.body?.length ?: 0}")
            _state.value = _state.value.copy(
                text = document.body,
                signedAtLabel = document.signedAt?.let(::formatStamp),
            )
        }
    }

    fun onTextChanged(text: String) {
        _state.value = _state.value.copy(text = text, saved = false, error = null)
    }

    /** Compresses + encrypts the image and appends its Markdown reference. */
    fun insertImage(bytes: ByteArray) {
        runCatching { editorModule.insertImage(document, bytes, personId = null) }
            .onSuccess { updated ->
                document = updated
                _state.value = _state.value.copy(text = updated.body, saved = false)
            }
            .onFailure { appLog.warn(TAG, "insert image failed", it) }
    }

    fun insertLink(target: String, label: String?) {
        if (target.isBlank()) return
        document = editorModule.insertLink(document, target, label)
        _state.value = _state.value.copy(text = document.body, saved = false)
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.isSaving) return
        _state.value = snapshot.copy(isSaving = true, error = null)

        viewModelScope.launch {
            val toSave = document.copy(body = snapshot.text)
            val result = runCatching { editorModule.save(toSave) }
            result.fold(
                onSuccess = { appLog.info(TAG, "diary saved: $date") },
                onFailure = { appLog.error(TAG, "diary save failed: $date", it) },
            )

            // Re-read so the watermark reflects the stamp actually written, and
            // push the fresh plaintext into the index (archive §9).
            val stored = if (result.isSuccess) {
                withContext(Dispatchers.IO) { runCatching { diaryQueries.read(date) }.getOrNull() }
            } else {
                null
            }
            if (stored != null) {
                document = stored
                runCatching { searchService.indexDocument(stored) }
                    .onFailure { appLog.warn(TAG, "index update failed", it) }
            }

            _state.value = _state.value.copy(
                isSaving = false,
                saved = result.isSuccess,
                error = result.exceptionOrNull()?.message,
                signedAtLabel = stored?.signedAt?.let(::formatStamp) ?: _state.value.signedAtLabel,
            )
        }
    }

    private fun newDocument(): Document = Document(
        file = storageRouter.route(null, RecordType.DIARY, date),
        personId = null,
        type = RecordType.DIARY,
        timestamp = Instant.now(),
        source = RecordSource.USER,
        body = "",
    )

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
