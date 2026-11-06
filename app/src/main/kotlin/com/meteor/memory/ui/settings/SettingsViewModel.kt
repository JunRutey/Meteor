package com.meteor.memory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.ai.AiModels
import com.meteor.memory.feature.ai.AiModule
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.DarkMode
import com.meteor.memory.feature.settings.FontScale
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.system.LauncherPermissionGuide
import com.meteor.memory.system.NotificationFeature
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

/** Model ids fetched from the provider; failure keeps manual entry possible. */
data class AiModelsState(
    val loading: Boolean = false,
    val items: List<String> = emptyList(),
    val failed: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val appLog: AppLog,
    private val reader: AiSettingsReader,
    private val aiModule: AiModule,
    private val launcherPermissionGuide: LauncherPermissionGuide,
    private val notificationFeature: NotificationFeature,
) : ViewModel() {

    /**
     * True on MIUI / HyperOS, where hiding the icon only stays recoverable once
     * the user turns on 「自启动」 and 「后台弹出界面」 (ADR-031). The ROM does not
     * change at runtime, so this is read once.
     */
    val launcherIconNeedsSystemGrants: Boolean = launcherPermissionGuide.needsManualGrants()
    val notificationFeatureAvailable: Boolean = notificationFeature.available

    private val _notificationsAllowed = MutableStateFlow(notificationFeature.canPostNotifications())
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed.asStateFlow()

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

    /**
     * Records the choice only: `com.meteor.memory.ui.RootViewModel` is the
     * single writer of the launcher component state, so the switch and the icon
     * cannot disagree (ADR-031).
     */
    fun setHideLauncherIcon(hidden: Boolean) {
        viewModelScope.launch { repository.setHideLauncherIcon(hidden) }
    }

    /** Opens MIUI's permission editor; falls back to the app info screen. */
    fun openLauncherPermissionEditor() {
        launcherPermissionGuide.openAppPermissionEditor()
    }

    /** Opens MIUI's auto-start manager; falls back to the permission editor. */
    fun openAutoStartSettings() {
        launcherPermissionGuide.openAutoStartSettings()
    }

    fun setDiaryReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDiaryReminderEnabled(enabled)
            notificationFeature.syncReminder()
            refreshNotificationPermissions()
        }
    }

    fun setDiaryReminderTimeMinutes(minutes: Int) {
        viewModelScope.launch {
            repository.setDiaryReminderTimeMinutes(minutes)
            notificationFeature.syncReminder()
        }
    }

    fun refreshNotificationPermissions() {
        _notificationsAllowed.value = notificationFeature.canPostNotifications()
        _canScheduleExactAlarms.value = notificationFeature.canScheduleExactAlarms()
        viewModelScope.launch { notificationFeature.syncReminder() }
    }

    fun openNotificationSettings() {
        notificationFeature.openNotificationSettings()
    }

    fun openExactAlarmSettings() {
        notificationFeature.openExactAlarmSettings()
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

    fun setAiReasoning(reasoning: String) {
        viewModelScope.launch { repository.setAiReasoning(reasoning) }
    }

    fun setAiWriteEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAiWriteEnabled(enabled) }
    }

    fun setAiWriteDiary(enabled: Boolean) {
        viewModelScope.launch { repository.setAiWriteDiary(enabled) }
    }

    fun setAiWritePeople(enabled: Boolean) {
        viewModelScope.launch { repository.setAiWritePeople(enabled) }
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
        }.also { _apiKeyPresent.value = it }
    }

    suspend fun clearApiKey() {
        withContext(Dispatchers.IO) {
            reader.clearApiKey()
        }
        _apiKeyPresent.value = false
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

    /** Pulls the provider's model ids so the user never has to type one. */
    fun refreshAiModels() {
        if (_aiModels.value.loading) return
        _aiModels.value = AiModelsState(loading = true)
        viewModelScope.launch {
            val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            if (readiness == null || !readiness.config.isConfigured()) {
                _aiModels.value = AiModelsState(failed = true)
                return@launch
            }
            val models = runCatching { AiModels.fetch(readiness.config) }.getOrNull().orEmpty()
            _aiModels.value = AiModelsState(items = models, failed = models.isEmpty())
        }
    }

    fun refreshLogSize() {
        viewModelScope.launch {
            _logSizeBytes.value = withContext(Dispatchers.IO) { appLog.sizeBytes() }
        }
    }
}
