package com.meteor.memory.ui.people

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AlbumRepository
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaOwner
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.PersonDeleteResult
import com.meteor.memory.core.storage.PersonProfile
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StorageLayout
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.editor.MarkdownEditing
import com.meteor.memory.feature.editor.MediaBlockState
import com.meteor.memory.feature.media.MediaImporter
import com.meteor.memory.feature.people.PersonMediaImportSummary
import com.meteor.memory.feature.people.PersonMediaRow
import com.meteor.memory.ui.common.decodeImages
import com.meteor.memory.ui.attachments.AttachmentImportCoordinator
import com.meteor.memory.ui.attachments.AttachmentImportSummary
import com.meteor.memory.ui.search.PersonRecord
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

data class PersonRecordView(
    val recordId: String,
    val type: RecordType,
    val dateLabel: String,
)

/**
 * The screen gets no `AlbumEntry`: `:feature-people` must not know the storage
 * layer's types, and the only thing the UI needs is the opaque ref plus the
 * badges. The row type itself lives in `:feature-people` next to the composable
 * that draws it, so the mapping stays in one place.
 */

data class PersonViewState(
    val displayName: String = "",
    val relation: Relation? = null,
    /** One-line reminder, shown right under the name. */
    val note: String = "",
    /** Markdown body (正文), rendered with images. */
    val notes: String = "",
    /** User-defined labels shown beside the structured relation. */
    val tags: List<String> = emptyList(),
    /** Decrypted images referenced by the notes, so the page can render them. */
    val images: Map<String, ImageBitmap> = emptyMap(),
    val records: List<PersonRecordView> = emptyList(),
    /**
     * Pictures, videos, audio and imported files owned by this person
     * (ADR-052 §5 E, ADR-065).
     */
    val media: List<PersonMediaRow> = emptyList(),
    val mediaError: Boolean = false,
    val importingMedia: Boolean = false,
    /** Last "add media" outcome; null until one finished. */
    val mediaImport: PersonMediaImportSummary? = null,
    /** Set when the last media deletion failed; cleared by the next attempt. */
    val mediaDeleteFailed: Boolean = false,
    /**
     * How `meteor://media/<id>` references in the body must be drawn.
     *
     * Resolved here because the editor cannot read the encrypted sidecar that
     * holds the kind (ADR-055). A reference that is not in the map yet is still
     * decrypting and renders its neutral frame.
     */
    val mediaBlocks: Map<String, MediaBlockState> = emptyMap(),
    val isImporting: Boolean = false,
    val importSummary: AttachmentImportSummary? = null,
    val deleting: Boolean = false,
    /** Set after the profile is gone; the host screen leaves the route. */
    val deleted: Boolean = false,
    val deleteFailed: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val personQueries: PersonQueries,
    private val searchService: SearchService,
    private val timeModule: TimeModule,
    private val imageStore: ImageStore,
    private val mediaStore: MediaStore,
    private val albumRepository: AlbumRepository,
    private val attachmentImportCoordinator: AttachmentImportCoordinator,
    private val appLog: AppLog,
) : ViewModel() {

    private val personId: String = checkNotNull(savedStateHandle.get<String>(ARG_PERSON_ID))

    private val _state = MutableStateFlow(PersonViewState())
    val state: StateFlow<PersonViewState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val locale = Locale.getDefault()
            val profile = withContext(Dispatchers.IO) { personQueries.read(personId) }
            val records = withContext(Dispatchers.IO) { searchService.recordsForPerson(personId) }
            // New media, legacy pictures and imported files in one list, newest
            // import first — the same merge the album shows, scoped to this
            // person (ADR-052 §3, ADR-065). Files land in the same strip because
            // the 记录 section that used to own them no longer exists.
            var mediaError = false
            val media = try {
                withContext(Dispatchers.IO) {
                    albumRepository.entries(MediaOwner.Person(personId)).map { entry ->
                        PersonMediaRow(
                            ref = entry.ref,
                            kind = entry.kind,
                            title = entry.fileName,
                            durationMs = entry.durationMs,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mediaError = true
                appLog.warn(TAG, "person media list failed", failure)
                emptyList()
            }

            _state.update {
                it.copy(
                    displayName = profile?.displayName ?: personId.take(SHORT_ID),
                    relation = profile?.relation,
                    note = profile?.note.orEmpty(),
                    notes = profile?.notes.orEmpty(),
                    tags = profile?.tags.orEmpty(),
                    records = records.map { record -> record.toView(locale) },
                    media = media,
                    mediaError = mediaError,
                    loaded = true,
                )
            }
            loadNoteImages(_state.value.notes)
            // Same resolver wiring as the editors: the body's media references
            // have to be resolved once, or their cards stay on the pending frame
            // forever (ADR-055, ADR-064).
            loadMediaBlocks(_state.value.notes)
        }
    }

    /**
     * Imports picked media under **this** person (ADR-052 §5 E).
     *
     * `MediaImporter` is synchronous and does all the decryption/thumbnail work
     * itself, so the whole batch runs on the IO dispatcher and the UI only sees
     * a counter flip. One unreadable file must not lose the rest of the batch.
     */
    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.importingMedia) return
        viewModelScope.launch {
            _state.update { it.copy(importingMedia = true, mediaImport = null) }
            val importer = MediaImporter(context.contentResolver, mediaStore)
            val summary = withContext(Dispatchers.IO) {
                var imported = 0
                var failed = 0
                uris.forEach { uri ->
                    val stored = runCatching { importer.import(uri, personId) }.getOrNull()
                    if (stored == null) {
                        failed++
                        appLog.warn(TAG, "media import failed: $uri")
                    } else {
                        imported++
                    }
                }
                PersonMediaImportSummary(imported = imported, failed = failed)
            }
            appLog.info(TAG, "person media import: ${summary.imported} ok, ${summary.failed} failed")
            _state.update { it.copy(importingMedia = false, mediaImport = summary) }
            load()
        }
    }

    /**
     * Deletes one media file after the UI's confirmation.
     *
     * Deliberately narrow, like `ImageStore.delete`: only the payload goes away.
     * Bodies that reference the file keep their Markdown and start rendering a
     * missing-media placeholder — nothing here rewrites a record (ADR-037 §3).
     */
    fun deleteMedia(ref: String) {
        viewModelScope.launch {
            _state.update { it.copy(mediaDeleteFailed = false) }
            val deleted = withContext(Dispatchers.IO) {
                runCatching { albumRepository.delete(ref) }.getOrElse { failure ->
                    appLog.warn(TAG, "media delete failed: $ref", failure)
                    false
                }
            }
            if (deleted) {
                _state.update { state ->
                    state.copy(media = state.media.filterNot { it.ref == ref })
                }
                appLog.info(TAG, "media deleted: $ref")
            } else {
                _state.update { it.copy(mediaDeleteFailed = true) }
            }
        }
    }

    fun imageFor(token: String): ImageBitmap? = _state.value.images[token]

    /** Synchronous lookup the body renderer calls for one `meteor://media/` id. */
    fun mediaBlockFor(id: String): MediaBlockState =
        _state.value.mediaBlocks[id] ?: MediaBlockState.Pending

    fun importAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.isImporting) return
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, importSummary = null) }
            val summary = attachmentImportCoordinator.importAll(uris, personId)
            _state.update { it.copy(isImporting = false, importSummary = summary) }
            load()
        }
    }

    /**
     * Deletes only the address-book entry. Diaries and imported attachments are
     * separate stores, so this cannot silently destroy unrelated memories.
     */
    fun delete() {
        val current = _state.value
        if (current.deleting || current.deleted) return
        _state.update { it.copy(deleting = true, deleteFailed = false) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { personQueries.delete(personId) }.getOrNull()
            }
            when (result) {
                PersonDeleteResult.DELETED, PersonDeleteResult.NOT_FOUND -> {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            searchService.removeRecord(StorageLayout.profileRelativePath(personId))
                        }.onFailure { appLog.warn(TAG, "person index removal failed: $personId", it) }
                    }
                    appLog.info(TAG, "person deleted: $personId result=$result")
                    _state.update { it.copy(deleting = false, deleted = true) }
                }

                else -> {
                    appLog.warn(TAG, "person delete failed: $personId result=$result")
                    _state.update { it.copy(deleting = false, deleteFailed = true) }
                }
            }
        }
    }

    private fun loadNoteImages(notes: String) {
        val tokens = MarkdownEditing.imagesOf(notes)
            .map { it.token }
            .filter { it !in _state.value.images }
        if (tokens.isEmpty()) return

        viewModelScope.launch {
            val decoded = decodeImages(imageStore, tokens)
            _state.update { it.copy(images = it.images + decoded.bitmaps) }
        }
    }

    private fun loadMediaBlocks(notes: String) {
        val ids = MarkdownEditing.mediaOf(notes)
            .map { it.id }
            .filter { it !in _state.value.mediaBlocks }
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val resolved = decodeMediaBlocks(mediaStore, ids)
            if (resolved.isNotEmpty()) {
                _state.update { it.copy(mediaBlocks = it.mediaBlocks + resolved) }
            }
        }
    }

    private fun PersonRecord.toView(locale: Locale): PersonRecordView {
        val date = timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        return PersonRecordView(
            recordId = recordId,
            type = type,
            dateLabel = timeModule.formatDate(date, locale),
        )
    }

    companion object {
        const val ARG_PERSON_ID = "personId"
        private const val SHORT_ID = 8
        private const val TAG = "PersonDetail"
    }
}

/** Fallback profile for a directory that exists but has no readable profile yet. */
internal fun PersonProfile?.displayOr(personId: String): String =
    this?.displayName ?: personId.take(8)
