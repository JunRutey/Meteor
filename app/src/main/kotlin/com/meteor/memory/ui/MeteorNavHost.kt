package com.meteor.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meteor.memory.R
import com.meteor.memory.feature.settings.SettingsScreen
import com.meteor.memory.ui.settings.SettingsViewModel

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun MeteorNavHost(versionName: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            HomeScreen(onOpenSettings = { navController.navigate(ROUTE_SETTINGS) })
        }
        composable(ROUTE_SETTINGS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            SettingsScreen(
                settings = settings,
                versionName = versionName,
                onAnimationsChanged = viewModel::setAnimationsEnabled,
                onAutoBackupChanged = viewModel::setAutoBackupEnabled,
                onAppLockChanged = viewModel::setAppLockEnabled,
            )
        }
    }
}

@Composable
private fun HomeScreen(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
        Text(text = stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.home_open_settings))
        }
    }
}
