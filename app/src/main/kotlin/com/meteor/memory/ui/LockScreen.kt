package com.meteor.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * [noDeviceCredential] is the one situation the system cannot verify against:
 * the device has neither an enrolled biometric nor a lock-screen PIN/pattern.
 * Prompting would only fail, so the screen explains the fix and offers a
 * confirmed escape instead of trapping the archive behind the gate.
 */
@Composable
fun LockScreen(
    failed: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    autoPromptOnShow: Boolean = true,
    noDeviceCredential: Boolean = false,
    onOpenSecuritySettings: () -> Unit = {},
    onDisableLock: () -> Unit = {},
) {
    // Prompt immediately instead of making the user tap first. Waits for RESUMED
    // because BiometricPrompt cannot attach before the activity is resumed; a
    // cancelled prompt falls back to the manual button. Re-runs when the device
    // gains a credential while this screen is up (return from system settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(autoPromptOnShow, noDeviceCredential) {
        if (autoPromptOnShow && !noDeviceCredential) {
            lifecycleOwner.lifecycle.withResumed { onUnlock() }
        }
    }

    var confirmDisable by remember { mutableStateOf(false) }

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
            text = stringResource(
                if (noDeviceCredential) R.string.lock_no_device_credential else R.string.lock_subtitle,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (noDeviceCredential) {
            Button(
                onClick = onOpenSecuritySettings,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(text = stringResource(R.string.lock_open_security_settings))
            }
            TextButton(
                onClick = { confirmDisable = true },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(text = stringResource(R.string.lock_disable_app_lock))
            }
        } else {
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

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(text = stringResource(R.string.lock_disable_app_lock_title)) },
            text = { Text(text = stringResource(R.string.lock_disable_app_lock_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisable = false
                        onDisableLock()
                    },
                ) {
                    Text(text = stringResource(R.string.lock_disable_app_lock_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) {
                    Text(text = stringResource(R.string.lock_disable_app_lock_cancel))
                }
            },
        )
    }
}
