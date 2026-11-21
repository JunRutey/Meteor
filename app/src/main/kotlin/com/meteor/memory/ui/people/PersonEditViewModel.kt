package com.meteor.memory.ui.people

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.PersonProfileCodec
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.feature.editor.EditorModule
import com.meteor.memory.feature.editor.MarkdownEditing
import com.meteor.memory.feature.editor.MediaBlockState
import com.meteor.memory.feature.media.MediaImporter
import com.meteor.memory.feature.people.PeopleTagLibrary
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.ui.common.decodeImages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

data class PersonEditState(
    val displayName: String = "",
    val relation: Relation? = null,
    /** One-line reminder shown under the name on the profile page. */
    val note: String = "",
    /** Markdown body (正文) — the part that can carry images. */
    val notes: String = "",
    /** Free-form labels selected for this person. */
    val tags: List<String> = emptyList(),
    /** Remembered labels offered as selectable chips. */
    val customTags: List<String> = emptyList(),
    val images: Map<String, ImageBitmap> = emptyMap(),
    /** Referenced images whose bytes exist but produced no bitmap. */
    val missingImages: Set<String> = emptySet(),
    /**
     * How `meteor://media/<id>` references in the body must be drawn.
     *
     * Resolved by this view model because the editor cannot read the encrypted
     * metadata sidecar that holds the kind (ADR-052 §3, ADR-055).
     */
    val mediaBlocks: Map<String, MediaBlockState> = emptyMap(),
    val saving: Boolean = false,
    val insertingImage: Boolean = false,
    /**
     * Placeholder ids for media imports still running in the background (ADR-061).
     * Each renders as an "importing" card until the stored id replaces it.
     */
    val pendingMedia: Set<String> = emptySet(),
    val saved: Boolean = false,
    val error: String? = null,
) {
    /** True while any picked media is still being encrypted in the background. */
    val insertingMedia: Boolean get() = pendingMedia.isNotEmpty()
}

/**
 * Writes a person's profile back to `<person_id>/profile.md` (ADR-020).
 *
 * The notes field is a Markdown body, so it is edited through the same
 * `:feature-editor` surface as a diary entry: images go to
 * `数字遗产/图片/<person_id>/` and land in the body as `meteor://image/…`.
 */
@HiltViewModel
class PersonEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personQueries: PersonQueries,
    private val editorModule: EditorModule,
    private val imageStore: ImageStore,
    private val mediaStore: MediaStore,
    private val storageRouter: StorageRouter,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
    private val appLog: AppLog,
) : ViewModel() {

    private val personId: String = checkNotNull(savedStateHandle.get<String>(ARG_PERSON_ID))

    private val _state = MutableStateFlow(PersonEditState())
    val state: StateFlow<PersonEditState> = _state.asStateFlow()

    /** Images removed from the notes; their files go away only after a save. */
    private val pendingDeletes = mutableSetOf<String>()
    private var pendingImageInsertions = 0

    /** Serialises background media imports so several large files do not fight for IO (ADR-061). */
    private val importGate = Mutex()

    private data class ImportJob(val placeholder: String, val uri: Uri)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { personQueries.read(personId) }.getOrNull()
            }
            val customTags = withContext(Dispatchers.IO) {
                // ADR-045: the edit page can be the first people screen opened,
                // so it seeds the built-in labels too. Idempotent once for all.
                runCatching { settingsRepository.seedPeopleTags(defaultPeopleTags(context)) }
                    .onFailure { appLog.warn(TAG, "people tag seed failed", it) }
                val stored = runCatching { settingsRepository.settings.first().peopleTags }
                    .getOrDefault(emptySet<String>())
                // Remembered labels plus this profile's own labels. Other
                // people's tags stay out so a deleted chip cannot bounce back.
                PeopleTagLibrary.available(stored, profile?.tags.orEmpty())
            }
            _state.update {
                it.copy(
                    displayName = profile?.displayName ?: personId.take(SHORT_ID),
                    relation = profile?.relation,
                    note = profile?.note.orEmpty(),
                    notes = profile?.notes.orEmpty(),
                    tags = profile?.tags.orEmpty(),
                    customTags = customTags,
                )
            }
            refreshImages(_state.value.notes)
            refreshMediaBlocks(_state.value.notes)
        }
    }

    fun onNameChanged(value: String) {
        _state.update { it.copy(displayName = value, saved = false, error = null) }
    }

    fun onRelationChanged(value: Relation?) {
        _state.update { it.copy(relation = value, saved = false) }
    }

    fun onTagsChanged(value: List<String>) {
        _state.update { it.copy(tags = value, saved = false, error = null) }
    }

    /** Remembers a tag immediately and persists it for future edits. */
    fun addTag(raw: String) {
        val tag = PersonProfileCodec.normalizeTags(listOf(raw)).firstOrNull() ?: return
        _state.update { state ->
            state.copy(
                customTags = (state.customTags + tag).distinct().sorted(),
                saved = false,
            )
        }
        viewModelScope.launch {
            runCatching { settingsRepository.addPeopleTag(tag) }
                .onFailure { appLog.warn(TAG, "people tag persist failed", it) }
        }
    }

    /**
     * Deletes a tag from the remembered library and, when this person is using
     * it, from the current selection as well. Other profiles keep their saved
     * labels; the profile file only changes when the user saves.
     */
    fun removeTag(raw: String) {
        val tag = PersonProfileCodec.normalizeTags(listOf(raw)).firstOrNull() ?: return
        _state.update { state ->
            state.copy(
                customTags = state.customTags - tag,
                tags = state.tags - tag,
                saved = false,
            )
        }
        viewModelScope.launch {
            runCatching { settingsRepository.removePeopleTag(tag) }
                .onFailure { appLog.warn(TAG, "people tag delete failed", it) }
        }
    }

    fun onNoteChanged(value: String) {
        _state.update { it.copy(note = value, saved = false, error = null) }
    }

    fun onNotesChanged(value: String) {
        _state.update { it.copy(notes = value, saved = false, error = null) }
    }

    fun imageFor(token: String): ImageBitmap? = _state.value.images[token]

    /** Synchronous lookup the body renderer calls for one `meteor://media/` id. */
    fun mediaBlockFor(id: String): MediaBlockState =
        if (id in _state.value.pendingMedia) {
            MediaBlockState.Importing
        } else {
            _state.value.mediaBlocks[id] ?: MediaBlockState.Pending
        }

    fun insertImage(bytes: ByteArray) {
        val base = document()
        pendingImageInsertions++
        _state.update { it.copy(insertingImage = true, saved = false) }

        viewModelScope.launch {
            try {
                val inserted = withContext(Dispatchers.IO) {
                    editorModule.insertImage(base, bytes, personId)
                }
                val token = MarkdownEditing.imagesOf(inserted.body).lastOrNull()?.token
                if (token == null) {
                    appLog.warn(TAG, "insert image produced no image reference: $personId")
                    return@launch
                }

                val body = MarkdownEditing.appendImage(_state.value.notes, token)
                _state.update { it.copy(notes = body, saved = false) }
                refreshImages(body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLog.warn(TAG, "insert image failed: $personId", error)
            } finally {
                pendingImageInsertions--
                _state.update { it.copy(insertingImage = pendingImageInsertions > 0) }
            }
        }
    }

    /**
     * Encrypts picked media into **this person's** media folder and appends the
     * reference to the body (ADR-052 §5 D/E).
     *
     * Ownership never comes from the editor: the toolbar only reports the kinds
     * the user asked for, and this view model — which knows the person — decides
     * `personId`. `MediaImporter` does metadata, thumbnails and encryption in one
     * synchronous call, so the batch runs off the main thread and the toolbar
     * shows a progress label.
     *
     * A failed file is logged and skipped; the rest of the batch still lands, so
     * one unreadable video cannot throw away the pictures chosen with it.
     */
    /**
     * Starts importing picked media into **this person's** folder and inserts a
     * placeholder card for each immediately (ADR-061).
     *
     * Ownership never comes from the editor: the toolbar only reports the kinds
     * the user asked for, and this view model — which knows the person — decides
     * `personId`. Because the cards land before the encryption runs, a large video
     * never blocks editing or a second insert.
     */
    fun insertMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val jobs = uris.map { uri ->
            ImportJob(placeholder = UUID.randomUUID().toString().replace("-", ""), uri = uri)
        }
        var body = _state.value.notes
        jobs.forEach { job -> body = MarkdownEditing.appendMedia(body, job.placeholder) }
        _state.update {
            it.copy(
                notes = body,
                pendingMedia = it.pendingMedia + jobs.map { job -> job.placeholder },
                saved = false,
            )
        }
        jobs.forEach { job ->
            viewModelScope.launch {
                importGate.withLock {
                    val storedId = withContext(Dispatchers.IO) {
                        val importer = MediaImporter(context.contentResolver, mediaStore)
                        runCatching { importer.import(job.uri, personId) }
                            .onFailure { appLog.warn(TAG, "insert media failed: ${job.uri}", it) }
                            .getOrNull()
                            ?.id
                    }
                    finishMediaImport(job.placeholder, storedId)
                }
            }
        }
    }

    /** Swaps the placeholder for the stored id, or drops it when the import failed. */
    private fun finishMediaImport(placeholder: String, storedId: String?) {
        val current = _state.value.notes
        val updated = if (storedId != null) {
            MarkdownEditing.replaceMediaId(current, placeholder, storedId)
        } else {
            MarkdownEditing.removeMedia(current, placeholder)
        }
        _state.update {
            it.copy(
                notes = updated,
                pendingMedia = it.pendingMedia - placeholder,
                mediaBlocks = it.mediaBlocks - placeholder,
                saved = false,
            )
        }
        refreshMediaBlocks(updated)
        appLog.info(TAG, "person media import finished: ${storedId != null}")
    }

    /**
     * Appends references that were already imported by the standalone picker
     * route (ADR-059). The person edit screen owns the body, so only the
     * Markdown append happens here.
     */
    fun appendMediaIds(ids: List<String>) {
        if (ids.isEmpty()) return
        var body = _state.value.notes
        ids.forEach { id -> body = MarkdownEditing.appendMedia(body, id) }
        _state.update { it.copy(notes = body, saved = false) }
        refreshMediaBlocks(body)
    }

    fun insertLink(target: String, label: String?) {
        if (target.isBlank()) return
        val updated = editorModule.insertLink(document(), target, label)
        _state.update { it.copy(notes = updated.body, saved = false) }
    }

    /** Writes the description (alt text) of one image reference (ADR-049). */
    fun setImageCaption(token: String, caption: String) {
        val current = _state.value
        val body = MarkdownEditing.setImageCaption(current.notes, token, caption)
        if (body == current.notes) return
        _state.update { it.copy(notes = body, saved = false) }
    }

    /** Drops one image from the notes and schedules its file for deletion. */
    fun removeImage(token: String) {
        val current = _state.value
        val body = MarkdownEditing.removeImage(current.notes, token)
        if (body == current.notes) return

        pendingDeletes += token
        _state.update {
            it.copy(
                notes = body,
                images = it.images - token,
                missingImages = it.missingImages - token,
                saved = false,
            )
        }
        appLog.debug(TAG, "image detached, will delete on save: $token")
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.insertingImage || current.insertingMedia) return
        val name = current.displayName.trim()
        // The button is disabled for a blank name; guard anyway so a profile file
        // can never lose the one field PersonProfileCodec requires.
        if (name.isEmpty()) return

        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    personQueries.update(
                        personId = personId,
                        displayName = name,
                        relation = current.relation,
                        note = current.note.trim(),
                        notes = current.notes,
                        tags = current.tags,
                    )
                }
            }
            result.fold(
                onSuccess = { appLog.info(TAG, "person saved: $personId") },
                onFailure = { appLog.error(TAG, "person save failed: $personId", it) },
            )
            if (result.isSuccess) purgeDeletedImages()

            _state.update {
                it.copy(
                    saving = false,
                    saved = result.isSuccess,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

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
     * Resolves the media references the body currently carries.
     *
     * Called on load, after an insertion and after a reference is dropped, so
     * the editor's cards stay in step with the text without it ever touching
     * `MediaStore` itself (ADR-055).
     */
    private fun refreshMediaBlocks(body: String) {
        val ids = MarkdownEditing.mediaOf(body)
            .map { it.id }
            // An importing placeholder is not in the store yet (ADR-061).
            .filter { it !in _state.value.mediaBlocks && it !in _state.value.pendingMedia }
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val resolved = decodeMediaBlocks(mediaStore, ids)
            if (resolved.isNotEmpty()) {
                _state.update { it.copy(mediaBlocks = it.mediaBlocks + resolved) }
            }
        }
    }

    /** Shell document so the editor module can address this person's image folder. */
    private fun document(): Document = Document(
        file = storageRouter.route(personId, RecordType.PROFILE, LocalDate.now()),
        personId = personId,
        type = RecordType.PROFILE,
        timestamp = Instant.now(),
        source = RecordSource.USER,
        body = _state.value.notes,
    )

    companion object {
        const val ARG_PERSON_ID = "personId"
        private const val TAG = "PersonEdit"
        private const val SHORT_ID = 8
    }
}
