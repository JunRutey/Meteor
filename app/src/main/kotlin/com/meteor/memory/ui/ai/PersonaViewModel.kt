package com.meteor.memory.ui.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.ai.AiModule
import com.meteor.memory.feature.ai.AiPresets
import com.meteor.memory.feature.ai.AiProviderException
import com.meteor.memory.feature.ai.DistillRequest
import com.meteor.memory.feature.ai.PersonaQuoteRow
import com.meteor.memory.feature.ai.PersonaSnapshot
import com.meteor.memory.feature.ai.PersonaTopicRow
import com.meteor.memory.feature.ai.PersonaUi
import com.meteor.memory.feature.ai.SkillStage
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
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

data class PersonaUiState(
    val ui: PersonaUi = PersonaUi(displayName = "", distilled = false, body = ""),
    val provider: String = AiPresets.DEEPSEEK,
    val blocked: AiBlocked? = null,
    val busy: Boolean = false,
    val loading: Boolean = true,
    val errorCode: Int = 0,
    val errorDetail: String? = null,
)

@HiltViewModel
class PersonaViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val aiModule: AiModule,
    private val repository: SettingsRepository,
    private val reader: AiSettingsReader,
    private val gate: AiGate,
    private val timeModule: TimeModule,
    private val appLog: AppLog,
) : ViewModel() {

    private val personId: String = checkNotNull(savedStateHandle.get<String>(ARG_PERSON_ID))

    private val _state = MutableStateFlow(PersonaUiState())
    val state: StateFlow<PersonaUiState> = _state.asStateFlow()

    private var distillSkill: String? = null

    init {
        observeReadiness()
        loadDistillSkill()
        load()
    }

    private fun observeReadiness() {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val blocked = gate.blocked(settings)
                _state.update { it.copy(provider = settings.aiProvider, blocked = blocked) }
            }
        }
    }

    private fun loadDistillSkill() {
        viewModelScope.launch {
            distillSkill = runCatching { aiModule.skills() }
                .getOrDefault(emptyList())
                .firstOrNull { it.stage == SkillStage.DISTILL }
                ?.name
        }
    }

    fun load() {
        viewModelScope.launch {
            val snapshot = runCatching { aiModule.persona(personId) }
                .onFailure { appLog.warn(TAG, "persona load failed", it) }
                .getOrNull()
                ?: return@launch
            _state.update { it.copy(ui = snapshot.toUi(Locale.getDefault()), loading = false) }
        }
    }

    /** The user already confirmed what will be sent (dialog in the screen). */
    fun distill() {
        val current = _state.value
        if (current.busy || current.blocked != null) return
        val skill = distillSkill ?: return

        _state.update { it.copy(busy = true, errorCode = 0, errorDetail = null) }
        viewModelScope.launch {
            val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            if (readiness == null || !readiness.config.isConfigured()) {
                _state.update { it.copy(busy = false, errorCode = 0, errorDetail = "not-configured") }
                return@launch
            }
            runCatching {
                aiModule.distill(
                    DistillRequest(personId = personId, skillName = skill, config = readiness.config),
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        busy = false,
                        ui = result.snapshot.toUi(Locale.getDefault()),
                        errorCode = 0,
                        errorDetail = null,
                    )
                }
            }.onFailure { error ->
                appLog.warn(TAG, "distill failed", error)
                _state.update {
                    it.copy(
                        busy = false,
                        errorCode = (error as? AiProviderException)?.code ?: 0,
                        errorDetail = (error as? AiProviderException)?.message,
                    )
                }
            }
        }
    }

    private fun PersonaSnapshot.toUi(locale: Locale): PersonaUi {
        fun label(instant: Instant?): String? = instant?.let {
            timeModule.formatDate(it.atZone(ZoneId.systemDefault()).toLocalDate(), locale)
        }
        return PersonaUi(
            displayName = displayName ?: personId.take(SHORT_ID),
            distilled = distilled,
            body = body,
            generatedLabel = label(savedAt ?: generatedAt),
            providerLabel = provider,
            skillLabel = skill,
            recordCount = recordCount,
            diaryMentions = diaryMentions,
            topics = topics.map { PersonaTopicRow(it.term, it.count) },
            quotes = quotes.map { PersonaQuoteRow(label(it.at).orEmpty(), it.text) },
        )
    }

    companion object {
        const val ARG_PERSON_ID = "personId"
        private const val TAG = "Persona"
        private const val SHORT_ID = 8
    }
}
