package com.meteor.memory.ui.chats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AttachmentStore
import com.meteor.memory.core.storage.PersonEntry
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.ui.attachments.AttachmentImportCoordinator
import com.meteor.memory.ui.attachments.AttachmentImportSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

data class ChatAttachmentRow(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val personName: String?,
    val dateLabel: String,
)

data class ChatsViewState(
    val people: List<PersonEntry> = emptyList(),
    val attachments: List<ChatAttachmentRow> = emptyList(),
    val selectedPersonId: String? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val isImporting: Boolean = false,
    val importProgress: Pair<Int, Int>? = null,
    val importSummary: AttachmentImportSummary? = null,
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentStore: AttachmentStore,
    private val personQueries: PersonQueries,
    private val timeModule: TimeModule,
    private val importCoordinator: AttachmentImportCoordinator,
    private val appLog: AppLog,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatsViewState())
    val state: StateFlow<ChatsViewState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun selectPerson(personId: String?) {
        if (personId == _state.value.selectedPersonId) return
        _state.value = _state.value.copy(selectedPersonId = personId)
        refresh()
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.isImporting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isImporting = true,
                importProgress = 0 to uris.size,
                importSummary = null,
            )
            val summary = importCoordinator.importAll(
                uris = uris,
                personId = _state.value.selectedPersonId,
            ) { done, total ->
                _state.value = _state.value.copy(importProgress = done to total)
            }
            _state.value = _state.value.copy(
                isImporting = false,
                importProgress = null,
                importSummary = summary,
            )
            refresh()
        }
    }

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

    fun delete(id: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { attachmentStore.delete(id) }
            }
            result.onFailure { appLog.warn(TAG, "attachment delete failed", it) }
            appLog.info(TAG, "attachment deleted=${result.getOrDefault(false)}")
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, loadFailed = false)
            try {
                val locale = Locale.getDefault()
                val (people, attachments) = withContext(Dispatchers.IO) {
                    val people = personQueries.list()
                    val names = people.associate { entry ->
                        entry.personId to (entry.displayName ?: entry.personId.take(SHORT_ID))
                    }
                    val attachments = attachmentStore.list(_state.value.selectedPersonId).map { attachment ->
                        val date = attachment.modifiedAt.atZone(ZoneId.systemDefault()).toLocalDate()
                        ChatAttachmentRow(
                            id = attachment.id,
                            fileName = attachment.fileName,
                            mimeType = attachment.mimeType,
                            sizeBytes = attachment.sizeBytes,
                            personName = attachment.personId?.let { names[it] ?: it.take(SHORT_ID) },
                            dateLabel = timeModule.formatDate(date, locale),
                        )
                    }
                    people to attachments
                }
                _state.value = _state.value.copy(
                    people = people,
                    attachments = attachments,
                    loading = false,
                    loadFailed = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.warn(TAG, "attachment records load failed", failure)
                _state.value = _state.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    private companion object {
        const val TAG = "Records"
        const val SHORT_ID = 8
    }
}
