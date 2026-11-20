package com.meteor.memory.ui

import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.BuildConfig
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.system.LegacyInstallDetector
import com.meteor.memory.update.UpdateService
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

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
}

enum class OnboardingGate { LOADING, REQUIRED, COMPLETE, FAILED }
