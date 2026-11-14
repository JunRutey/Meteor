package com.meteor.memory

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.ui.MeteorRoot
import dagger.hilt.android.AndroidEntryPoint
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            MeteorRoot(versionName = BuildConfig.VERSION_NAME)
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
    }
}
