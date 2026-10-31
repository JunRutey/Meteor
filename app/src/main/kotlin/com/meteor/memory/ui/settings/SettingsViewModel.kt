package com.meteor.memory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.ai.AiModule
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.DarkMode
import com.meteor.memory.feature.settings.FontScale
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.ui.ai.AiSettingsReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Result of the "test connection" probe, kept code-only so the UI owns copy. */
data class AiTestState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val ok: Boolean = false,
    val code: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val appLog: AppLog,
    private val reader: AiSettingsReader,
    private val aiModule: AiModule,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    private val _logSizeBytes = MutableStateFlow(0L)
    val logSizeBytes: StateFlow<Long> = _logSizeBytes.asStateFlow()

    private val _apiKeyPresent = MutableStateFlow(reader.hasApiKey())
    val apiKeyPresent: StateFlow<Boolean> = _apiKeyPresent.asStateFlow()

    private val _aiTest = MutableStateFlow(AiTestState())
    val aiTest: StateFlow<AiTestState> = _aiTest.asStateFlow()

    init {
        refreshLogSize()
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { repository.setDarkMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }

    fun setFontScale(scale: FontScale) {
        viewModelScope.launch { repository.setFontScale(scale) }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAnimationsEnabled(enabled) }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAppLockEnabled(enabled) }
    }

    fun setVerboseLogging(enabled: Boolean) {
        viewModelScope.launch {
            repository.setVerboseLogging(enabled)
            withContext(Dispatchers.IO) { appLog.setVerbose(enabled) }
        }
    }

    /** Hides the entry and stops every request; never touches archive data. */
    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAiEnabled(enabled) }
    }

    /** Only ever called from the consent dialog's confirm button. */
    fun acceptAiConsent() {
        viewModelScope.launch { repository.setAiConsentAccepted(true) }
    }

    fun setAiProvider(provider: String) {
        viewModelScope.launch { repository.setAiProvider(provider) }
    }

    fun setAiBaseUrl(baseUrl: String) {
        viewModelScope.launch { repository.setAiBaseUrl(baseUrl) }
    }

    fun setAiModel(model: String) {
        viewModelScope.launch { repository.setAiModel(model) }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            reader.saveApiKey(key)
            _apiKeyPresent.value = reader.hasApiKey()
        }
    }

    fun clearApiKey() {
        viewModelScope.launch(Dispatchers.IO) {
            reader.clearApiKey()
            _apiKeyPresent.value = false
        }
    }

    fun testAiConnection() {
        if (_aiTest.value.running) return
        _aiTest.value = AiTestState(running = true)
        viewModelScope.launch {
            val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            if (readiness == null || !readiness.config.isConfigured()) {
                _aiTest.value = AiTestState(finished = true, ok = false, code = 0)
                return@launch
            }
            val result = runCatching { aiModule.probe(readiness.config) }.getOrNull()
            _aiTest.value = AiTestState(
                finished = true,
                ok = result?.ok == true,
                code = result?.code ?: 0,
            )
        }
    }

    fun refreshLogSize() {
        viewModelScope.launch {
            _logSizeBytes.value = withContext(Dispatchers.IO) { appLog.sizeBytes() }
        }
    }
}
