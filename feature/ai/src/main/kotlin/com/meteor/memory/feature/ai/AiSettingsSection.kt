package com.meteor.memory.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The AI block inside the settings screen (ADR-024).
 *
 * Enabling always goes through the consent dialog first: the whole point is
 * that the user knows content leaves the device, and that the author collects
 * nothing. Everything is emitted upward — this composable owns no storage.
 */
@Composable
fun AiSettingsSection(
    enabled: Boolean,
    consentAccepted: Boolean,
    provider: String,
    baseUrl: String,
    model: String,
    apiKeyPresent: Boolean,
    testing: Boolean,
    testMessage: String?,
    onEnabledChange: (Boolean) -> Unit,
    onConsentAccepted: () -> Unit,
    onProviderChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConsent by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = stringResource(R.string.ai_settings_enable), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.ai_settings_enable_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { wanted ->
                    if (wanted && !consentAccepted) {
                        showConsent = true
                    } else {
                        onEnabledChange(wanted)
                    }
                },
            )
        }

        if (showConsent) {
            AlertDialog(
                onDismissRequest = { showConsent = false },
                title = { Text(text = stringResource(R.string.ai_settings_consent_title)) },
                text = { Text(text = stringResource(R.string.ai_settings_consent_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConsent = false
                            onConsentAccepted()
                            onEnabledChange(true)
                        },
                    ) {
                        Text(text = stringResource(R.string.ai_settings_consent_accept))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showConsent = false
                            onEnabledChange(false)
                        },
                    ) {
                        Text(text = stringResource(R.string.ai_settings_cancel))
                    }
                },
            )
        }

        if (enabled && consentAccepted) {
            Text(text = stringResource(R.string.ai_settings_provider), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiPresets.ids.forEach { id ->
                    val label = aiProviderLabel(id)
                    if (id == provider) {
                        Button(onClick = { onProviderChange(id) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onProviderChange(id) }) { Text(label) }
                    }
                }
            }

            if (provider == AiPresets.CUSTOM) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.ai_settings_base_url)) },
                )
            }

            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = stringResource(R.string.ai_settings_model)) },
                placeholder = { Text(text = stringResource(R.string.ai_settings_model_hint)) },
            )

            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(text = stringResource(R.string.ai_settings_key)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (apiKeyPresent) {
                    Text(
                        text = stringResource(R.string.ai_settings_key_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = {
                        onSaveApiKey(keyDraft.trim())
                        keyDraft = ""
                    },
                    enabled = keyDraft.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.ai_settings_key_save))
                }
                TextButton(onClick = onClearApiKey, enabled = apiKeyPresent) {
                    Text(text = stringResource(R.string.ai_settings_key_clear))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTestConnection, enabled = !testing) {
                    Text(
                        text = stringResource(
                            if (testing) R.string.ai_settings_testing else R.string.ai_settings_test,
                        ),
                    )
                }
                testMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                text = stringResource(R.string.ai_settings_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun aiProviderLabel(provider: String): String = stringResource(
    when (provider) {
        AiPresets.OPENAI -> R.string.ai_provider_openai
        AiPresets.CUSTOM -> R.string.ai_provider_custom
        else -> R.string.ai_provider_deepseek
    },
)
