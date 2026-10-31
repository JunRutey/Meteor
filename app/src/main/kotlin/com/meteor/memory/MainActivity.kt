package com.meteor.memory

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.ui.MeteorRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * [FragmentActivity], not just ComponentActivity: the frozen
 * `AppLock.unlock(activity: FragmentActivity)` contract requires it.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLock: AppLock

    @Inject
    lateinit var appLog: AppLog

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

        // Keep a stale thumbnail out of the recents switcher while locked.
        applyScreenProtection()

        setContent {
            MeteorRoot(versionName = BuildConfig.VERSION_NAME)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate in case the user just toggled the lock in settings.
        applyScreenProtection()
    }

    private fun applyScreenProtection() {
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                runCatching { appLock.isEnabled() }.getOrDefault(false)
            }
            if (enabled) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
