package com.meteor.memory

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.system.AndroidNotificationFeature
import com.meteor.memory.ui.MeteorRoot
import com.meteor.memory.ui.question.QuestionOpenRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [FragmentActivity], not just ComponentActivity: the frozen
 * `AppLock.unlock(activity: FragmentActivity)` contract requires it.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLog: AppLog

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /**
     * Receives "open this question" taps from a 每日一问 reminder notification
     * (ADR-068).
     *
     * The notification cannot navigate on its own: it is delivered by the system
     * while this activity may be dead or already showing another screen. So the
     * tap is turned into a pending request here — in both `onCreate` (cold start
     * from the notification) and `onNewIntent` (already running) — and the
     * navigation graph consumes it once it exists.
     */
    @Inject
    lateinit var questionOpenRequest: QuestionOpenRequest

    /**
     * Set once [MeteorRoot] has resolved the lock preference and the onboarding
     * gate. Until then the system splash keeps the Meteor icon on screen, so no
     * placeholder or half-resolved gate is ever shown. This is **not** an unlock.
     */
    @Volatile
    private var contentReady: Boolean = false

    /**
     * Set by the 5-second safety valve. It releases the splash even if the root
     * state never finishes. The lock gate below is still the only thing that
     * decides whether content may render.
     */
    @Volatile
    private var splashTimedOut: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the platform splash is adopted
        // instead of the plain window background (ADR-038). It stays until the
        // shell knows what it may render; the condition is a cheap volatile read.
        installSplashScreen().setKeepOnScreenCondition {
            !contentReady && !splashTimedOut
        }

        super.onCreate(savedInstanceState)

        // Cold start from a 每日一问 reminder (ADR-068). Handled before setContent
        // so the first composition already sees the pending request and opens the
        // question instead of flashing the calendar and then navigating.
        handleNotificationIntent(intent)

        // Edge-to-edge in one place (ADR-024): the app draws behind the bars and
        // MeteorRoot pads by the insets. Without this the client still believes the
        // decor fits the system windows, and Android falls back to *panning* the
        // whole window — header included — when the IME appears.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        appLog.info(
            "AppStart",
            "Meteor ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MODEL}",
        )

        // Start secure: the persisted switch arrives asynchronously from
        // DataStore, and the launch frame must not be capturable before it does.
        // If the preference cannot be read, this conservative flag stays on.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        lifecycleScope.launch {
            settingsRepository.settings
                .map { it.screenshotProtectionEnabled }
                .distinctUntilChanged()
                .catch { failure ->
                    appLog.warn(TAG, "screenshot protection preference unavailable", failure)
                }
                .collect { enabled -> applyScreenshotProtection(enabled) }
        }

        setContent {
            MeteorRoot(
                versionName = BuildConfig.VERSION_NAME,
                onContentReady = { contentReady = true },
            )
        }

        // Safety valve: if the root state never resolves, hand over to the
        // shell anyway instead of leaving the splash up forever. The lock gate
        // still decides what may be rendered; releasing the splash unlocks
        // nothing, and the life tab falls back to its own loading state.
        lifecycleScope.launch {
            delay(SPLASH_MAX_WAIT_MS)
            splashTimedOut = true
            contentReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        appLog.info(LIFECYCLE_TAG, "activity started")
    }

    /**
     * The app is already running and the person tapped a reminder notification.
     *
     * `launchMode` is the default, so the notification's intent arrives here as a
     * *new* intent on the existing instance (it carries
     * `FLAG_ACTIVITY_SINGLE_TOP`). Not handling this would make a tap do nothing
     * whenever Meteor happened to be in the foreground or background stack.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep Activity#getIntent() truthful for the next recreation.
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * Turns an "open question" action into a pending navigation request.
     *
     * Anything else — the launcher intent, the update download notification — is
     * ignored here so this stays a single narrow branch rather than a routing
     * table.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action != AndroidNotificationFeature.ACTION_OPEN_QUESTION) return
        val questionId = intent.getStringExtra(AndroidNotificationFeature.EXTRA_QUESTION_ID)
        if (questionId.isNullOrBlank()) {
            appLog.warn(TAG, "reminder tap without a question id")
            return
        }
        appLog.info(TAG, "reminder tap opened question")
        questionOpenRequest.request(questionId)
    }

    override fun onStop() {
        appLog.info(LIFECYCLE_TAG, "activity stopped")
        super.onStop()
    }

    /**
     * Low-memory events explain later image/AI failures that otherwise look
     * random in a bug report.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appLog.debug(LIFECYCLE_TAG, "onTrimMemory level=$level")
        }
    }

    /**
     * `FLAG_SECURE` blocks screenshots and recording, and the platform also
     * blanks the recent-tasks preview. Applied from the settings flow so a
     * toggle takes effect while this activity is still on screen.
     */
    private fun applyScreenshotProtection(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private companion object {
        const val TAG = "ScreenPrivacy"
        const val LIFECYCLE_TAG = "Lifecycle"
        const val SPLASH_MAX_WAIT_MS = 5_000L
    }
}
