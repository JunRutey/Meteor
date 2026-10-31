package com.meteor.memory.ui.diary

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.ImageStore
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
    /** Localized `yyyy/MM/dd HH:mm` — the moment this record was last written. */
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
    private val imageStore: ImageStore,
    private val storageRouter: StorageRouter,
) : ViewModel() {

    private val date: LocalDate = LocalDate.parse(checkNotNull(savedStateHandle.get<String>(ARG_DATE)))

    private val _state = MutableStateFlow(
        DiaryEditorState(dateLabel = formatSavedAt(Instant.now()), text = ""),
    )
    val state: StateFlow<DiaryEditorState> = _state.asStateFlow()

    /** The record being edited; created lazily for a brand-new day. */
    private var document: Document = newDocument()

    /** Decrypted bitmaps for the images referenced by the current body. */
    private var images: Map<String, ImageBitmap> = emptyMap()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { diaryQueries.read(date) }
            if (loaded != null) document = loaded
            appLog.debug(TAG, "opened $date chars=${loaded?.body?.length ?: 0}")

            _state.value = _state.value.copy(
                dateLabel = formatSavedAt(document.timestamp),
                text = document.body,
                signedAtLabel = document.signedAt?.let(::formatSavedAt),
            )
            refreshImages(document.body)
        }
    }

    fun onTextChanged(text: String) {
        _state.value = _state.value.copy(text = text, saved = false, error = null)
    }

    /** Bitmap for a `meteor://image/<token>` reference, or null when unknown. */
    /** Bitmap for a `meteor://image/<token>` reference, or null when unknown. */
    fun imageFor(token: String): ImageBitmap? = images[token]

    fun insertImage(bytes: ByteArray) {
        // Base on what the user has typed right now, not on the stale document:
        // otherwise the inserted image would overwrite unsaved text.
        document = document.copy(body = _state.value.text)
        runCatching { editorModule.insertImage(document, bytes, personId = null) }
            .onSuccess { updated ->
                document = updated
                _state.value = _state.value.copy(text = updated.body, saved = false)
                refreshImages(updated.body)
            }
            .onFailure { appLog.warn(TAG, "insert image failed", it) }
    }

    fun insertLink(target: String, label: String?) {
        if (target.isBlank()) return
        // Same trap as insertImage: append to the live text, or the editor text
        // that has not been saved yet disappears.
        document = editorModule.insertLink(document.copy(body = _state.value.text), target, label)
        _state.value = _state.value.copy(text = document.body, saved = false)
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.isSaving) return
        _state.value = snapshot.copy(isSaving = true, error = null)

        viewModelScope.launch {
            // Writing refreshes the timestamp: the record shows when it was last
            // saved, which is what the editor label promises.
            val toSave = document.copy(body = snapshot.text, timestamp = Instant.now())
            val result = runCatching { editorModule.save(toSave) }
            result.fold(
                onSuccess = { appLog.info(TAG, "diary saved: $date") },
                onFailure = { appLog.error(TAG, "diary save failed: $date", it) },
            )

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
                dateLabel = stored?.let { formatSavedAt(it.timestamp) } ?: _state.value.dateLabel,
                signedAtLabel = stored?.signedAt?.let(::formatSavedAt) ?: _state.value.signedAtLabel,
            )
        }
    }

    /** Decodes every image referenced by [body] once, off the main thread. */
    private fun refreshImages(body: String) {
        val tokens = IMAGE_REF.findAll(body).map { it.groupValues[1] }.distinct().take(MAX_IMAGES).toList()
        if (tokens.isEmpty()) {
            images = emptyMap()
            return
        }

        viewModelScope.launch {
            images = withContext(Dispatchers.IO) {
                tokens.mapNotNull { token ->
                    val bytes = imageStore.load(token) ?: return@mapNotNull null
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        ?: return@mapNotNull null
                    token to bitmap.asImageBitmap()
                }.toMap()
            }
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

    private fun formatSavedAt(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)

    companion object {
        const val ARG_DATE = "date"
        private const val TAG = "DiaryEdit"
        private const val MAX_IMAGES = 20
        private val IMAGE_REF = Regex("""\]\(meteor://image/([^)]+)\)""")
    }
}
