package com.meteor.memory.ui

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.system.LauncherIconController
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Activity-scoped state for the app shell: the lock gate and the preferences
 * that drive theming.
 *
 * [locked] is nullable on purpose: `null` means "not resolved yet". Rendering
 * the unlocked UI before the persisted lock preference has been read would show
 * one frame of plaintext content on every cold start.
 *
 * The settings DataStore is the single source of truth (archive R11); this view
 * model mirrors it into [AppLock], which owns its own keystore-backed storage
 * and cannot depend on `:feature-settings` itself. It mirrors
 * [AppSettings.hideLauncherIcon] onto the launcher alias the same way (ADR-031).
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val appLock: AppLock,
    private val launcherIcon: LauncherIconController,
    private val settingsRepository: SettingsRepository,
    private val appLog: AppLog,
    private val searchService: SearchService,
) : ViewModel() {

    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    private val _unlockFailed = MutableStateFlow(false)
    val unlockFailed: StateFlow<Boolean> = _unlockFailed.asStateFlow()

    /** Guards against re-locking while a biometric check is in flight. */
    @Volatile
    private var unlocking: Boolean = false

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    init {
        viewModelScope.launch {
            // v0.7.1: the AI entry now defaults to off. Users who already
            // accepted the consent warning keep it; the settings flow re-emits
            // once this one-time write lands.
            runCatching { settingsRepository.migrateAiDefaultToOff() }
        }

        viewModelScope.launch {
            // Verbose logging must be active from process start, before any
            // screen is opened.
            settingsRepository.settings
                .map { it.verboseLogging }
                .distinctUntilChanged()
                .collect { appLog.setVerbose(it) }
        }

        viewModelScope.launch {
            // ADR-031: the launcher alias is derived state. Re-applying it on
            // every process start keeps the icon honest even when the package
            // manager state was reset (data restore, an OEM launcher re-enabling
            // the component after an update).
            settingsRepository.settings
                .map { it.hideLauncherIcon }
                .distinctUntilChanged()
                .collect { hidden ->
                    val changed = withContext(Dispatchers.IO) {
                        runCatching { launcherIcon.sync(hidden) }
                            .onFailure { appLog.warn(LAUNCHER_TAG, "launcher icon sync failed", it) }
                            .getOrDefault(false)
                    }
                    if (changed) appLog.info(LAUNCHER_TAG, "launcher icon hidden=$hidden")
                }
        }

        viewModelScope.launch {
            // Wait for the first persisted value, then keep AppLock in sync.
            settingsRepository.settings
                .map { it.appLockEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    withContext(Dispatchers.IO) {
                        runCatching { appLock.setEnabled(enabled) }
                    }
                    appLog.info(TAG, "app lock enabled=$enabled")
                    // Enabling locks straight away; disabling drops the gate.
                    _locked.value = enabled
                    if (!enabled) scheduleIndexRebuild()
                }
        }
    }

    fun unlock(activity: FragmentActivity) {
        if (unlocking) return
        unlocking = true
        viewModelScope.launch {
            try {
                val granted = runCatching { appLock.unlock(activity) }.getOrDefault(false)
                if (granted) {
                    appLog.info(TAG, "unlock granted")
                    _unlockFailed.value = false
                    _locked.value = false
                    scheduleIndexRebuild()
                } else {
                    appLog.warn(TAG, "unlock denied or cancelled")
                    _unlockFailed.value = true
                }
            } finally {
                unlocking = false
            }
        }
    }

    /**
     * Called when the app leaves the foreground: re-arms the gate so returning
     * requires a fresh biometric check instead of reusing the session shortcut.
     */
    fun onEnterBackground() {
        // A BiometricPrompt can push the activity to STOPPED; never re-lock
        // while a check is already in flight or the prompt would loop.
        if (unlocking) return

        viewModelScope.launch {
            val wasEnabled = withContext(Dispatchers.IO) {
                runCatching {
                    if (appLock.isEnabled()) {
                        appLock.lock()
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            searchService.clearAttachmentIndex()
            if (wasEnabled) {
                appLog.info(TAG, "entered background, lock re-armed")
                _locked.value = true
            }
        }
    }

    private companion object {
        const val TAG = "AppLock"
        const val LAUNCHER_TAG = "LauncherIcon"
    }

    /**
     * Rebuild once per process, and only after the content is actually
     * reachable (unlocked). [IndexBootstrap] damps repeated failures.
     */
    private fun scheduleIndexRebuild() {
        if (indexRebuildStarted) return
        indexRebuildStarted = true
        viewModelScope.launch { searchService.rebuildOnLaunch() }
    }

    @Volatile
    private var indexRebuildStarted: Boolean = false
}
