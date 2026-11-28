package com.meteor.memory.ui

import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.BuildConfig
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.crypto.MediaKeyStore
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.feature.media.MediaHost
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.system.LegacyInstallDetector
import com.meteor.memory.update.UpdateService
import com.meteor.memory.update.UpdateState
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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
 * model mirrors the app-lock preference into [AppLock], which owns keystore-backed
 * storage and cannot depend on `:feature-settings` itself.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val appLock: AppLock,
    private val settingsRepository: SettingsRepository,
    private val appLog: AppLog,
    private val searchService: SearchService,
    private val updateService: UpdateService,
    private val legacyInstallDetector: LegacyInstallDetector,
    private val mediaKeyStore: MediaKeyStore,
    @ApplicationContext context: Context,
    mediaStore: MediaStore,
    imageStore: ImageStore,
) : ViewModel() {

    /**
     * Archive handles for the media composables (ADR-052 §4). Built here rather
     * than in Compose so the cache directory and the already-injected stores are
     * reused instead of being rebuilt per composition.
     */
    val mediaHost: MediaHost = MediaHost(
        mediaStore = mediaStore,
        imageStore = imageStore,
        cacheDir = context.cacheDir,
    )

    private val _onboardingGate = MutableStateFlow(OnboardingGate.LOADING)
    val onboardingGate: StateFlow<OnboardingGate> = _onboardingGate.asStateFlow()

    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    private val _unlockFailed = MutableStateFlow(false)
    val unlockFailed: StateFlow<Boolean> = _unlockFailed.asStateFlow()

    /**
     * True when the gate is armed but the system has nothing to verify the
     * person against: no enrolled biometric and no lock-screen PIN/pattern.
     * Only then may the lock screen offer the "disable the lock" escape, since
     * keeping the gate would otherwise strand the archive forever.
     */
    private val _noDeviceCredential = MutableStateFlow(false)
    val noDeviceCredential: StateFlow<Boolean> = _noDeviceCredential.asStateFlow()

    /**
     * The update prompt to show, or null when nothing is being asked (ADR-072).
     *
     * Driven by the update service reaching `Available` and suppressed for a
     * version the person already skipped or already answered once this session;
     * the dialog itself renders at the shell level in [MeteorRoot].
     */
    private val _updatePrompt = MutableStateFlow<UpdatePrompt?>(null)
    val updatePrompt: StateFlow<UpdatePrompt?> = _updatePrompt.asStateFlow()

    /**
     * The foreground download dialog (1.7.1). Non-null only while the download
     * was accepted from the prompt and has not yet been sent to the background:
     * dismissing it, leaving the app or finishing the download all clear it.
     */
    private val _updateDownload = MutableStateFlow<UpdateDownloadProgress?>(null)
    val updateDownload: StateFlow<UpdateDownloadProgress?> = _updateDownload.asStateFlow()

    /**
     * True once this download was handed over to the notification. It is reset
     * when a new download starts, so a later check can show the dialog again.
     */
    @Volatile
    private var downloadDialogDismissed: Boolean = false

    /** Whether the activity is started; only then may the dialog auto-install. */
    @Volatile
    private var appForeground: Boolean = false

    /** Guards against re-locking while a biometric check is in flight. */
    @Volatile
    private var unlocking: Boolean = false

    /**
     * Deadline ([SystemClock.elapsedRealtime]) until which an ON_STOP does not
     * re-arm the gate. Set by [beginSystemTransition] as soon as a screen of our
     * own has been started, cleared by [onReturnToForeground]. A plain timestamp
     * rather than a boolean so a launch that never produces a matching ON_START
     * cannot leave the gate disabled.
     */
    @Volatile
    private var systemTransitionUntil: Long = 0L

    /**
     * `elapsedRealtime` of the ON_STOP whose re-lock was skipped because of
     * [systemTransitionUntil], or 0 when no lock is owed.
     *
     * This is the safety net for the window: the share sheet produces no second
     * ON_STOP, so a person who presses Home from the sheet leaves the activity
     * STOPPED with the gate held open. Comparing this timestamp against the
     * return time lets [onReturnToForeground] apply the lock that was skipped,
     * once the trip is clearly no longer "just sharing".
     */
    @Volatile
    private var backgroundSince: Long = 0L

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    init {
        // Media files use a random data key wrapped by the Keystore key (ADR-063).
        // Generating it during startup on an IO thread keeps the first media
        // import from paying for it. Only the failure is logged — never the key.
        // A damaged key file must stay visible instead of being silently replaced,
        // so the warm-up only reports the error.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mediaKeyStore.ensureGenerated() }
                .onFailure { appLog.warn(TAG, "media key warm-up failed", it) }
        }

        // The gate must not resolve before the upgrade migration has run, so
        // both steps share one coroutine and keep LOADING until both are done.
        viewModelScope.launch {
            markLegacyInstallAsOnboarded()
            settingsRepository.migrateAiToDeepSeekOnly()
            settingsRepository.migrateNotificationsMasterSwitch()
            settingsRepository.settings
                .map { it.onboardingCompleted }
                .distinctUntilChanged()
                .catch { failure ->
                    appLog.error(TAG, "onboarding state unavailable", failure)
                    _onboardingGate.value = OnboardingGate.FAILED
                }
                .collect { completed ->
                    _onboardingGate.value =
                        if (completed) OnboardingGate.COMPLETE else OnboardingGate.REQUIRED
                }
        }

        viewModelScope.launch {
            // Debug builds use a different signing key, so a release APK could
            // never be installed over them; the automatic check is pointless.
            if (BuildConfig.DEBUG) return@launch
            val autoCheckEnabled = try {
                settingsRepository.settings.first().autoCheckForUpdates
            } catch (failure: IOException) {
                appLog.warn(UPDATE_TAG, "could not read update preference", failure)
                false
            }
            if (autoCheckEnabled) {
                try {
                    settingsRepository.settings.first { it.onboardingCompleted }
                    if (settingsRepository.settings.first().autoCheckForUpdates) {
                        updateService.checkOnLaunch()
                    }
                } catch (failure: IOException) {
                    appLog.warn(UPDATE_TAG, "could not wait for onboarding before update check", failure)
                }
            }
        }

        // Update prompts are shell-level (ADR-072): any check landing on a new
        // version asks here. A manual check always asks; the automatic launch
        // check stays quiet for a version it already prompted about.
        viewModelScope.launch {
            updateService.state.collect { state ->
                when (state) {
                    is UpdateState.Available ->
                        askAboutUpdate(state.versionName, state.manual, state.notes)
                    is UpdateState.Downloading -> updateDownloadProgress(state)
                    is UpdateState.ReadyToInstall -> finishForegroundDownload(state.versionName)
                    is UpdateState.Failed -> {
                        // A failed download has no progress to show and no
                        // ready notification to fall back on; close both
                        // dialogs so the person is not left with a dead bar.
                        _updateDownload.value = null
                        _updatePrompt.value = null
                    }

                    is UpdateState.InstallPermissionRequired -> _updateDownload.value = null
                    else -> Unit
                }
            }
        }

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
            // Wait for the first persisted value, then keep AppLock in sync.
            var startupValueApplied = false
            settingsRepository.settings
                .map { it.appLockEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    withContext(Dispatchers.IO) {
                        runCatching { appLock.setEnabled(enabled) }
                    }
                    appLog.info(TAG, "app lock enabled=$enabled")
                    if (!startupValueApplied) {
                        startupValueApplied = true
                        // Cold start: a stored lock is honoured right away.
                        _locked.value = enabled
                        if (enabled) updateLockAvailability() else scheduleIndexRebuild()
                    } else if (enabled) {
                        // Turned on from Settings by the person holding the phone.
                        // Swapping the screen for the gate here would throw away
                        // the open settings page and immediately demand a
                        // fingerprint; instead the gate arms on the next cold
                        // start or as soon as the app goes to the background.
                        appLog.info(TAG, "app lock armed for next launch")
                    } else {
                        // Turning it off drops the gate immediately.
                        _locked.value = false
                        scheduleIndexRebuild()
                    }
                }
        }
    }

    fun unlock(activity: FragmentActivity) {
        if (unlocking) return
        unlocking = true
        viewModelScope.launch {
            try {
                // Check before prompting: without any credential the system can
                // only error out, and the gate must explain why instead of
                // pretending the attempt failed.
                if (!isAuthenticationAvailable()) {
                    appLog.warn(TAG, "unlock impossible: no biometric or lock screen")
                    _noDeviceCredential.value = true
                    return@launch
                }
                _noDeviceCredential.value = false
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
     * Re-checks whether the system can still verify identity, e.g. after the
     * person returned from setting up a PIN in system settings.
     */
    fun refreshLockAvailability() {
        if (_locked.value != true) return
        viewModelScope.launch { updateLockAvailability() }
    }

    /**
     * Last-resort escape shown only when the device has no lock screen and no
     * enrolled biometric: there is nothing the system could verify against, so
     * a lock nobody can pass would brick the archive. The lock screen asks for
     * explicit confirmation first and this is logged as a warning.
     */
    fun disableAppLockWithoutCredential() {
        viewModelScope.launch {
            appLog.warn(TAG, "disabling app lock without a device credential to avoid a lockout")
            runCatching { settingsRepository.setAppLockEnabled(false) }
            withContext(Dispatchers.IO) {
                runCatching { appLock.setEnabled(false) }
            }
            _noDeviceCredential.value = false
            _unlockFailed.value = false
            _locked.value = false
            scheduleIndexRebuild()
        }
    }

    private suspend fun isAuthenticationAvailable(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { appLock.isAuthenticationAvailable() }.getOrDefault(false)
        }

    private suspend fun updateLockAvailability() {
        if (_locked.value != true) {
            _noDeviceCredential.value = false
            return
        }
        _noDeviceCredential.value = !isAuthenticationAvailable()
    }

    /**
     * Called when the app leaves the foreground: re-arms the gate so returning
     * requires a fresh biometric check instead of reusing the session shortcut.
     */
    fun onEnterBackground() {
        appForeground = false
        // Leaving the app turns the foreground progress dialog into the live
        // notification. It deliberately does not come back on return: the
        // download was handed over, and the notification is now the surface.
        if (_updateDownload.value != null) {
            appLog.info(UPDATE_TAG, "app backgrounded, download progress moved to notification")
            downloadDialogDismissed = true
            _updateDownload.value = null
        }

        // A BiometricPrompt can push the activity to STOPPED; never re-lock
        // while a check is already in flight or the prompt would loop.
        if (unlocking) return

        // Our own share sheet also stops the activity. Re-arming the gate there
        // would swap the NavHost for the lock screen, throwing away the viewer
        // (and the share that is still being handed over); the person comes back
        // to a lock screen, or to the home tab after unlocking, instead of the
        // image they just shared. Keep the session for that one transition only:
        // the window closes on the next ON_START, and [onReturnToForeground]
        // still applies the skipped lock when the trip was long.
        val keepSession = systemScreenOnTop()
        if (keepSession) {
            appLog.info(TAG, "background transition from our own system screen, gate stays open")
            // Remember when the re-lock was skipped: only that lock can still be
            // owed, and only [onReturnToForeground] can notice it went unapplied.
            backgroundSince = SystemClock.elapsedRealtime()
        }

        viewModelScope.launch {
            val wasEnabled = withContext(Dispatchers.IO) {
                runCatching {
                    if (keepSession || !appLock.isEnabled()) {
                        false
                    } else {
                        appLock.lock()
                        true
                    }
                }.getOrDefault(false)
            }
            // Decrypted attachment text must not outlive the foreground even when
            // the gate is skipped; the index rebuilds lazily on the next search.
            // This runs on every background transition — including the one the
            // share sheet causes — so an uncaught throw here would crash the app
            // the moment the sheet opens. Cancellation is rethrown rather than
            // logged: it means the scope is going away, not that the clear failed.
            try {
                searchService.clearAttachmentIndex()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                appLog.warn(TAG, "attachment index clear failed", failure)
            }
            if (wasEnabled) {
                appLog.info(TAG, "entered background, lock re-armed")
                _locked.value = true
                updateLockAvailability()
            }
        }
    }

    /**
     * Records that we just handed the foreground to a screen we started ourselves
     * (the share sheet), so the [onEnterBackground] that follows must not re-arm
     * the gate.
     */
    fun beginSystemTransition() {
        systemTransitionUntil = SystemClock.elapsedRealtime() + SYSTEM_SCREEN_GRACE_MS
    }

    /**
     * Called when the activity is the foreground app again — driven by **both**
     * ON_START and ON_RESUME.
     *
     * Always closes the "own system screen" window, so the next background trip
     * arms the gate normally. ON_RESUME is what covers dialog-style share sheets
     * (some OEMs inject them): they only pause us, so coming back produces
     * ON_RESUME without any ON_START, and the window would otherwise stay armed
     * and swallow the next real background trip. Calling this from both events is
     * idempotent — it returns immediately once [backgroundSince] is 0.
     *
     * And if the trip was long, it applies the re-lock that the window skipped: a
     * person who left through the share sheet by pressing Home never produces
     * another ON_STOP, and the archive would stay unlocked for the rest of the
     * process lifetime.
     */
    fun onReturnToForeground() {
        appForeground = true
        val since = backgroundSince
        backgroundSince = 0L
        systemTransitionUntil = 0L
        if (since == 0L) return
        if (SystemClock.elapsedRealtime() - since < SHARE_ROUNDTRIP_MAX_MS) return

        viewModelScope.launch {
            // Already gated by a normal ON_STOP: nothing to redo.
            if (_locked.value == true) return@launch
            val reArmed = withContext(Dispatchers.IO) {
                runCatching {
                    if (appLock.isEnabled()) {
                        // lock(), not just the flag: AppLock.unlock() short-circuits
                        // while the session is still marked unlocked, so the gate
                        // would dismiss itself without asking for anything.
                        appLock.lock()
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            if (!reArmed) return@launch
            appLog.info(TAG, "long background trip, lock re-armed on return")
            _locked.value = true
            updateLockAvailability()
        }
    }

    private fun systemScreenOnTop(): Boolean =
        SystemClock.elapsedRealtime() < systemTransitionUntil

    fun retryOnboardingState() {
        _onboardingGate.value = OnboardingGate.LOADING
        viewModelScope.launch {
            try {
                val completed = settingsRepository.settings
                    .map { it.onboardingCompleted }
                    .first()
                _onboardingGate.value =
                    if (completed) OnboardingGate.COMPLETE else OnboardingGate.REQUIRED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.error(TAG, "onboarding state retry failed", failure)
                _onboardingGate.value = OnboardingGate.FAILED
            }
        }
    }

    /**
     * Asks about a newly found version unless it is the one the person already
     * skipped persistently or already answered since this process started
     * (ADR-072). A manual re-check that finds the same release therefore lands
     * on the settings row instead of interrupting with the dialog again.
     */
    private suspend fun askAboutUpdate(versionName: String, manual: Boolean, notes: String) {
        // The automatic launch check asks once per process; a manual 「检查更新」
        // must be able to bring the dialog back after 「稍后再说」, otherwise the
        // only visible path would be a parked settings row.
        if (!manual && versionName == lastPromptedVersion) return
        lastPromptedVersion = versionName
        val skipped = runCatching { settingsRepository.skippedUpdateVersion() }.getOrNull()
        if (versionName == skipped) {
            appLog.info(UPDATE_TAG, "version $versionName is skipped, no prompt")
            return
        }
        appLog.info(UPDATE_TAG, "asking about update $versionName (manual=$manual)")
        _updatePrompt.value = UpdatePrompt(versionName, notes)
    }

    /** 「立即更新」: keeps the dialog open as a foreground progress bar. */
    fun acceptUpdatePrompt() {
        val prompt = _updatePrompt.value ?: return
        _updatePrompt.value = null
        downloadDialogDismissed = false
        _updateDownload.value = UpdateDownloadProgress(
            versionName = prompt.versionName,
            downloadedBytes = 0,
            totalBytes = 0,
        )
        appLog.info(UPDATE_TAG, "update to ${prompt.versionName} accepted")
        viewModelScope.launch { updateService.startDownload() }
    }

    private fun updateDownloadProgress(state: UpdateState.Downloading) {
        // The dialog only exists for a download the person started from the
        // prompt. Once it was sent to the notification (back press / leaving
        // the app) it must stay there, so returning does not pop it again.
        if (downloadDialogDismissed || !appForeground) return
        val current = _updateDownload.value ?: return
        _updateDownload.value = current.copy(
            versionName = state.versionName,
            downloadedBytes = state.downloadedBytes,
            totalBytes = state.totalBytes,
        )
    }

    /**
     * The download finished while the progress dialog was still in front.
     * That is the one case that opens the system installer automatically; if
     * the person already sent the download to the notification, the ready
     * notification (and the settings install row) remains the entry point.
     */
    private fun finishForegroundDownload(versionName: String) {
        val visible = _updateDownload.value ?: return
        if (downloadDialogDismissed || !appForeground) return
        _updateDownload.value = null
        downloadDialogDismissed = true
        appLog.info(UPDATE_TAG, "foreground download finished for $versionName, opening installer")
        updateService.installDownloadedUpdate()
    }

    /**
     * 「后台下载」/ back / leaving the app: the dialog disappears and the live
     * notification (already posted by the service) becomes the only surface.
     */
    fun dismissDownloadProgressToNotification() {
        if (_updateDownload.value == null) return
        appLog.info(UPDATE_TAG, "download handed to the background notification")
        downloadDialogDismissed = true
        _updateDownload.value = null
    }

    /** 「稍后再说」and outside-tap: ask again the next time a check finds it. */
    fun dismissUpdatePrompt() {
        _updatePrompt.value = null
    }

    /** 「忽略此版本」: persist the version so future checks stay quiet about it. */
    fun skipUpdatePrompt() {
        val prompt = _updatePrompt.value ?: return
        _updatePrompt.value = null
        appLog.info(UPDATE_TAG, "version ${prompt.versionName} skipped")
        viewModelScope.launch {
            runCatching { settingsRepository.setSkippedUpdateVersion(prompt.versionName) }
                .onFailure { failure ->
                    appLog.warn(UPDATE_TAG, "could not persist skipped update", failure)
                }
        }
    }

    /**
     * v0.9.0 added the first-launch gate. Installs that already hold archive
     * records predate it, so they skip the introduction instead of being asked
     * for a birth date before they can reach their diary.
     */
    private suspend fun markLegacyInstallAsOnboarded() {
        val hasArchive = withContext(Dispatchers.IO) {
            runCatching { legacyInstallDetector.hasArchiveRecords() }.getOrDefault(false)
        }
        if (!hasArchive) return
        val alreadyCompleted = try {
            settingsRepository.settings.first().onboardingCompleted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            appLog.warn(ONBOARDING_TAG, "could not read onboarding state", failure)
            false
        }
        if (alreadyCompleted) return
        try {
            settingsRepository.setOnboardingCompleted(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            appLog.warn(ONBOARDING_TAG, "could not migrate onboarding state", failure)
        }
    }

    private companion object {
        const val TAG = "AppLock"
        const val UPDATE_TAG = "AppUpdate"
        const val ONBOARDING_TAG = "Onboarding"

        /**
         * How long a screen we started ourselves may keep the gate open.
         *
         * Deliberately generous: the ON_STOP we are covering is delivered only
         * once the share sheet is actually on screen, so the gap between
         * `startActivity` and that callback is the sheet's cold start — hundreds
         * of milliseconds to a few seconds, longer on a loaded device. A tight
         * window would simply reopen the original bug on slow devices.
         *
         * Widening the window does not widen the leak surface, because the window
         * is closed by the next foreground event rather than by the clock: the
         * dialog-style panels that never stop us at all are handled from the other
         * side, since [onReturnToForeground] also runs on ON_RESUME.
         */
        const val SYSTEM_SCREEN_GRACE_MS = 60_000L

        /**
         * Longest gap that still counts as "the person just shared something".
         *
         * Only consulted on the "own system screen" path ([beginSystemTransition]):
         * every other background trip is locked immediately by [onEnterBackground].
         * A normal share round trip is seconds to about a minute — sheet up, target
         * picked, back — so three minutes is far beyond it; past that the app was
         * out of sight long enough for someone else to have picked the phone up,
         * and the skipped lock is applied on return.
         */
        const val SHARE_ROUNDTRIP_MAX_MS = 3 * 60 * 1000L
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

    /** Version the dialog last asked about in this process; blocks repeat prompts. */
    @Volatile
    private var lastPromptedVersion: String? = null
}

/** One pending "a new version exists" question, held until the dialog answers it. */
data class UpdatePrompt(
    val versionName: String,
    /** GitHub release body, shown in a fixed scrollable area of the dialog. */
    val notes: String = "",
)

/**
 * Foreground download numbers. [totalBytes] is 0 while the source has not
 * announced a size, in which case the dialog shows an indeterminate bar.
 */
data class UpdateDownloadProgress(
    val versionName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes > 0) {
            ((downloadedBytes.coerceAtLeast(0) * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

enum class OnboardingGate { LOADING, REQUIRED, COMPLETE, FAILED }
