package com.meteor.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.meteor.memory.ui.MeteorNavHost
import com.meteor.memory.ui.theme.MeteorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v0.1 skeleton: theme follows system. Once :feature-settings is wired
        // into the composition root, MeteorTheme() reads SettingsRepository.
        setContent {
            MeteorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MeteorNavHost(versionName = BuildConfig.VERSION_NAME)
                }
            }
        }
    }
}
