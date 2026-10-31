package com.meteor.memory.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.memory.feature.settings.DarkMode
import com.meteor.memory.feature.settings.FontScale
import com.meteor.memory.ui.theme.MeteorTheme

/**
 * App shell above the NavHost: themes from [RootViewModel.settings] and shows
 * the lock gate when the session is locked.
 *
 * The app is edge-to-edge (ADR-024), so the **only** inset handling lives here:
 * `WindowInsets.safeDrawing` covers status/navigation bars, display cutout and the
 * keyboard. Individual screens must not add their own `imePadding()`.
 */
@Composable
fun MeteorRoot(versionName: String) {
    val viewModel: RootViewModel = hiltViewModel()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val unlockFailed by viewModel.unlockFailed.collectAsStateWithLifecycle()

    val darkTheme = when (settings.darkMode) {
        DarkMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
    }

    val fontScale = when (settings.fontScale) {
        FontScale.SYSTEM, FontScale.NORMAL -> 1f
        FontScale.SMALL -> 0.9f
        FontScale.LARGE -> 1.15f
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onEnterBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MeteorTheme(
        darkTheme = darkTheme,
        animationsEnabled = settings.animationsEnabled,
        dynamicColor = settings.dynamicColor,
        fontScale = fontScale,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // safeDrawing = system bars + display cutout + IME: one padding
                    // for everything the system can cover. Applied once here so a
                    // screen never has to guess how the keyboard was handled.
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                when (locked) {
                    // Preference not read yet: show nothing rather than a frame
                    // of decrypted content on cold start.
                    null -> Unit

                    true -> {
                        val activity = LocalContext.current as? FragmentActivity
                        LockScreen(
                            failed = unlockFailed,
                            onUnlock = { activity?.let(viewModel::unlock) },
                        )
                    }

                    false -> MeteorNavHost(
                        versionName = versionName,
                        animationsEnabled = settings.animationsEnabled,
                    )
                }
            }
        }
    }
}
