package com.meteor.memory.ui.diary

import androidx.compose.ui.graphics.ImageBitmap
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
import com.meteor.memory.feature.editor.MarkdownEditing
import com.meteor.memory.ui.common.decodeImages
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

data class DiaryEditorState(
    /** Localized `yyyy/MM/dd HH:mm` — the moment this record was last written. */
    val dateLabel: String,
    val text: String,
    /** Localized `meteor-sig` watermark, shown as metadata — never in the body. */
    val signedAtLabel: String? = null,
    /** Decrypted bitmaps for the images referenced by [text]. */
    val images: Map<String, ImageBitmap> = emptyMap(),
    /** Referenced images whose bytes exist but produced no bitmap. */
    val missingImages: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val isInsertingImage: Boolean = false,
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

    /**
     * Images the user removed from the body. Their files are deleted only after
     * a successful save — otherwise an abandoned edit would destroy pictures the
     * stored record still points at.
     */
    private val pendingDeletes = mutableSetOf<String>()
    private var pendingImageInsertions = 0

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { diaryQueries.read(date) }
            if (loaded != null) document = loaded
            appLog.debug(TAG, "opened $date chars=${loaded?.body?.length ?: 0}")

            _state.update {
                it.copy(
                    dateLabel = formatSavedAt(document.timestamp),
                    text = document.body,
                    signedAtLabel = document.signedAt?.let(::formatSavedAt),
                )
            }
            refreshImages(document.body)
        }
    }

    fun onTextChanged(text: String) {
        _state.update { it.copy(text = text, saved = false, error = null) }
    }

    /** Bitmap for a `meteor://image/<token>` reference, or null when unknown. */
    fun imageFor(token: String): ImageBitmap? = _state.value.images[token]

    fun insertImage(bytes: ByteArray) {
        val base = document.copy(body = _state.value.text)
        pendingImageInsertions++
        _state.update { it.copy(isInsertingImage = true, saved = false) }

        viewModelScope.launch {
            try {
                val inserted = withContext(Dispatchers.IO) {
                    editorModule.insertImage(base, bytes, personId = null)
                }
                val token = MarkdownEditing.imagesOf(inserted.body).lastOrNull()?.token
                if (token == null) {
                    appLog.warn(TAG, "insert image produced no image reference")
                    return@launch
                }

                // Merge into the latest text so typing while compression runs is preserved.
                val body = MarkdownEditing.appendImage(_state.value.text, token)
                document = inserted.copy(body = body)
                _state.update { it.copy(text = body, saved = false) }
                refreshImages(body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLog.warn(TAG, "insert image failed", error)
            } finally {
                pendingImageInsertions--
                _state.update { it.copy(isInsertingImage = pendingImageInsertions > 0) }
            }
        }
    }

    fun insertLink(target: String, label: String?) {
        if (target.isBlank()) return
        // Same trap as insertImage: append to the live text, or the editor text
        // that has not been saved yet disappears.
        document = editorModule.insertLink(document.copy(body = _state.value.text), target, label)
        _state.update { it.copy(text = document.body, saved = false) }
    }

    /** Drops one image from the body and schedules its file for deletion on save. */
    fun removeImage(token: String) {
        val current = _state.value
        val body = MarkdownEditing.removeImage(current.text, token)
        if (body == current.text) return

        pendingDeletes += token
        _state.update {
            it.copy(
                text = body,
                images = it.images - token,
                missingImages = it.missingImages - token,
                saved = false,
            )
        }
        appLog.debug(TAG, "image detached, will delete on save: $token")
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.isSaving || snapshot.isInsertingImage) return
        _state.update { it.copy(isSaving = true, error = null) }

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
            if (result.isSuccess) purgeDeletedImages()

            _state.update {
                it.copy(
                    isSaving = false,
                    saved = result.isSuccess,
                    error = result.exceptionOrNull()?.message,
                    dateLabel = stored?.let { doc -> formatSavedAt(doc.timestamp) } ?: it.dateLabel,
                    signedAtLabel = stored?.signedAt?.let(::formatSavedAt) ?: it.signedAtLabel,
                )
            }
        }
    }

    /** The record no longer references these tokens, so the ciphertext can go. */
    private suspend fun purgeDeletedImages() {
        if (pendingDeletes.isEmpty()) return
        val tokens = pendingDeletes.toList()
        pendingDeletes.clear()
        withContext(Dispatchers.IO) {
            tokens.forEach { token ->
                runCatching { imageStore.delete(token) }
                    .onSuccess { deleted -> appLog.info(TAG, "image deleted=$deleted $token") }
                    .onFailure { appLog.warn(TAG, "image delete failed: $token", it) }
            }
        }
    }

    /** Decodes any referenced image that is not loaded yet. */
    private fun refreshImages(body: String) {
        val known = _state.value.images.keys + _state.value.missingImages
        val tokens = MarkdownEditing.imagesOf(body)
            .map { it.token }
            .filter { it !in known }
        if (tokens.isEmpty()) return

        viewModelScope.launch {
            val decoded = decodeImages(imageStore, tokens)
            if (decoded.missing.isNotEmpty()) {
                appLog.warn(TAG, "images not decodable: ${decoded.missing.size}")
            }
            _state.update {
                it.copy(
                    images = it.images + decoded.bitmaps,
                    missingImages = it.missingImages + decoded.missing,
                )
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
    }
}
