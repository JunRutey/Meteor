package com.meteor.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.meteor.memory.R

/**
 * The gate shown instead of the whole app when [com.meteor.memory.core.crypto.AppLock]
 * is enabled and the current session has not been unlocked.
 */
@Composable
fun LockScreen(
    failed: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    autoPromptOnShow: Boolean = true,
) {
    // Prompt immediately instead of making the user tap first. Waits for RESUMED
    // because BiometricPrompt cannot attach before the activity is resumed; a
    // cancelled prompt falls back to the manual button.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(autoPromptOnShow) {
        if (autoPromptOnShow) lifecycleOwner.lifecycle.withResumed { onUnlock() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.lock_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.lock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onUnlock,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(text = stringResource(R.string.lock_unlock))
        }
        if (failed) {
            Text(
                text = stringResource(R.string.lock_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
