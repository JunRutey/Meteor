package com.meteor.memory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.ai.AiModels
import com.meteor.memory.feature.ai.AiModule
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.DarkMode
import com.meteor.memory.feature.settings.FontScale
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.system.NotificationFeature
import com.meteor.memory.ui.ai.AiSettingsReader
import com.meteor.memory.update.UpdateService
import com.meteor.memory.update.UpdateState
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

/** Model ids fetched from the provider; failure keeps manual entry possible. */
data class AiModelsState(
    val loading: Boolean = false,
    val items: List<String> = emptyList(),
    val failed: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val appLock: AppLock,
    private val appLog: AppLog,
    private val reader: AiSettingsReader,
    private val aiModule: AiModule,
    private val notificationFeature: NotificationFeature,
    private val updateService: UpdateService,
) : ViewModel() {

    private val _notificationsAllowed =
        MutableStateFlow(notificationFeature.canPostDailyReminder())
    val notificationsAllowed: StateFlow<Boolean> =
        _notificationsAllowed.asStateFlow()

    private val _canScheduleExactAlarms = MutableStateFlow(notificationFeature.canScheduleExactAlarms())
    val canScheduleExactAlarms: StateFlow<Boolean> = _canScheduleExactAlarms.asStateFlow()

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

    private val _aiModels = MutableStateFlow(AiModelsState())
    val aiModels: StateFlow<AiModelsState> = _aiModels.asStateFlow()

    /** True after an attempt to enable the lock on a device with nothing to verify. */
    private val _appLockUnavailable = MutableStateFlow(false)
    val appLockUnavailable: StateFlow<Boolean> = _appLockUnavailable.asStateFlow()

    internal val updateState: StateFlow<UpdateState> = updateService.state

    init {
        refreshLogSize()
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch {
            repository.setDarkMode(mode)
            appLog.info(TAG, "dark mode=${mode.name}")
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDynamicColor(enabled)
            appLog.info(TAG, "dynamic color=$enabled")
        }
    }

    fun setFontScale(scale: FontScale) {
        viewModelScope.launch {
            repository.setFontScale(scale)
            appLog.info(TAG, "font scale=${scale.name}")
        }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAnimationsEnabled(enabled)
            appLog.info(TAG, "animations=$enabled")
        }
    }

    /**
     * Never persists "on" when the system has nothing to verify the person
     * against: the next cold start would otherwise sit at a gate nobody can
     * pass. The screen explains this with [appLockUnavailable].
     */
    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val available = withContext(Dispatchers.IO) {
                    runCatching { appLock.isAuthenticationAvailable() }.getOrDefault(false)
                }
                if (!available) {
                    appLog.warn(TAG, "app lock not enabled: no biometric or lock-screen credential")
                    _appLockUnavailable.value = true
                    return@launch
                }
            }
            _appLockUnavailable.value = false
            repository.setAppLockEnabled(enabled)
            appLog.info(TAG, "app lock requested=$enabled")
        }
    }

    fun dismissAppLockUnavailable() {
        _appLockUnavailable.value = false
    }

    fun setScreenshotProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setScreenshotProtectionEnabled(enabled)
            appLog.info(TAG, "screenshot protection=$enabled")
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setNotificationsEnabled(enabled)
            notificationFeature.syncReminder()
            refreshNotificationPermissions()
            appLog.info(TAG, "notifications enabled=$enabled")
        }
    }

    fun refreshNotificationPermissions() {
        _notificationsAllowed.value = notificationFeature.canPostDailyReminder()
        _canScheduleExactAlarms.value = notificationFeature.canScheduleExactAlarms()
        viewModelScope.launch { notificationFeature.syncReminder() }
    }

    fun openNotificationSettings() {
        appLog.info(TAG, "opened notification settings")
        notificationFeature.openNotificationSettings()
    }

    fun openExactAlarmSettings() {
        appLog.info(TAG, "opened exact alarm settings")
        notificationFeature.openExactAlarmSettings()
    }

    fun setVerboseLogging(enabled: Boolean) {
        viewModelScope.launch {
            repository.setVerboseLogging(enabled)
            appLog.info(TAG, "verbose logging=$enabled")
            withContext(Dispatchers.IO) { appLog.setVerbose(enabled) }
        }
    }

    fun setAutoCheckForUpdates(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoCheckForUpdates(enabled)
            appLog.info(TAG, "auto update check=$enabled")
        }
    }

    fun checkForUpdates() {
        appLog.info(TAG, "manual update check")
        updateService.checkAndDownload()
    }

    fun installUpdate() {
        appLog.info(TAG, "install update requested")
        updateService.installDownloadedUpdate()
    }

    fun refreshUpdateInstallPermission() {
        updateService.refreshInstallPermission()
    }

    /** Hides the entry and stops every request; never touches archive data. */
    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAiEnabled(enabled)
            appLog.info(TAG, "AI enabled=$enabled")
        }
    }

    /** Only ever called from the consent dialog's confirm button. */
    fun acceptAiConsent() {
        viewModelScope.launch {
            repository.setAiConsentAccepted(true)
            appLog.info(TAG, "AI consent accepted")
        }
    }

    fun setAiModel(model: String) {
        viewModelScope.launch {
            repository.setAiModel(model)
            appLog.info(TAG, "AI model=$model")
        }
    }

    fun setAiReasoning(reasoning: String) {
        viewModelScope.launch {
            repository.setAiReasoning(reasoning)
            appLog.info(TAG, "AI reasoning=$reasoning")
        }
    }

    fun setAiTtsVoice(voice: String) {
        viewModelScope.launch {
            repository.setAiTtsVoice(voice)
            appLog.info(TAG, "AI TTS voice=$voice")
        }
    }

    fun setAiWriteEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAiWriteEnabled(enabled)
            appLog.info(TAG, "AI writes=$enabled")
        }
    }

    fun setAiWriteDiary(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAiWriteDiary(enabled)
            appLog.info(TAG, "AI diary writes=$enabled")
        }
    }

    fun setAiWritePeople(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAiWritePeople(enabled)
            appLog.info(TAG, "AI people writes=$enabled")
        }
    }

    /**
     * Plaintext key for the reveal/copy buttons. Keystore decryption happens on
     * the IO dispatcher; callers must not log or persist the result.
     */
    suspend fun apiKeyOnce(): String? = withContext(Dispatchers.IO) { reader.apiKey() }

    suspend fun saveApiKey(key: String) {
        withContext(Dispatchers.IO) {
            reader.saveApiKey(key)
            reader.hasApiKey()
        }.also {
            _apiKeyPresent.value = it
            appLog.info(TAG, "AI API key saved")
        }
    }

    suspend fun clearApiKey() {
        withContext(Dispatchers.IO) {
            reader.clearApiKey()
        }
        _apiKeyPresent.value = false
        appLog.info(TAG, "AI API key cleared")
    }

    fun testAiConnection() {
        if (_aiTest.value.running) return
        _aiTest.value = AiTestState(running = true)
        viewModelScope.launch {
            val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            if (readiness == null || !readiness.config.isConfigured()) {
                appLog.info(TAG, "AI connection test skipped: not configured")
                _aiTest.value = AiTestState(finished = true, ok = false, code = 0)
                return@launch
            }
            val result = runCatching { aiModule.probe(readiness.config) }.getOrNull()
            appLog.info(
                TAG,
                "AI connection test ok=${result?.ok == true} code=${result?.code ?: 0}",
            )
            _aiTest.value = AiTestState(
                finished = true,
                ok = result?.ok == true,
                code = result?.code ?: 0,
            )
        }
    }

    /** Pulls the provider's model ids so the user never has to type one. */
    fun refreshAiModels() {
        if (_aiModels.value.loading) return
        _aiModels.value = AiModelsState(loading = true)
        viewModelScope.launch {
            val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            if (readiness == null || !readiness.config.isConfigured()) {
                appLog.info(TAG, "AI model fetch skipped: not configured")
                _aiModels.value = AiModelsState(failed = true)
                return@launch
            }
            val fetched = runCatching { AiModels.fetch(readiness.config) }
            val models = fetched.getOrNull().orEmpty()
            val failure = fetched.exceptionOrNull()
            if (failure == null) {
                appLog.info(TAG, "AI models fetched count=${models.size}")
            } else {
                appLog.warn(TAG, "AI model fetch failed", failure)
            }
            _aiModels.value = AiModelsState(items = models, failed = models.isEmpty())
        }
    }

    fun refreshLogSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { appLog.sizeBytes() }
            _logSizeBytes.value = bytes
            appLog.debug(TAG, "log size=$bytes")
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
