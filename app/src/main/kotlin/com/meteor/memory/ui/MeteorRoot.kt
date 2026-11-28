package com.meteor.memory.ui

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.memory.R
import com.meteor.memory.feature.media.ProvideMediaHost
import com.meteor.memory.feature.settings.DarkMode
import com.meteor.memory.feature.settings.FontScale
import com.meteor.memory.ui.life.OnboardingFlowScreen
import com.meteor.memory.ui.life.OnboardingLoadFailureScreen
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
fun MeteorRoot(versionName: String, onContentReady: () -> Unit = {}) {
    val viewModel: RootViewModel = hiltViewModel()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val unlockFailed by viewModel.unlockFailed.collectAsStateWithLifecycle()
    val noDeviceCredential by viewModel.noDeviceCredential.collectAsStateWithLifecycle()
    val onboardingGate by viewModel.onboardingGate.collectAsStateWithLifecycle()
    val updatePrompt by viewModel.updatePrompt.collectAsStateWithLifecycle()
    val updateDownload by viewModel.updateDownload.collectAsStateWithLifecycle()

    // Cold-start splash hand-over (ADR-038). The system splash may leave only
    // once the shell knows whether to show the lock gate or the onboarding
    // flow; this flag says "resolved", not "unlocked".
    var contentReadyReported by remember { mutableStateOf(false) }
    LaunchedEffect(locked, onboardingGate) {
        if (!contentReadyReported &&
            locked != null &&
            onboardingGate != OnboardingGate.LOADING
        ) {
            contentReadyReported = true
            onContentReady()
        }
    }

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

    val view = LocalView.current
    val windowContext = LocalContext.current
    val hideStatusBar = settings.hideStatusBar
    val currentHideStatusBar = rememberUpdatedState(hideStatusBar)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onEnterBackground()
                // Return from system settings: the credential the user just set
                // up has to become usable without restarting the app.
                // We are the foreground app again, so an ON_STOP from now on is a
                // real background trip and must arm the gate even if a screen we
                // started ourselves (the share sheet) triggered the previous one.
                Lifecycle.Event.ON_START -> {
                    viewModel.onReturnToForeground()
                    viewModel.refreshLockAvailability()
                    applyStatusBarVisibility(windowContext, view, currentHideStatusBar.value)
                }
                // Some OEM share sheets are dialogs, so they only pause us and
                // never produce ON_STOP; coming back then only produces ON_RESUME.
                // Both events call the same idempotent handler, which is what
                // closes the "own system screen" window in that case.
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onReturnToForeground()
                    // Some shells restore status-bar visibility when the window
                    // regains focus; re-apply the preference on every resume.
                    applyStatusBarVisibility(windowContext, view, currentHideStatusBar.value)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 「隐藏状态栏」is a window-level preference, so it is applied once here
    // instead of every screen asking for the inset controller. A LaunchedEffect
    // keyed by the preference (not a SideEffect) is what keeps a transient swipe
    // reveal usable: showing the bar changes the insets, which recomposes, and a
    // SideEffect would immediately hide it again.
    LaunchedEffect(hideStatusBar, view, windowContext) {
        applyStatusBarVisibility(windowContext, view, hideStatusBar)
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
                    // screen never has to guess how the keyboard was handled. The
                    // drawer escapes this padding on its own when the status bar is
                    // hidden, so the home header keeps its original offset.
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                when (locked) {
                    // Show only the app name, never a frame of decrypted content.
                    null -> StartupPlaceholder()

                    true -> {
                        val context = LocalContext.current
                        val activity = context as? FragmentActivity
                        LockScreen(
                            failed = unlockFailed,
                            onUnlock = { activity?.let(viewModel::unlock) },
                            noDeviceCredential = noDeviceCredential,
                            onOpenSecuritySettings = { openSecuritySettings(context) },
                            onDisableLock = viewModel::disableAppLockWithoutCredential,
                        )
                    }

                    false -> when (onboardingGate) {
                        OnboardingGate.LOADING -> StartupPlaceholder()
                        OnboardingGate.REQUIRED -> OnboardingFlowScreen()
                        // Media composables live deep inside the feature modules and
                        // take no store parameter (ADR-052 §4), so the archive handles
                        // are installed once here as a CompositionLocal. The update
                        // dialog (ADR-072) is a sibling on purpose: it opens its own
                        // window, and being inside the COMPLETE branch means it can
                        // never appear over the lock gate or the onboarding flow.
                        OnboardingGate.COMPLETE -> {
                            ProvideMediaHost(viewModel.mediaHost) {
                                MeteorNavHost(
                                    versionName = versionName,
                                    animationsEnabled = settings.animationsEnabled,
                                    aiEnabled = settings.aiEnabled,
                                    mediaPickerMode = settings.mediaPickerMode,
                                    drawerOrder = settings.drawerOrder,
                                    drawerVisibleItems = settings.drawerVisibleItems,
                                    drawerFullscreen = settings.hideStatusBar,
                                    onSystemScreenOpened = viewModel::beginSystemTransition,
                                )
                            }
                            updatePrompt?.let { prompt ->
                                UpdatePromptDialog(
                                    versionName = prompt.versionName,
                                    notes = prompt.notes,
                                    onDownload = viewModel::acceptUpdatePrompt,
                                    onLater = viewModel::dismissUpdatePrompt,
                                    onSkip = viewModel::skipUpdatePrompt,
                                )
                            }
                            updateDownload?.let { progress ->
                                UpdateProgressDialog(
                                    progress = progress,
                                    onDismiss = viewModel::dismissDownloadProgressToNotification,
                                )
                            }
                        }

                        OnboardingGate.FAILED -> OnboardingLoadFailureScreen(
                            onRetry = viewModel::retryOnboardingState,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Applies the 「隐藏状态栏」 preference to the current window. Kept outside the
 * composable so both the immediate effect and the start/resume fallback share
 * one implementation.
 */
private fun applyStatusBarVisibility(context: Context, view: View, hidden: Boolean) {
    val window = (context as? Activity)?.window ?: return
    WindowCompat.getInsetsController(window, view).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            hide(WindowInsetsCompat.Type.statusBars())
        } else {
            show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

@Composable
private fun StartupPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
