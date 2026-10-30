package com.meteor.memory.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Settings screen skeleton: 外观 / 安全 / 备份 / 关于.
 *
 * The AI section is added in v0.6. This composable holds no business logic —
 * it renders [AppSettings] and emits intents upward.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    versionName: String,
    onAnimationsChanged: (Boolean) -> Unit,
    onAutoBackupChanged: (Boolean) -> Unit,
    onAppLockChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_section_appearance))
        ToggleRow(
            title = stringResource(R.string.settings_animations),
            subtitle = stringResource(R.string.settings_animations_desc),
            checked = settings.animationsEnabled,
            onCheckedChange = onAnimationsChanged,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_security))
        ToggleRow(
            title = stringResource(R.string.settings_app_lock),
            subtitle = stringResource(R.string.settings_app_lock_desc),
            checked = settings.appLockEnabled,
            onCheckedChange = onAppLockChanged,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_backup))
        ToggleRow(
            title = stringResource(R.string.settings_auto_backup),
            subtitle = stringResource(R.string.settings_auto_backup_desc),
            checked = settings.autoBackupEnabled,
            onCheckedChange = onAutoBackupChanged,
        )
        Text(
            text = stringResource(R.string.settings_backup_retention, settings.backupRetention),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_about))
        Text(
            text = stringResource(R.string.settings_about_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
