package com.meteor.memory.ui.people

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AttachmentStore
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.PersonProfile
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.editor.MarkdownEditing
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

data class PersonAttachmentView(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val dateLabel: String,
    val sizeBytes: Long,
)

data class PersonViewState(
    val displayName: String = "",
    val relation: Relation? = null,
    /** One-line reminder, shown right under the name. */
    val note: String = "",
    /** Markdown body (正文), rendered with images. */
    val notes: String = "",
    /** Decrypted images referenced by the notes, so the page can render them. */
    val images: Map<String, ImageBitmap> = emptyMap(),
    val records: List<PersonRecordView> = emptyList(),
    val attachments: List<PersonAttachmentView> = emptyList(),
    val attachmentsError: Boolean = false,
    val isImporting: Boolean = false,
    val importSummary: AttachmentImportSummary? = null,
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
    private val attachmentStore: AttachmentStore,
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
            var attachmentsError = false
            val attachments = try {
                withContext(Dispatchers.IO) { attachmentStore.list(personId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                attachmentsError = true
                appLog.warn(TAG, "person attachment list failed", failure)
                emptyList()
            }

            _state.update {
                it.copy(
                    displayName = profile?.displayName ?: personId.take(SHORT_ID),
                    relation = profile?.relation,
                    note = profile?.note.orEmpty(),
                    notes = profile?.notes.orEmpty(),
                    records = records.map { record -> record.toView(locale) },
                    attachments = attachments.map { attachment ->
                        PersonAttachmentView(
                            id = attachment.id,
                            fileName = attachment.fileName,
                            mimeType = attachment.mimeType,
                            dateLabel = timeModule.formatDate(
                                attachment.modifiedAt.atZone(ZoneId.systemDefault()).toLocalDate(),
                                locale,
                            ),
                            sizeBytes = attachment.sizeBytes,
                        )
                    },
                    attachmentsError = attachmentsError,
                    loaded = true,
                )
            }
            loadNoteImages(_state.value.notes)
        }
    }

    fun imageFor(token: String): ImageBitmap? = _state.value.images[token]

    fun export(id: String, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        attachmentStore.copyDecrypted(id, out)
                    } ?: error("cannot open export destination")
                }
            }
            result.fold(
                onSuccess = { bytes -> appLog.info(TAG, "attachment exported: $bytes bytes") },
                onFailure = { appLog.warn(TAG, "attachment export failed", it) },
            )
        }
    }

    fun importAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.isImporting) return
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, importSummary = null) }
            val summary = attachmentImportCoordinator.importAll(uris, personId)
            _state.update { it.copy(isImporting = false, importSummary = summary) }
            load()
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
