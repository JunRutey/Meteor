package com.meteor.memory.ui.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AiChatStore
import com.meteor.memory.core.storage.PersonEntry
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.StoredChatTurn
import com.meteor.memory.feature.ai.AiChatBubble
import com.meteor.memory.feature.ai.AiChatListener
import com.meteor.memory.feature.ai.AiModule
import com.meteor.memory.feature.ai.AiPresets
import com.meteor.memory.feature.ai.AiProviderException
import com.meteor.memory.feature.ai.AiSkill
import com.meteor.memory.feature.ai.AiProcessStep
import com.meteor.memory.feature.ai.AiUsage
import com.meteor.memory.feature.ai.ChatRequest
import com.meteor.memory.feature.ai.ChatTurn
import com.meteor.memory.feature.ai.SkillStage
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

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
) : ViewModel() {

    private val initialPersonId: String? = savedStateHandle.get<String>(ARG_PERSON_ID)

    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    init {
        observeReadiness()
        loadLibrary()
        initialPersonId?.let { selectPerson(it) }
    }

    private fun observeReadiness() {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val blocked = blockedOf(settings)
                _state.update { it.copy(blocked = blocked, provider = settings.aiProvider) }
            }
        }
    }

    private fun loadLibrary() {
        viewModelScope.launch {
            val chatSkills = runCatching { aiModule.skills() }
                .getOrDefault(emptyList())
                .filter { it.stage == SkillStage.CHAT }
            val people = withContext(Dispatchers.IO) { personQueries.list() }
            _state.update { state ->
                val default = if (state.selectedPersonId != null) {
                    chatSkills.firstOrNull { it.requiresPerson }
                } else {
                    chatSkills.firstOrNull { !it.requiresPerson }
                }
                state.copy(
                    skills = chatSkills,
                    people = people,
                    selectedSkill = state.selectedSkill ?: default?.name ?: chatSkills.firstOrNull()?.name,
                )
            }
            restoreHistory()
        }
    }

    fun selectSkill(name: String) {
        _state.update { it.copy(selectedSkill = name) }
        restoreHistory()
    }

    fun selectPerson(personId: String?) {
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
        viewModelScope.launch {
            runCatching { aiModule.persona(personId) }.onSuccess { persona ->
                _state.update { it.copy(personaDistilled = persona.distilled) }
            }
        }
    }

    fun send(text: String) {
        val query = text.trim()
        val current = _state.value
        if (query.isEmpty() || current.busy || current.blocked != null) return
        val skillName = current.selectedSkill ?: return
        val personId = current.selectedPersonId
        val history = current.messages.map { ChatTurn(fromUser = it.fromUser, text = it.text) }

        _state.update {
            it.copy(
                messages = it.messages + AiChatBubble(fromUser = true, text = query),
                busy = true,
                streaming = true,
                liveContent = "",
                liveSteps = emptyList(),
                errorCode = 0,
                errorDetail = null,
            )
        }
        persistHistory()

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

            override fun onToolResult(name: String, result: String) {
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
            runCatching {
                aiModule.chatStreaming(
                    ChatRequest(
                        skillName = skillName,
                        question = query,
                        personId = personId,
                        history = history,
                        config = readiness.config,
                    ),
                    listener,
                )
            }.onSuccess { reply ->
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

    fun clear() {
        val key = scopeKey(_state.value.selectedSkill, _state.value.selectedPersonId)
        viewModelScope.launch(Dispatchers.IO) { runCatching { chatStore.clear(key) } }
        _state.update {
            it.copy(
                messages = emptyList(),
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

    private fun scopeKey(skill: String?, personId: String?) =
        "${skill ?: "none"}__${personId ?: "all"}"

    private fun restoreHistory() {
        if (_state.value.busy) return
        viewModelScope.launch {
            val key = scopeKey(_state.value.selectedSkill, _state.value.selectedPersonId)
            val turns = withContext(Dispatchers.IO) {
                runCatching { chatStore.load(key) }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(
                    messages = turns.map { turn -> AiChatBubble(turn.fromUser, turn.text) },
                    sessionUsage = AiUsage(),
                    sessionElapsedMs = 0,
                    streaming = false,
                    liveContent = "",
                    liveSteps = emptyList(),
                )
            }
        }
    }

    private fun persistHistory() {
        val key = scopeKey(_state.value.selectedSkill, _state.value.selectedPersonId)
        val turns = _state.value.messages.map {
            StoredChatTurn(fromUser = it.fromUser, text = it.text, at = Instant.now())
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatStore.save(key, turns) }
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
    }
}
