package com.meteor.memory.ui.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.storage.StoredAiConversation
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AiChatStore
import com.meteor.memory.core.storage.PersonEntry
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.StoredChatCitation
import com.meteor.memory.core.storage.StoredChatFile
import com.meteor.memory.core.storage.StoredChatMessage
import com.meteor.memory.feature.ai.AiChatBubble
import com.meteor.memory.feature.ai.AiChatListener
import com.meteor.memory.feature.ai.AiChatMode
import com.meteor.memory.feature.ai.AiCitation
import com.meteor.memory.feature.ai.AiFileReference
import com.meteor.memory.feature.ai.AiFiles
import com.meteor.memory.feature.ai.AiModule
import com.meteor.memory.feature.ai.AiPresets
import com.meteor.memory.feature.ai.AiProviderException
import com.meteor.memory.feature.ai.AiSkill
import com.meteor.memory.feature.ai.AiProcessStep
import com.meteor.memory.feature.ai.AiServerToolEvent
import com.meteor.memory.feature.ai.AiToolApproval
import com.meteor.memory.feature.ai.AiUsage
import com.meteor.memory.feature.ai.ArchiveTools
import com.meteor.memory.feature.ai.AiConversationItem
import com.meteor.memory.feature.ai.ChatRequest
import com.meteor.memory.feature.ai.ChatTurn
import com.meteor.memory.feature.ai.SkillStage
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal fun isSuccessfulSearchableWrite(name: String, result: String): Boolean {
    val successKey = when (name) {
        ArchiveTools.TOOL_SAVE_DIARY,
        ArchiveTools.TOOL_APPEND_DIARY,
        ArchiveTools.TOOL_SET_MOOD -> "saved"

        ArchiveTools.TOOL_CREATE_PERSON -> "created"
        ArchiveTools.TOOL_UPDATE_PERSON -> "updated"
        ArchiveTools.TOOL_DELETE_PERSON -> "deleted"
        else -> return false
    }
    return try {
        JSONObject(result).optBoolean(successKey)
    } catch (_: JSONException) {
        false
    }
}

/** Why the chat is unavailable. Mapped to user copy by the UI layer. */
enum class AiBlocked { DISABLED, NEEDS_CONSENT, NEEDS_KEY, NEEDS_CONFIG }

data class AiUiState(
    val skills: List<AiSkill> = emptyList(),
    val selectedSkill: String? = null,
    val people: List<PersonEntry> = emptyList(),
    val selectedPersonId: String? = null,
    val personaDistilled: Boolean = false,
    val messages: List<AiChatBubble> = emptyList(),
    val busy: Boolean = false,
    val errorCode: Int = 0,
    val errorDetail: String? = null,
    val blocked: AiBlocked? = null,
    val provider: String = AiPresets.DEEPSEEK,
    val sessionUsage: AiUsage = AiUsage(),
    val sessionElapsedMs: Long = 0,
    val streaming: Boolean = false,
    val liveContent: String = "",
    val liveSteps: List<AiProcessStep> = emptyList(),
    val pendingToolApprovals: List<AiToolApproval> = emptyList(),
    val conversations: List<AiConversationItem> = emptyList(),
    val activeConversationId: String? = null,
    val chatMode: AiChatMode = AiChatMode.ARCHIVE,
    val pendingFiles: List<AiFileReference> = emptyList(),
    val fileUploading: Boolean = false,
    val canUploadFiles: Boolean = false,
    val canUseWebTools: Boolean = false,
)

@HiltViewModel
class AiViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val aiModule: AiModule,
    private val personQueries: PersonQueries,
    private val repository: SettingsRepository,
    private val reader: AiSettingsReader,
    private val appLog: AppLog,
    private val chatStore: AiChatStore,
    private val searchService: SearchService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val initialPersonId: String? = savedStateHandle.get<String>(ARG_PERSON_ID)

    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    /** Serialises every conversation read/write so a late save cannot win. */
    private val chatStoreMutex = Mutex()

    /** Blocks sending while a conversation-level operation is still running. */
    private val conversationOp = AtomicBoolean(false)

    @Volatile
    private var toolApprovalDecision: CompletableDeferred<Boolean>? = null

    init {
        observeReadiness()
        loadLibrary()
    }

    private fun observeReadiness() {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val blocked = blockedOf(settings)
                val config = withContext(Dispatchers.IO) {
                    runCatching { reader.read().config }.getOrNull()
                }
                val deepSeekFilesAvailable = config?.let {
                    it.provider == AiPresets.DEEPSEEK &&
                        it.officialDeepSeekEndpoint &&
                        it.resolvedModel == "deepseek-flash" &&
                        it.isConfigured()
                } == true
                _state.update {
                    it.copy(
                        blocked = blocked,
                        provider = settings.aiProvider,
                        canUploadFiles = deepSeekFilesAvailable,
                        canUseWebTools = deepSeekFilesAvailable,
                        chatMode = if (deepSeekFilesAvailable) it.chatMode else AiChatMode.ARCHIVE,
                    )
                }
            }
        }
    }

    private fun loadLibrary() {
        viewModelScope.launch {
            val chatSkills = runCatching { aiModule.skills() }
                .getOrDefault(emptyList())
                .filter { it.stage == SkillStage.CHAT }
            val people = withContext(Dispatchers.IO) { personQueries.list() }
            val targetPersonId = _state.value.selectedPersonId ?: initialPersonId
            _state.update { state ->
                val personId = state.selectedPersonId ?: targetPersonId
                val default = if (personId != null) {
                    chatSkills.firstOrNull { it.requiresPerson }
                } else {
                    chatSkills.firstOrNull { !it.requiresPerson }
                }
                state.copy(
                    skills = chatSkills,
                    people = people,
                    selectedPersonId = personId,
                    selectedSkill = state.selectedSkill ?: default?.name ?: chatSkills.firstOrNull()?.name,
                )
            }
            restoreHistory()
            _state.value.selectedPersonId?.let { loadPersona(it) }
        }
    }

    private fun loadPersona(personId: String) {
        viewModelScope.launch {
            runCatching { aiModule.persona(personId) }.onSuccess { persona ->
                _state.update { it.copy(personaDistilled = persona.distilled) }
            }
        }
    }

    fun selectSkill(name: String) {
        if (_state.value.busy || _state.value.fileUploading || conversationOp.get()) return
        discardPendingFiles()
        _state.update { it.copy(selectedSkill = name) }
        restoreHistory()
    }

    fun selectPerson(personId: String?) {
        if (_state.value.busy || _state.value.fileUploading || conversationOp.get()) return
        discardPendingFiles()
        _state.update { state ->
            val current = state.skills.firstOrNull { it.name == state.selectedSkill }
            val compatible = when {
                personId == null && current?.requiresPerson == true ->
                    state.skills.firstOrNull { !it.requiresPerson }

                personId != null && current?.requiresPerson != true ->
                    state.skills.firstOrNull { it.requiresPerson }

                else -> current
            }
            state.copy(
                selectedPersonId = personId,
                selectedSkill = compatible?.name ?: state.selectedSkill,
                personaDistilled = false,
                errorCode = 0,
                errorDetail = null,
            )
        }
        if (personId == null) {
            restoreHistory()
            return
        }
        restoreHistory()
        loadPersona(personId)
    }

    fun send(text: String) {
        val query = text.trim()
        val current = _state.value
        if (
            query.isEmpty() ||
            current.busy ||
            current.fileUploading ||
            conversationOp.get() ||
            current.blocked != null ||
            current.activeConversationId == null
        ) return
        val skillName = current.selectedSkill ?: return
        val personId = current.selectedPersonId
        if (current.pendingFiles.size > MAX_FILES_PER_MESSAGE) {
            _state.update { it.copy(errorCode = 0, errorDetail = "file-limit") }
            return
        }
        val now = Instant.now()
        val files = current.pendingFiles.filter { isFresh(it, now) }
        val hasExpiredPending = files.size != current.pendingFiles.size
        // The API accepts at most 20 document references per request, and an
        // expired file_id makes every later turn fail. Keep only fresh files
        // and charge the newest history first so an ongoing document is kept.
        var fileBudget = (MAX_FILE_REFERENCES_PER_REQUEST - files.size).coerceAtLeast(0)
        val history = buildList {
            for (bubble in current.messages.asReversed()) {
                val kept = bubble.files
                    .filter { isFresh(it, now) }
                    .take(fileBudget)
                fileBudget -= kept.size
                add(
                    ChatTurn(
                        fromUser = bubble.fromUser,
                        text = bubble.text,
                        files = kept,
                        serverToolCallsJson = bubble.serverToolCallsJson,
                        citations = bubble.citations,
                    ),
                )
            }
        }.asReversed()

        _state.update {
            val existingTitle = it.conversations.firstOrNull { item -> item.id == it.activeConversationId }?.title.orEmpty()
            val title = existingTitle.ifBlank { query.take(MAX_TITLE_CHARS) }
            it.copy(
                messages = it.messages + AiChatBubble(
                    fromUser = true,
                    text = query,
                    files = files,
                ),
                conversations = it.conversations.map { item ->
                    if (item.id == it.activeConversationId && item.title.isBlank()) item.copy(title = title) else item
                },
                pendingFiles = emptyList(),
                busy = true,
                streaming = true,
                liveContent = "",
                liveSteps = emptyList(),
                errorCode = 0,
                errorDetail = if (hasExpiredPending) "file-expired" else null,
            )
        }
        persistHistory()

        val searchableRecordChanged = AtomicBoolean(false)
        val listener = object : AiChatListener {
            override fun onContent(delta: String) {
                _state.update { it.copy(liveContent = it.liveContent + delta) }
            }

            override fun onReasoning(delta: String) {
                _state.update { state ->
                    // Consecutive deltas belong to the same thinking segment; a
                    // delta after a tool call starts a new one.
                    val steps = state.liveSteps
                    val last = steps.lastOrNull()
                    val updated = if (last is AiProcessStep.Reasoning) {
                        steps.dropLast(1) + last.copy(text = last.text + delta)
                    } else {
                        steps + AiProcessStep.Reasoning(delta)
                    }
                    state.copy(liveSteps = updated)
                }
            }

            override fun onToolCall(name: String, arguments: String) {
                _state.update {
                    it.copy(
                        liveSteps = it.liveSteps + AiProcessStep.Tool(
                            name = name,
                            arguments = arguments.take(MAX_TOOL_ARG_CHARS),
                        ),
                    )
                }
            }

            override fun onServerToolEvent(event: AiServerToolEvent) {
                _state.update { state ->
                    state.copy(liveSteps = state.liveSteps + AiProcessStep.Web(event))
                }
            }

            override fun onToolResult(name: String, result: String) {
                if (isSuccessfulSearchableWrite(name, result)) {
                    searchableRecordChanged.set(true)
                }
                _state.update { state ->
                    val index = state.liveSteps.indexOfLast {
                        it is AiProcessStep.Tool && it.name == name && !it.done
                    }
                    if (index < 0) {
                        state
                    } else {
                        val step = state.liveSteps[index] as AiProcessStep.Tool
                        state.copy(
                            liveSteps = state.liveSteps.toMutableList().also {
                                it[index] = step.copy(
                                    result = result.take(MAX_TOOL_RESULT_CHARS),
                                    done = true,
                                )
                            },
                        )
                    }
                }
            }

            override fun onToolResult(name: String) {
                onToolResult(name, "")
            }

            override suspend fun onToolApprovals(approvals: List<AiToolApproval>): Boolean {
                val decision = CompletableDeferred<Boolean>()
                toolApprovalDecision = decision
                _state.update { it.copy(pendingToolApprovals = approvals.toList()) }
                return try {
                    decision.await()
                } finally {
                    if (toolApprovalDecision === decision) {
                        toolApprovalDecision = null
                        _state.update { it.copy(pendingToolApprovals = emptyList()) }
                    }
                }
            }
        }

        viewModelScope.launch {
            val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            if (readiness == null || !readiness.config.isConfigured()) {
                _state.update {
                    it.copy(
                        busy = false,
                        streaming = false,
                        liveContent = "",
                        liveSteps = emptyList(),
                        errorCode = 0,
                        errorDetail = "not-configured",
                    )
                }
                return@launch
            }
            val chatResult = runCatching {
                aiModule.chatStreaming(
                    ChatRequest(
                        skillName = skillName,
                        question = query,
                        personId = personId,
                        history = history,
                        config = readiness.config,
                        mode = current.chatMode,
                        files = files,
                    ),
                    listener,
                )
            }
            if (searchableRecordChanged.get()) {
                runCatching { searchService.rebuild() }
                    .onFailure { appLog.warn(TAG, "search index refresh after AI write failed", it) }
            }
            chatResult.onSuccess { reply ->
                val usage = reply.usage
                _state.update { state ->
                    state.copy(
                        busy = false,
                        streaming = false,
                        liveContent = "",
                        liveSteps = emptyList(),
                        messages = state.messages + AiChatBubble(
                            fromUser = false,
                            text = reply.text,
                            usage = usage.takeIf { it.totalTokens > 0 },
                            elapsedMs = reply.elapsedMs,
                            // Keep the ordered thinking/tool steps with the bubble;
                            // they used to be dropped the moment the answer landed.
                            steps = state.liveSteps,
                            serverToolCallsJson = reply.serverToolCallsJson,
                            citations = reply.citations,
                            isTruncated = reply.finishReason == "length",
                        ),
                        sessionUsage = state.sessionUsage.merge(usage),
                        sessionElapsedMs = state.sessionElapsedMs + reply.elapsedMs,
                    )
                }
                persistHistory()
            }.onFailure { error ->
                appLog.warn(TAG, "chat failed", error)
                _state.update {
                    it.copy(
                        busy = false,
                        streaming = false,
                        liveContent = "",
                        liveSteps = emptyList(),
                        errorCode = (error as? AiProviderException)?.code ?: 0,
                        errorDetail = (error as? AiProviderException)?.message,
                    )
                }
                persistHistory()
            }
        }
    }

    fun setChatMode(mode: AiChatMode) {
        val current = _state.value
        if (current.busy || current.fileUploading || conversationOp.get()) return
        if (mode == AiChatMode.WEB && !current.canUseWebTools) {
            _state.update { it.copy(errorCode = 0, errorDetail = "web-tools-unavailable") }
            return
        }
        _state.update { it.copy(chatMode = mode, errorCode = 0, errorDetail = null) }
    }

    fun addFiles(uris: List<Uri>) {
        val current = _state.value
        if (
            current.busy ||
            current.fileUploading ||
            conversationOp.get() ||
            !current.canUploadFiles ||
            current.blocked != null
        ) return
        if (current.pendingFiles.size + uris.size > MAX_FILES_PER_MESSAGE) {
            _state.update { it.copy(errorCode = 0, errorDetail = "file-limit") }
            return
        }
        _state.update { it.copy(fileUploading = true, errorCode = 0, errorDetail = null) }
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { reader.read().config }
                for (uri in uris) {
                    val selected = withContext(Dispatchers.IO) { readSelectedFile(uri) }
                    try {
                        val uploaded = withContext(Dispatchers.IO) {
                            AiFiles.upload(config, selected.name, selected.mimeType, selected.file)
                        }
                        _state.update { state -> state.copy(pendingFiles = state.pendingFiles + uploaded) }
                    } finally {
                        selected.file.delete()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.warn(TAG, "AI file upload failed", failure)
                _state.update {
                    it.copy(
                        errorCode = (failure as? AiProviderException)?.code ?: 0,
                        errorDetail = (failure as? AiProviderException)?.message ?: "file-upload-failed",
                    )
                }
            } finally {
                _state.update { it.copy(fileUploading = false) }
            }
        }
    }

    fun removePendingFile(fileId: String) {
        val file = _state.value.pendingFiles.firstOrNull { it.id == fileId } ?: return
        if (conversationOp.get()) return
        if (!beginConversationOp()) return
        viewModelScope.launch {
            try {
                if (deleteRemoteFiles(listOf(file))) {
                    _state.update {
                        it.copy(pendingFiles = it.pendingFiles.filterNot { pending -> pending.id == fileId })
                    }
                } else {
                    _state.update { it.copy(errorCode = 0, errorDetail = "file-delete-failed") }
                }
            } finally {
                endConversationOp()
            }
        }
    }

    fun editMessage(messageId: String, text: String) {
        val state = _state.value
        val index = state.messages.indexOfFirst { it.id == messageId && it.fromUser }
        if (index < 0 || state.busy || state.fileUploading || conversationOp.get()) return
        val original = state.messages[index]
        val dropped = state.messages.drop(index + 1)
        _state.update {
            it.copy(
                messages = it.messages.take(index),
                pendingFiles = original.files,
                errorCode = 0,
                errorDetail = null,
            )
        }
        cleanupDroppedFiles(dropped)
        send(text)
    }

    fun regenerate(messageId: String) {
        val state = _state.value
        val index = state.messages.indexOfFirst { it.id == messageId && !it.fromUser }
        if (index < 0 || state.busy || state.fileUploading || conversationOp.get()) return
        val userIndex = state.messages.subList(0, index).indexOfLast { it.fromUser }
        if (userIndex < 0) return
        val previousUser = state.messages[userIndex]
        val dropped = state.messages.drop(userIndex + 1)
        _state.update {
            it.copy(
                messages = it.messages.take(userIndex),
                pendingFiles = previousUser.files,
                errorCode = 0,
                errorDetail = null,
            )
        }
        cleanupDroppedFiles(dropped)
        send(previousUser.text)
    }

    fun continueResponse(messageId: String, prompt: String) {
        val state = _state.value
        val response = state.messages.firstOrNull { it.id == messageId && !it.fromUser } ?: return
        if (state.messages.lastOrNull()?.id != messageId) return
        if (!response.isTruncated || response.serverToolCallsJson != null || state.busy ||
            state.fileUploading || conversationOp.get()
        ) return
        send(prompt)
    }

    fun newConversation() {
        if (_state.value.busy || _state.value.fileUploading || conversationOp.get()) return
        if (!beginConversationOp()) return
        val state = _state.value
        val unusedFiles = state.pendingFiles
        val conversation = newStoredConversation(scopeKey(state.selectedSkill, state.selectedPersonId))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filesDeleted = deleteRemoteFiles(unusedFiles)
                chatStoreMutex.withLock {
                    chatStore.saveConversation(conversation)
                    _state.update {
                        it.copy(
                            conversations = listOf(AiConversationItem(conversation.id, "")) +
                                it.conversations.filterNot { item -> item.id == conversation.id },
                            activeConversationId = conversation.id,
                            messages = emptyList(),
                            pendingFiles = emptyList(),
                            sessionUsage = AiUsage(),
                            sessionElapsedMs = 0,
                            errorCode = 0,
                            errorDetail = if (filesDeleted) null else "file-delete-failed",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.warn(TAG, "AI conversation creation failed", failure)
                _state.update { it.copy(errorCode = 0, errorDetail = "chat-store-write-failed") }
            } finally {
                endConversationOp()
            }
        }
    }

    fun selectConversation(id: String) {
        if (_state.value.busy || _state.value.fileUploading || conversationOp.get()) return
        if (!beginConversationOp()) return
        val unusedFiles = _state.value.pendingFiles
        val currentScope = scopeKey(_state.value.selectedSkill, _state.value.selectedPersonId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filesDeleted = deleteRemoteFiles(unusedFiles)
                chatStoreMutex.withLock {
                    val conversation = chatStore.listConversations(currentScope).firstOrNull { it.id == id }
                    if (conversation == null) {
                        _state.update {
                            it.copy(
                                pendingFiles = emptyList(),
                                errorCode = 0,
                                errorDetail = if (filesDeleted) null else "file-delete-failed",
                            )
                        }
                        return@withLock
                    }
                    _state.update {
                        it.copy(
                            activeConversationId = conversation.id,
                            messages = conversation.messages.map(::toBubble),
                            pendingFiles = emptyList(),
                            sessionUsage = AiUsage(),
                            sessionElapsedMs = 0,
                            errorCode = 0,
                            errorDetail = if (filesDeleted) null else "file-delete-failed",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.warn(TAG, "AI conversation load failed", failure)
                _state.update { it.copy(errorCode = 0, errorDetail = "chat-store-read-failed") }
            } finally {
                endConversationOp()
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        val cleaned = title.trim().take(MAX_TITLE_CHARS)
        if (
            cleaned.isBlank() ||
            _state.value.busy ||
            _state.value.fileUploading ||
            conversationOp.get()
        ) return
        if (!beginConversationOp()) return
        val scope = scopeKey(_state.value.selectedSkill, _state.value.selectedPersonId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatStoreMutex.withLock {
                    val conversation = chatStore.listConversations(scope).firstOrNull { it.id == id }
                        ?: return@withLock
                    chatStore.saveConversation(conversation.copy(title = cleaned, updatedAt = Instant.now()))
                    // Update the in-memory title inside the lock as well, so the
                    // next queued persist cannot write the old title back.
                    _state.update { state ->
                        state.copy(
                            conversations = state.conversations.map {
                                if (it.id == id) it.copy(title = cleaned) else it
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.warn(TAG, "AI conversation rename failed", failure)
                _state.update { it.copy(errorCode = 0, errorDetail = "chat-store-write-failed") }
            } finally {
                endConversationOp()
            }
        }
    }

    fun deleteConversation(id: String) {
        if (_state.value.busy || _state.value.fileUploading || conversationOp.get()) return
        if (!beginConversationOp()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversation = chatStoreMutex.withLock {
                    chatStore.listConversations().firstOrNull { it.id == id }
                } ?: return@launch
                val pending = if (_state.value.activeConversationId == id) _state.value.pendingFiles else emptyList()
                val stored = conversation.messages.flatMap { it.files }.map {
                    AiFileReference(it.id, it.fileName, it.purpose, it.uploadedAt)
                }
                val filesDeleted = deleteRemoteFiles((stored + pending).distinctBy { it.id })
                chatStoreMutex.withLock {
                    chatStore.deleteConversation(id)
                    val remaining = chatStore.listConversations(conversation.scope)
                    val next = remaining.firstOrNull() ?: newStoredConversation(conversation.scope)
                    if (remaining.isEmpty()) chatStore.saveConversation(next)
                    _state.update { state ->
                        if (state.activeConversationId != id) {
                            state.copy(
                                conversations = state.conversations.filterNot { it.id == id },
                                errorCode = 0,
                                errorDetail = if (filesDeleted) null else "file-delete-failed",
                            )
                        } else {
                            state.copy(
                                conversations = (remaining.ifEmpty { listOf(next) })
                                    .map { AiConversationItem(it.id, it.title) },
                                activeConversationId = next.id,
                                messages = next.messages.map(::toBubble),
                                pendingFiles = emptyList(),
                                sessionUsage = AiUsage(),
                                sessionElapsedMs = 0,
                                errorCode = 0,
                                errorDetail = if (filesDeleted) null else "file-delete-failed",
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.warn(TAG, "AI conversation deletion failed", failure)
                _state.update { it.copy(errorCode = 0, errorDetail = "chat-store-delete-failed") }
            } finally {
                endConversationOp()
            }
        }
    }

    fun clear() {
        if (_state.value.busy || _state.value.fileUploading || conversationOp.get()) return
        decideToolApproval(approved = false)
        val files = (
            _state.value.messages.flatMap { it.files } + _state.value.pendingFiles
            ).distinctBy { it.id }
        _state.update {
            it.copy(
                messages = emptyList(),
                pendingFiles = emptyList(),
                sessionUsage = AiUsage(),
                sessionElapsedMs = 0,
                streaming = false,
                liveContent = "",
                liveSteps = emptyList(),
                pendingToolApprovals = emptyList(),
                errorCode = 0,
                errorDetail = null,
            )
        }
        persistHistory()
        viewModelScope.launch {
            if (!deleteRemoteFiles(files)) {
                _state.update { it.copy(errorCode = 0, errorDetail = "file-delete-failed") }
            }
        }
    }

    fun decideToolApproval(approved: Boolean) {
        toolApprovalDecision?.complete(approved)
    }

    override fun onCleared() {
        decideToolApproval(approved = false)
        super.onCleared()
    }

    private fun scopeKey(skill: String?, personId: String?) =
        "${skill ?: "none"}__${personId ?: "all"}"

    private fun beginConversationOp(): Boolean = conversationOp.compareAndSet(false, true)

    private fun endConversationOp() {
        conversationOp.set(false)
    }

    private fun isFresh(file: AiFileReference, now: Instant): Boolean =
        file.uploadedAt != Instant.EPOCH &&
            file.uploadedAt.plusSeconds(FILE_HISTORY_TTL_SECONDS.toLong()).isAfter(now)

    private fun toBubble(message: StoredChatMessage) = AiChatBubble(
        id = message.id,
        fromUser = message.fromUser,
        text = message.text,
        createdAt = message.at,
        files = message.files.map { AiFileReference(it.id, it.fileName, it.purpose, it.uploadedAt) },
        serverToolCallsJson = message.serverToolCallsJson,
        citations = message.citations.map {
            AiCitation(it.url, it.title, it.startIndex, it.endIndex)
        },
        isTruncated = message.isTruncated,
    )

    private fun newStoredConversation(scope: String): StoredAiConversation {
        val now = Instant.now()
        return StoredAiConversation(
            id = UUID.randomUUID().toString().replace("-", ""),
            scope = scope,
            title = "",
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
        )
    }

    private fun cleanupDroppedFiles(messages: List<AiChatBubble>) {
        val files = messages.flatMap { it.files }.distinctBy { it.id }
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!deleteRemoteFiles(files)) {
                appLog.warn(TAG, "AI dropped-message file cleanup failed")
            }
        }
    }

    private fun discardPendingFiles() {
        val files = _state.value.pendingFiles
        if (files.isEmpty()) return
        _state.update { it.copy(pendingFiles = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!deleteRemoteFiles(files)) {
                appLog.warn(TAG, "AI pending file cleanup failed")
            }
        }
    }

    private suspend fun deleteRemoteFiles(files: List<AiFileReference>): Boolean {
        if (files.isEmpty()) return true
        val config = try {
            withContext(Dispatchers.IO) { reader.read().config }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            appLog.warn(TAG, "AI file cleanup could not load provider settings", failure)
            return false
        }
        var allDeleted = true
        files.distinctBy { it.id }.forEach { file ->
            try {
                withContext(Dispatchers.IO) { AiFiles.delete(config, file.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                allDeleted = false
                appLog.warn(TAG, "AI remote file deletion failed", failure)
            }
        }
        return allDeleted
    }

    private fun readSelectedFile(uri: Uri): SelectedAiFile {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: throw AiProviderException(0, "missing-file-name")
        val mimeType = resolver.getType(uri)?.takeUnless { it == "application/octet-stream" }.orEmpty()
        if (!AiFiles.isSupported(name)) throw AiProviderException(0, "unsupported-file-type")
        val limit = AiFiles.maxBytesFor(name)
        val declaredSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst()) cursor.getLong(column) else -1L
            } ?: -1L
        if (declaredSize > limit.toLong()) throw AiProviderException(0, "file-size-out-of-range")
        val temporary = File.createTempFile("meteor-ai-", ".upload", context.cacheDir)
        var total = 0
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count > limit - total) {
                            throw AiProviderException(0, "file-size-out-of-range")
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
            } ?: throw AiProviderException(0, "file-read-failed")
            if (total == 0) throw AiProviderException(0, "file-size-out-of-range")
            return SelectedAiFile(name, mimeType, temporary)
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    private fun restoreHistory() {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatStoreMutex.withLock {
                    val scope = scopeKey(_state.value.selectedSkill, _state.value.selectedPersonId)
                    val sessions = chatStore.listConversations(scope)
                    val activeId = _state.value.activeConversationId
                    val selected = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
                    val conversation = selected ?: newStoredConversation(scope).also {
                        chatStore.saveConversation(it)
                    }
                    if (scope != scopeKey(_state.value.selectedSkill, _state.value.selectedPersonId)) {
                        return@withLock
                    }
                    _state.update {
                        it.copy(
                            conversations = (if (selected == null) listOf(conversation) else sessions)
                                .map { item -> AiConversationItem(item.id, item.title) },
                            activeConversationId = conversation.id,
                            messages = conversation.messages.map(::toBubble),
                            pendingFiles = emptyList(),
                            sessionUsage = AiUsage(),
                            sessionElapsedMs = 0,
                            streaming = false,
                            liveContent = "",
                            liveSteps = emptyList(),
                            errorCode = 0,
                            errorDetail = null,
                        )
                    }
                }
            } catch (failure: Exception) {
                appLog.warn(TAG, "AI conversation restore failed", failure)
                _state.update { it.copy(errorCode = 0, errorDetail = "chat-store-read-failed") }
            }
        }
    }

    private fun persistHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            chatStoreMutex.withLock {
                // Build the snapshot while holding the lock: a rename or clear
                // that landed after this call must be the one that is written.
                val state = _state.value
                val id = state.activeConversationId ?: return@withLock
                val scope = scopeKey(state.selectedSkill, state.selectedPersonId)
                val existing = state.conversations.firstOrNull { it.id == id }
                val now = Instant.now()
                val conversation = StoredAiConversation(
                    id = id,
                    scope = scope,
                    title = existing?.title.orEmpty(),
                    createdAt = state.messages.firstOrNull()?.createdAt ?: now,
                    updatedAt = now,
                    messages = state.messages.map { bubble ->
                        StoredChatMessage(
                            id = bubble.id,
                            fromUser = bubble.fromUser,
                            text = bubble.text,
                            at = bubble.createdAt,
                            files = bubble.files.map {
                                StoredChatFile(it.id, it.fileName, it.purpose, it.uploadedAt)
                            },
                            serverToolCallsJson = bubble.serverToolCallsJson,
                            citations = bubble.citations.map {
                                StoredChatCitation(it.url, it.title, it.startIndex, it.endIndex)
                            },
                            isTruncated = bubble.isTruncated,
                        )
                    },
                )
                runCatching { chatStore.saveConversation(conversation) }
                    .onFailure { appLog.warn(TAG, "AI conversation save failed", it) }
            }
        }
    }

    /** Re-check the selected persona after returning from its screen. */
    fun refresh() {
        val personId = _state.value.selectedPersonId ?: return
        viewModelScope.launch {
            runCatching { aiModule.persona(personId) }.onSuccess { persona ->
                _state.update { it.copy(personaDistilled = persona.distilled) }
            }
        }
    }

    private suspend fun blockedOf(settings: AppSettings): AiBlocked? {
        if (!settings.aiEnabled) return AiBlocked.DISABLED
        if (!settings.aiConsentAccepted) return AiBlocked.NEEDS_CONSENT
        val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            ?: return AiBlocked.NEEDS_CONFIG
        return when {
            !readiness.hasApiKey -> AiBlocked.NEEDS_KEY
            !readiness.config.isConfigured() -> AiBlocked.NEEDS_CONFIG
            else -> null
        }
    }

    companion object {
        const val ARG_PERSON_ID = "personId"
        private const val TAG = "Ai"

        /** Arguments and results are shown inline, so keep them short. */
        private const val MAX_TOOL_ARG_CHARS = 160
        private const val MAX_TOOL_RESULT_CHARS = 400
        private const val MAX_TITLE_CHARS = 32
        private const val MAX_FILES_PER_MESSAGE = 10

        /** DeepSeek accepts at most 20 document references per request. */
        private const val MAX_FILE_REFERENCES_PER_REQUEST = 20

        /** Keep a one-day margin before the provider's 30-day expiry. */
        private const val FILE_HISTORY_TTL_SECONDS = AiFiles.FILE_EXPIRY_SECONDS - 24 * 60 * 60
    }

    private data class SelectedAiFile(val name: String, val mimeType: String, val file: File)
}
