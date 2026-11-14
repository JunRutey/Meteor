package com.meteor.memory.feature.ai

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The AI block inside the settings screen (ADR-027).
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
    reasoning: String,
    apiKeyPresent: Boolean,
    models: List<String> = emptyList(),
    modelsLoading: Boolean = false,
    modelsFailed: Boolean = false,
    writeEnabled: Boolean = false,
    writeDiary: Boolean = true,
    writePeople: Boolean = true,
    testing: Boolean,
    testMessage: String?,
    onEnabledChange: (Boolean) -> Unit,
    onConsentAccepted: () -> Unit,
    onProviderChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onReasoningChange: (String) -> Unit,
    onRefreshModels: () -> Unit = {},
    onWriteChange: (Boolean) -> Unit = {},
    onWriteDiaryChange: (Boolean) -> Unit = {},
    onWritePeopleChange: (Boolean) -> Unit = {},
    onSaveApiKey: suspend (String) -> Unit,
    onClearApiKey: suspend () -> Unit,
    /** Plaintext key for reveal/copy; must already run off the main thread. */
    onRequestApiKey: suspend () -> String? = { null },
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier,
    /** The standalone AI settings page renders the details only; the master switch stays in Settings. */
    showMasterSwitch: Boolean = true,
    animationsEnabled: Boolean = true,
) {
    var showModels by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }

    // The saved key stays masked by default. It is only held in memory while the
    // settings block is composed, and the reveal resets when the key changes.
    var storedKey by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var keyActionFailed by remember { mutableStateOf(false) }
    var keyActionInProgress by remember { mutableStateOf(false) }
    var keyVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(apiKeyPresent, keyVersion) {
        storedKey = if (apiKeyPresent) onRequestApiKey() else null
        revealed = false
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_BADGE_MS)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = if (animationsEnabled) {
                    tween(durationMillis = 180, easing = FastOutSlowInEasing)
                } else {
                    snap()
                },
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showMasterSwitch) {
            AiMasterSwitchRow(
                enabled = enabled,
                consentAccepted = consentAccepted,
                onEnabledChange = onEnabledChange,
                onConsentAccepted = onConsentAccepted,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onRefreshModels, enabled = !modelsLoading) {
                    Text(
                        text = stringResource(
                            if (modelsLoading) {
                                R.string.ai_settings_models_loading
                            } else {
                                R.string.ai_settings_models_fetch
                            },
                        ),
                    )
                }
                if (models.isNotEmpty()) {
                    Box {
                        OutlinedButton(onClick = { showModels = true }) {
                            Text(stringResource(R.string.ai_settings_models_pick, models.size))
                        }
                        DropdownMenu(
                            expanded = showModels,
                            onDismissRequest = { showModels = false },
                        ) {
                            models.forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(id) },
                                    onClick = {
                                        showModels = false
                                        onModelChange(id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (modelsFailed) {
                Text(
                    text = stringResource(R.string.ai_settings_models_failed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = stringResource(R.string.ai_settings_reasoning),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReasoningMode.entries.forEach { mode ->
                    val label = reasoningLabel(mode)
                    if (mode.wire == reasoning) {
                        Button(onClick = { onReasoningChange(mode.wire) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onReasoningChange(mode.wire) }) { Text(label) }
                    }
                }
            }
            Text(
                text = stringResource(R.string.ai_settings_reasoning_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                        shape = MaterialTheme.shapes.medium,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.ai_settings_write),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.ai_settings_write_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = writeEnabled, onCheckedChange = onWriteChange)
            }

            // Granular grants (ADR-030): the master switch alone no longer means
            // "the model may rewrite everything".
            AnimatedVisibility(
                visible = writeEnabled,
                enter = if (animationsEnabled) {
                    fadeIn(tween(140, easing = FastOutSlowInEasing)) +
                        slideInVertically(
                            initialOffsetY = { it / 24 },
                            animationSpec = tween(170, easing = FastOutSlowInEasing),
                        )
                } else {
                    EnterTransition.None
                },
                exit = if (animationsEnabled) {
                    fadeOut(tween(90, easing = FastOutSlowInEasing)) +
                        slideOutVertically(
                            targetOffsetY = { it / 24 },
                            animationSpec = tween(120, easing = FastOutSlowInEasing),
                        )
                } else {
                    ExitTransition.None
                },
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleRow(
                        title = stringResource(R.string.ai_settings_write_diary),
                        subtitle = stringResource(R.string.ai_settings_write_diary_desc),
                        checked = writeDiary,
                        onCheckedChange = onWriteDiaryChange,
                    )
                    ToggleRow(
                        title = stringResource(R.string.ai_settings_write_people),
                        subtitle = stringResource(R.string.ai_settings_write_people_desc),
                        checked = writePeople,
                        onCheckedChange = onWritePeopleChange,
                    )
                }
            }

            if (apiKeyPresent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                storedKey == null -> stringResource(R.string.ai_settings_key_loading)
                                revealed -> storedKey.orEmpty()
                                else -> maskApiKey(storedKey.orEmpty())
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(
                            stringResource(
                                if (revealed) {
                                    R.string.ai_settings_key_hide
                                } else {
                                    R.string.ai_settings_key_show
                                },
                            ),
                        )
                    }
                    TextButton(
                        onClick = {
                            val key = storedKey.orEmpty()
                            if (key.isNotBlank()) {
                                copied = copySecret(context, key)
                                keyActionFailed = !copied
                            }
                        },
                        enabled = storedKey != null,
                    ) {
                        Text(
                            stringResource(
                                if (copied) {
                                    R.string.ai_settings_key_copied
                                } else {
                                    R.string.ai_settings_key_copy
                                },
                            ),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.ai_settings_key_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Paste works like any text field; the draft is never echoed back.
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(text = stringResource(R.string.ai_settings_key_new)) },
                placeholder = { Text(text = stringResource(R.string.ai_settings_key_new_hint)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            keyActionInProgress = true
                            keyActionFailed = false
                            try {
                                onSaveApiKey(keyDraft.trim())
                                keyDraft = ""
                                storedKey = null
                                revealed = false
                                // Same presence, new value: force the masked preview to reload.
                                keyVersion++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                keyActionFailed = true
                            } finally {
                                keyActionInProgress = false
                            }
                        }
                    },
                    enabled = keyDraft.isNotBlank() && !keyActionInProgress,
                ) {
                    Text(text = stringResource(R.string.ai_settings_key_save))
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            keyActionInProgress = true
                            keyActionFailed = false
                            try {
                                onClearApiKey()
                                storedKey = null
                                revealed = false
                                keyDraft = ""
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                keyActionFailed = true
                            } finally {
                                keyActionInProgress = false
                            }
                        }
                    },
                    enabled = apiKeyPresent && !keyActionInProgress,
                ) {
                    Text(text = stringResource(R.string.ai_settings_key_clear))
                }
            }
            if (keyActionFailed) {
                Text(
                    text = stringResource(R.string.ai_settings_key_action_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
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
private fun AiMasterSwitchRow(
    enabled: Boolean,
    consentAccepted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConsentAccepted: () -> Unit,
) {
    var showConsent by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = stringResource(R.string.ai_settings_enable),
                style = MaterialTheme.typography.bodyLarge,
            )
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
}

/**
 * Settings entry: only the master switch plus a jump to the dedicated AI page.
 * The detailed provider/model/key controls live there, not inline.
 */
@Composable
fun AiSettingsSummary(
    enabled: Boolean,
    consentAccepted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConsentAccepted: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canOpen = enabled && consentAccepted
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AiMasterSwitchRow(
            enabled = enabled,
            consentAccepted = consentAccepted,
            onEnabledChange = onEnabledChange,
            onConsentAccepted = onConsentAccepted,
        )
        AiSettingsEntryRow(
            enabled = canOpen,
            subtitle = stringResource(
                if (canOpen) {
                    R.string.ai_settings_summary_ready
                } else {
                    R.string.ai_settings_summary_locked
                },
            ),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun AiSettingsEntryRow(
    enabled: Boolean,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ai_settings_entry),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
        AiIcon(
            AiIconKind.ChevronRight,
            iconSize = 18.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
internal fun reasoningLabel(mode: ReasoningMode): String = stringResource(
    when (mode) {
        ReasoningMode.AUTO -> R.string.ai_reasoning_auto
        ReasoningMode.OFF -> R.string.ai_reasoning_off
        ReasoningMode.LOW -> R.string.ai_reasoning_low
        ReasoningMode.HIGH -> R.string.ai_reasoning_high
        ReasoningMode.MAX -> R.string.ai_reasoning_max
    },
)

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * `sk-********1a2b`: enough for the user to recognise which key is stored,
 * never enough to reuse it. Keys too short to split are hidden completely.
 */
internal fun maskApiKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.length <= MASK_HEAD_CHARS + MASK_TAIL_CHARS) return MASK_CHAR.repeat(MASK_DOTS)
    return trimmed.take(MASK_HEAD_CHARS) + MASK_CHAR.repeat(MASK_DOTS) + trimmed.takeLast(MASK_TAIL_CHARS)
}

/** Marks the clip as sensitive on Android 13+, so it is not previewed in the UI. */
private fun copySecret(context: Context, key: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val clip = ClipData.newPlainText(context.getString(R.string.ai_settings_key_clip_label), key)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    return runCatching { clipboard.setPrimaryClip(clip) }.isSuccess
}

private const val MASK_CHAR = "*"
private const val MASK_HEAD_CHARS = 3
private const val MASK_TAIL_CHARS = 4
private const val MASK_DOTS = 8
private const val COPIED_BADGE_MS = 1_500L
