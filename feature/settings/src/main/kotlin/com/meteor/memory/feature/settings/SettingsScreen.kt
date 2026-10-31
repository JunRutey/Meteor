package com.meteor.memory.feature.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Settings screen: 外观 / 安全 / 日志 / 备份 / AI / 关于.
 *
 * Holds no business logic — it renders [AppSettings] and emits intents upward.
 * Sections that need a service (backup, log export) are injected as slots so
 * this module never depends on Hilt or on `:app`.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    versionName: String,
    onDarkModeChanged: (DarkMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAppLockChanged: (Boolean) -> Unit,
    onVerboseLoggingChanged: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    logSizeBytes: Long,
    modifier: Modifier = Modifier,
    backupActions: @Composable () -> Unit = {},
    logActions: @Composable () -> Unit = {},
    aiActions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_section_appearance))

        ChoiceRow(
            title = stringResource(R.string.settings_dark_mode),
            options = listOf(
                DarkMode.FOLLOW_SYSTEM to stringResource(R.string.settings_dark_follow_system),
                DarkMode.DARK to stringResource(R.string.settings_dark_dark),
                DarkMode.LIGHT to stringResource(R.string.settings_dark_light),
            ),
            selected = settings.darkMode,
            onSelect = onDarkModeChanged,
        )

        // Dynamic colour is an Android 12+ capability; older releases fall back
        // to the built-in palette, so the switch would be a lie there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ToggleRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_desc),
                checked = settings.dynamicColor,
                onCheckedChange = onDynamicColorChanged,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_security))
        ToggleRow(
            title = stringResource(R.string.settings_app_lock),
            subtitle = stringResource(R.string.settings_app_lock_desc),
            checked = settings.appLockEnabled,
            onCheckedChange = onAppLockChanged,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_logs))
        ToggleRow(
            title = stringResource(R.string.settings_verbose_logging),
            subtitle = stringResource(R.string.settings_verbose_logging_desc),
            checked = settings.verboseLogging,
            onCheckedChange = onVerboseLoggingChanged,
        )
        Text(
            text = stringResource(R.string.settings_log_size, formatBytes(logSizeBytes)),
            style = MaterialTheme.typography.bodySmall,
        )
        logActions()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_backup))
        backupActions()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_ai))
        aiActions()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle(stringResource(R.string.settings_section_about))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAbout)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_about_version, versionName),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(text = "›", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Byte counts are units, not prose, so they stay out of string resources. */
private fun formatBytes(bytes: Long): String {
    val locale = java.util.Locale.getDefault()
    return when {
        bytes >= 1024L * 1024L -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(locale, "%.1f KB", bytes / 1024.0)
        else -> String.format(locale, "%d B", bytes)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                if (value == selected) {
                    Button(onClick = { onSelect(value) }) { Text(text = label) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }) { Text(text = label) }
                }
            }
        }
    }
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
