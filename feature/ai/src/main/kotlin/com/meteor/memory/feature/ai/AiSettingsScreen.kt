package com.meteor.memory.feature.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dedicated AI settings page. The main settings screen only keeps the master
 * switch, so this page owns the model, reasoning, read-aloud voice, write and
 * key controls without changing their existing presentation.
 */
@Composable
fun AiSettingsScreen(
    enabled: Boolean,
    consentAccepted: Boolean,
    model: String,
    reasoning: String,
    ttsVoice: String,
    apiKeyPresent: Boolean,
    models: List<String> = emptyList(),
    modelsLoading: Boolean = false,
    modelsFailed: Boolean = false,
    writeEnabled: Boolean = false,
    writeDiary: Boolean = true,
    writePeople: Boolean = true,
    testing: Boolean,
    testMessage: String?,
    onModelChange: (String) -> Unit,
    onReasoningChange: (String) -> Unit,
    onTtsVoiceChange: (String) -> Unit,
    onRefreshModels: () -> Unit = {},
    onWriteChange: (Boolean) -> Unit = {},
    onWriteDiaryChange: (Boolean) -> Unit = {},
    onWritePeopleChange: (Boolean) -> Unit = {},
    onSaveApiKey: suspend (String) -> Unit,
    onClearApiKey: suspend () -> Unit,
    onRequestApiKey: suspend () -> String? = { null },
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiSubPageHeader(
            title = stringResource(R.string.ai_settings_entry),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            DeepSeekCard()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (enabled && consentAccepted) {
                        AiSettingsSection(
                            enabled = enabled,
                            consentAccepted = consentAccepted,
                            model = model,
                            reasoning = reasoning,
                            ttsVoice = ttsVoice,
                            apiKeyPresent = apiKeyPresent,
                            models = models,
                            modelsLoading = modelsLoading,
                            modelsFailed = modelsFailed,
                            writeEnabled = writeEnabled,
                            writeDiary = writeDiary,
                            writePeople = writePeople,
                            testing = testing,
                            testMessage = testMessage,
                            onEnabledChange = {},
                            onConsentAccepted = {},
                            onModelChange = onModelChange,
                            onReasoningChange = onReasoningChange,
                            onTtsVoiceChange = onTtsVoiceChange,
                            onRefreshModels = onRefreshModels,
                            onWriteChange = onWriteChange,
                            onWriteDiaryChange = onWriteDiaryChange,
                            onWritePeopleChange = onWritePeopleChange,
                            onSaveApiKey = onSaveApiKey,
                            onClearApiKey = onClearApiKey,
                            onRequestApiKey = onRequestApiKey,
                            onTestConnection = onTestConnection,
                            modifier = Modifier.fillMaxWidth(),
                            showMasterSwitch = false,
                            animationsEnabled = animationsEnabled,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.ai_need_consent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeepSeekCard() {
    val uriHandler = LocalUriHandler.current
    val openPlatform = {
        runCatching { uriHandler.openUri(DEEPSEEK_PLATFORM_URL) }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AiIcon(
                            AiIconKind.Sparkle,
                            iconSize = 20.dp,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DeepSeek",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.ai_deepseek_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_deepseek_official_platform),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(role = Role.Button) { openPlatform() },
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button) { openPlatform() }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(18.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "?",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.ai_get_ds_api),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private const val DEEPSEEK_PLATFORM_URL = "https://platform.deepseek.com/"
