package com.meteor.memory.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meteor.memory.BuildConfig
import com.meteor.memory.R
import com.meteor.memory.feature.settings.SettingsScreen
import com.meteor.memory.ui.backup.BackupActions
import com.meteor.memory.ui.diagnostics.StorageDiagnosticsScreen
import com.meteor.memory.ui.diary.DiaryEditScreen
import com.meteor.memory.ui.diary.DiaryEditViewModel
import com.meteor.memory.ui.diary.DiaryListScreen
import com.meteor.memory.ui.diary.DiaryListViewModel
import com.meteor.memory.ui.log.LogActions
import com.meteor.memory.ui.settings.SettingsViewModel
import java.time.LocalDate

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DIARY = "diary"
private const val ROUTE_DIARY_EDIT = "diary/edit"
private const val ROUTE_DIAGNOSTICS = "diagnostics"

/** Shown in the About section; must match README.md. */
private const val REPOSITORY_URL = "https://github.com/JunRutey/Meteor"

private fun Context.openInBrowser(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun MeteorNavHost(versionName: String, animationsEnabled: Boolean = true) {
    val navController = rememberNavController()

    // Archive R11: turning animations off must kill screen transitions too.
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (animationsEnabled) fadeIn(tween(durationMillis = 220)) else EnterTransition.None
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (animationsEnabled) fadeOut(tween(durationMillis = 180)) else ExitTransition.None
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = enter,
        popExitTransition = exit,
    ) {
        composable(ROUTE_HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenDiary = { navController.navigate(ROUTE_DIARY) },
                onOpenDiagnostics = if (BuildConfig.DEBUG) {
                    { navController.navigate(ROUTE_DIAGNOSTICS) }
                } else {
                    null
                },
            )
        }

        composable(ROUTE_DIAGNOSTICS) {
            StorageDiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(ROUTE_SETTINGS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val logSizeBytes by viewModel.logSizeBytes.collectAsStateWithLifecycle()
            val context = LocalContext.current

            RefreshOnResume { viewModel.refreshLogSize() }

            SettingsScreen(
                settings = settings,
                versionName = versionName,
                onDarkModeChanged = viewModel::setDarkMode,
                onDynamicColorChanged = viewModel::setDynamicColor,
                onFontScaleChanged = viewModel::setFontScale,
                onAnimationsChanged = viewModel::setAnimationsEnabled,
                onAppLockChanged = viewModel::setAppLockEnabled,
                onVerboseLoggingChanged = viewModel::setVerboseLogging,
                onOpenRepository = { context.openInBrowser(REPOSITORY_URL) },
                logSizeBytes = logSizeBytes,
                backupActions = { BackupActions() },
                logActions = { LogActions() },
            )
        }

        composable(ROUTE_DIARY) {
            val viewModel: DiaryListViewModel = hiltViewModel()
            val rows by viewModel.rows.collectAsStateWithLifecycle()
            val loading by viewModel.loading.collectAsStateWithLifecycle()

            // Re-read the directory whenever the screen comes back to the front,
            // so an entry saved in the editor shows up immediately.
            RefreshOnResume { viewModel.refresh() }

            DiaryListScreen(
                rows = rows,
                loading = loading,
                onOpen = { date -> navController.navigate("$ROUTE_DIARY_EDIT/$date") },
                onWriteToday = { navController.navigate("$ROUTE_DIARY_EDIT/${LocalDate.now()}") },
            )
        }

        composable(
            route = "$ROUTE_DIARY_EDIT/{${DiaryEditViewModel.ARG_DATE}}",
            arguments = listOf(navArgument(DiaryEditViewModel.ARG_DATE) { type = NavType.StringType }),
        ) {
            val viewModel: DiaryEditViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            DiaryEditScreen(
                state = state,
                onTextChanged = viewModel::onTextChanged,
                onSave = viewModel::save,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun RefreshOnResume(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
        Text(text = stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium)

        Button(onClick = onOpenDiary, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.home_open_diary))
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
            Text(text = stringResource(R.string.home_open_settings))
        }
        onOpenDiagnostics?.let { open ->
            OutlinedButton(onClick = open, modifier = Modifier.padding(top = 8.dp)) {
                Text(text = stringResource(R.string.home_open_diagnostics))
            }
        }
    }
}
