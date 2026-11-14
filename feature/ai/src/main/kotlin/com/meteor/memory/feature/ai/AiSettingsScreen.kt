package com.meteor.memory.feature.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dedicated AI settings page. The main settings screen only keeps the master
 * switch, so this page owns the provider, model, reasoning, write and key
 * controls without changing their existing presentation.
 */
@Composable
fun AiSettingsScreen(
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
    onRequestApiKey: suspend () -> String? = { null },
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    val backLabel = stringResource(R.string.ai_back)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = backLabel },
                contentAlignment = Alignment.Center,
            ) {
                AiIcon(
                    AiIconKind.Back,
                    iconSize = 22.dp,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.ai_settings_entry),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
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
                            provider = provider,
                            baseUrl = baseUrl,
                            model = model,
                            reasoning = reasoning,
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
                            onProviderChange = onProviderChange,
                            onBaseUrlChange = onBaseUrlChange,
                            onModelChange = onModelChange,
                            onReasoningChange = onReasoningChange,
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
