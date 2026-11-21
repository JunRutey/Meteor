package com.meteor.memory.ui.diary

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.R
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.editor.EditorModule
import com.meteor.memory.feature.editor.MarkdownEditing
import com.meteor.memory.feature.editor.MediaBlockState
import com.meteor.memory.feature.media.MediaImporter
import com.meteor.memory.feature.media.PickedMedia
import com.meteor.memory.ui.common.decodeImages
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class DiaryEditorState(
    val text: String,
    /**
     * 「记录于」的内容：这份记录写下的时刻（本地化）。记录保存前为空——
     * 还没有「记录于」可言。主日期一直由页头的 [date] 承担，不再在小字里
     * 重复一遍日期（v1.4 反馈：保存后小字日期重复）。
     */
    val recordedAtLabel: String? = null,
    /** Decrypted bitmaps for the images referenced by [text]. */
    val images: Map<String, ImageBitmap> = emptyMap(),
    /** Referenced images whose bytes exist but produced no bitmap. */
    val missingImages: Set<String> = emptySet(),
    /**
     * How each `meteor://media/<id>` reference in [text] must be drawn (ADR-055).
     *
     * The kind lives in the encrypted sidecar, so it is resolved here, once, and
     * handed to the editor as a plain map. An id missing from this map is still
     * being resolved and renders as the neutral pending frame.
     */
    val mediaBlocks: Map<String, MediaBlockState> = emptyMap(),
    val isSaving: Boolean = false,
    val isInsertingImage: Boolean = false,
    /**
     * The ADR-052 import currently running, if any (ADR-055).
     *
     * Holds the kind being imported so the header can name it (图片 / 视频 / 音频)
     * instead of showing a generic line; null means nothing is running. While it is
     * non-null, repeated insert taps and saving are both disabled.
     */
    val insertingMedia: MediaKind? = null,
    /**
     * Placeholder ids inserted for imports still running in the background
     * (ADR-061). Each one is already a `meteor://media/<placeholder>` line, so the
     * editor shows an "importing" card and keeps accepting input; the stored id
     * replaces it when the import finishes.
     */
    val pendingMedia: Set<String> = emptySet(),
    val saved: Boolean = false,
    val error: String? = null,
    /** 记录所属日期，用于页头「11 月 7 日 · 周六」标题。 */
    val date: LocalDate = LocalDate.now(),
) {
    /** True while any insert — the legacy image path or a background media import — is running. */
    val isInserting: Boolean get() = isInsertingImage || pendingMedia.isNotEmpty()
}

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
    private val mediaStore: MediaStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val date: LocalDate = LocalDate.parse(checkNotNull(savedStateHandle.get<String>(ARG_DATE)))

    private val _state = MutableStateFlow(DiaryEditorState(text = "", date = date))
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

    /**
     * Serialises background media imports (ADR-061): several large videos should
     * not fight for IO, and the editor stays responsive while they run.
     */
    private val importGate = Mutex()

    private data class ImportJob(val placeholder: String, val uri: Uri)

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { diaryQueries.read(date) }
            if (loaded != null) document = loaded
            appLog.debug(TAG, "opened $date chars=${loaded?.body?.length ?: 0}")

            _state.update {
                it.copy(
                    text = document.body,
                    recordedAtLabel = loaded?.let { doc -> formatSavedAt(DiaryDates.writtenAt(doc)) },
                )
            }
            refreshImages(document.body)
            refreshMedia(document.body)
        }
    }

    fun onTextChanged(text: String) {
        _state.update { it.copy(text = text, saved = false, error = null) }
        // Pasting a media reference (or undoing a removal) must produce a card, not
        // a pending frame that never resolves.
        refreshMedia(text)
    }

    /** Bitmap for a `meteor://image/<token>` reference, or null when unknown. */
    fun imageFor(token: String): ImageBitmap? = _state.value.images[token]

    /**
     * How to draw one `meteor://media/<id>` reference (ADR-055).
     *
     * Unresolved ids answer [MediaBlockState.Pending], which is exactly what the
     * editor shows while the metadata is still being read — the same frame it uses
     * when no resolver is installed at all. A placeholder whose import is still
     * running answers [MediaBlockState.Importing] instead (ADR-061).
     */
    fun mediaState(id: String): MediaBlockState =
        if (id in _state.value.pendingMedia) {
            MediaBlockState.Importing
        } else {
            _state.value.mediaBlocks[id] ?: MediaBlockState.Pending
        }

    /** Writes the description (alt text / title) of one media reference. */
    fun setMediaCaption(id: String, caption: String) {
        val current = _state.value
        val body = MarkdownEditing.setMediaCaption(current.text, id, caption)
        if (body == current.text) return
        _state.update { it.copy(text = body, saved = false) }
    }

    /**
     * Drops one media reference from the body (ADR-055).
     *
     * Only the Markdown goes: the encrypted file stays in the archive and remains
     * reachable from the album, so removing a card is not the same destructive act
     * as deleting the picture. That mirrors ADR-052's deletion rule (referencing
     * places never rewrite each other) and keeps this editor from deleting a file
     * the album or another record may still show.
     */
    fun removeMedia(id: String) {
        val current = _state.value
        val body = MarkdownEditing.removeMedia(current.text, id)
        if (body == current.text) return
        _state.update {
            it.copy(text = body, mediaBlocks = it.mediaBlocks - id, saved = false)
        }
        appLog.debug(TAG, "media detached from body: $id")
    }

    /**
     * Appends references that were already imported by the standalone picker
     * route (ADR-059), keeping the editor free of import responsibilities.
     */
    fun appendMediaIds(ids: List<String>) {
        if (ids.isEmpty()) return
        var body = _state.value.text
        ids.forEach { id -> body = MarkdownEditing.appendMedia(body, id) }
        document = document.copy(body = body)
        _state.update { it.copy(text = body, saved = false) }
        refreshMedia(body)
    }

    /**
     * Imports URIs from the platform document picker (ADR-059, "system" mode)
     * through the same background placeholder path as the in-app picker (ADR-061).
     */
    fun importMediaUris(uris: List<Uri>) {
        enqueueMediaImport(uris)
    }

    /**
     * Starts importing [uris] in the background and inserts a placeholder card for
     * each immediately (ADR-061).
     *
     * This is the whole point of the placeholder: a multi-hundred-megabyte video
     * must not freeze the editor or block a second insert. The user can keep
     * typing, pick another file, and watch each card resolve on its own. Imports
     * are serialised on [importGate] so several large files do not fight for IO.
     */
    fun enqueueMediaImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val jobs = uris.map { uri ->
            ImportJob(placeholder = UUID.randomUUID().toString().replace("-", ""), uri = uri)
        }
        var body = _state.value.text
        jobs.forEach { job -> body = MarkdownEditing.appendMedia(body, job.placeholder) }
        document = document.copy(body = body)
        _state.update {
            it.copy(
                text = body,
                pendingMedia = it.pendingMedia + jobs.map { job -> job.placeholder },
                saved = false,
                error = null,
            )
        }
        jobs.forEach { job ->
            viewModelScope.launch {
                importGate.withLock {
                    val storedId = withContext(Dispatchers.IO) {
                        val importer = MediaImporter(context.contentResolver, mediaStore)
                        runCatching { importer.import(job.uri, personId = null) }
                            .onFailure { appLog.warn(TAG, "media import failed: ${job.uri}", it) }
                            .getOrNull()
                            ?.id
                    }
                    finishMediaImport(job.placeholder, storedId)
                }
            }
        }
    }

    /**
     * Swaps the placeholder for the stored id, or drops the line when the import
     * failed, so no reference is ever left pointing at a temp id.
     */
    private fun finishMediaImport(placeholder: String, storedId: String?) {
        val current = _state.value.text
        val updated = if (storedId != null) {
            MarkdownEditing.replaceMediaId(current, placeholder, storedId)
        } else {
            MarkdownEditing.removeMedia(current, placeholder)
        }
        document = document.copy(body = updated)
        _state.update {
            it.copy(
                text = updated,
                pendingMedia = it.pendingMedia - placeholder,
                mediaBlocks = it.mediaBlocks - placeholder,
                saved = false,
                error = if (storedId == null) {
                    context.getString(R.string.diary_media_import_failed)
                } else {
                    it.error
                },
            )
        }
        refreshMedia(updated)
        appLog.info(TAG, "media import finished: ${storedId != null}")
    }

    /** Legacy entry point: the old in-editor picker already picked the files. */
    fun importMedia(picked: List<PickedMedia>) {
        enqueueMediaImport(picked.map { it.uri })
    }

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

    /** Writes the description (alt text) of one image reference (ADR-049). */
    fun setImageCaption(token: String, caption: String) {
        val current = _state.value
        val body = MarkdownEditing.setImageCaption(current.text, token, caption)
        if (body == current.text) return
        _state.update { it.copy(text = body, saved = false) }
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
        if (snapshot.isSaving || snapshot.isInserting) return
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
                    recordedAtLabel = stored?.let { doc -> formatSavedAt(DiaryDates.writtenAt(doc)) }
                        ?: it.recordedAtLabel,
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

    /**
     * Resolves the kind — and, for pictures, the bitmap — of every
     * `meteor://media/<id>` reference that is not known yet (ADR-055).
     *
     * Cheap on the common path: a body without the media scheme returns before any
     * regex runs, so typing in a text-only record costs nothing, and an already
     * resolved id is never read twice.
     */
    private fun refreshMedia(body: String) {
        if (!body.contains(MediaStore.MEDIA_SCHEME)) return
        val known = _state.value.mediaBlocks.keys
        val ids = MarkdownEditing.mediaOf(body)
            .map { it.id }
            // Placeholders are inserted before their import starts; resolving one
            // against the store would only mark it "missing" (ADR-061).
            .filter { it !in known && it !in _state.value.pendingMedia }
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val states = decodeMediaStates(mediaStore, ids)
            _state.update { it.copy(mediaBlocks = it.mediaBlocks + states) }
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
