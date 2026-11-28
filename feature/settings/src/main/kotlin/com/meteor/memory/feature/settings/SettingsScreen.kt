package com.meteor.memory.feature.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings screen: appearance / profile / security / AI / notifications /
 * backup / updates / diagnostics / about.
 *
 * Holds no business logic — it renders [AppSettings] and emits intents upward.
 * Sections that need a service (backup, log export) are injected as slots so
 * this module never depends on Hilt or on `:app`.
 *
 * Visual language follows the settings design sheet: 13sp slate-blue section
 * captions on the paper background, white 20dp cards, 14×16 rows separated by
 * a single hairline, and 48dp pill buttons.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    versionName: String,
    onDarkModeChanged: (DarkMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationsChanged: (Boolean) -> Unit,
    onAppLockChanged: (Boolean) -> Unit,
    onScreenshotProtectionChanged: (Boolean) -> Unit,
    onMediaPickerModeChanged: (MediaPickerMode) -> Unit,
    onVerboseLoggingChanged: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDonate: () -> Unit,
    onOpenBirthDate: () -> Unit,
    onAutoCheckForUpdatesChanged: (Boolean) -> Unit,
    updateStatusText: String,
    updateBusy: Boolean,
    /** Version shown by the update rows: the pending release, or the current one when none. */
    updateVersionName: String,
    updateAvailable: Boolean,
    updateCanInstall: Boolean,
    onCheckUpdates: () -> Unit,
    /** Downloads the release the dialog announced (ADR-072); requires [updateAvailable]. */
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    logSizeBytes: Long,
    notificationsAllowed: Boolean,
    canScheduleExactAlarms: Boolean,
    notificationPermissionDenied: Boolean,
    onNotificationsEnabledChanged: (Boolean) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onDismissNotificationPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    /** Debug-only extra entry; `null` keeps the section clean in release builds. */
    onOpenDiagnostics: (() -> Unit)? = null,
    backupActions: @Composable () -> Unit = {},
    logActions: @Composable () -> Unit = {},
    aiActions: @Composable () -> Unit = {},
    appLockUnavailable: Boolean = false,
    onDismissAppLockUnavailable: () -> Unit = {},
    onOpenSecuritySettings: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        SettingsHeader(onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                SettingsRow {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        RowLabel(text = stringResource(R.string.settings_dark_mode))
                        SegmentedChoice(
                            options = listOf(
                                DarkMode.FOLLOW_SYSTEM to stringResource(R.string.settings_dark_follow_system),
                                DarkMode.DARK to stringResource(R.string.settings_dark_dark),
                                DarkMode.LIGHT to stringResource(R.string.settings_dark_light),
                            ),
                            selected = settings.darkMode,
                            onSelect = onDarkModeChanged,
                        )
                    }
                }
                Hairline()

                // Dynamic colour is an Android 12+ capability; older releases fall back
                // to the built-in palette, so the switch would be a lie there.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ToggleRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_desc),
                        checked = settings.dynamicColor,
                        onCheckedChange = onDynamicColorChanged,
                    )
                    Hairline()
                }

                ToggleRow(
                    title = stringResource(R.string.settings_animations),
                    subtitle = stringResource(R.string.settings_animations_desc),
                    checked = settings.animationsEnabled,
                    onCheckedChange = onAnimationsChanged,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_profile)) {
                ChevronRow(
                    title = stringResource(R.string.settings_birth_date),
                    subtitle = stringResource(R.string.settings_birth_date_desc),
                    onClick = onOpenBirthDate,
                )
            }

            // Which picker the editors open (ADR-059). The in-app picker keeps
            // source metadata, which the album's "by source date" ordering needs.
            SettingsSection(title = stringResource(R.string.settings_section_media)) {
                SettingsRow {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        RowLabel(text = stringResource(R.string.settings_media_picker))
                        SegmentedChoice(
                            options = listOf(
                                MediaPickerMode.IN_APP to
                                    stringResource(R.string.settings_media_picker_in_app),
                                MediaPickerMode.SYSTEM to
                                    stringResource(R.string.settings_media_picker_system),
                            ),
                            selected = settings.mediaPickerMode,
                            onSelect = onMediaPickerModeChanged,
                        )
                        Text(
                            text = stringResource(R.string.settings_media_picker_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_security)) {
                ToggleRow(
                    title = stringResource(R.string.settings_app_lock),
                    subtitle = stringResource(R.string.settings_app_lock_desc),
                    checked = settings.appLockEnabled,
                    onCheckedChange = onAppLockChanged,
                )
                Hairline()
                ToggleRow(
                    title = stringResource(R.string.settings_screenshot_protection),
                    subtitle = stringResource(R.string.settings_screenshot_protection_desc),
                    checked = settings.screenshotProtectionEnabled,
                    onCheckedChange = onScreenshotProtectionChanged,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_ai)) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        aiActions()
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_notifications)) {
                NotificationSettingsSection(
                    enabled = settings.notificationsEnabled,
                    notificationsAllowed = notificationsAllowed,
                    canScheduleExactAlarms = canScheduleExactAlarms,
                    permissionDenied = notificationPermissionDenied,
                    onEnabledChange = onNotificationsEnabledChanged,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onDismissPermissionDenied = onDismissNotificationPermissionDenied,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_backup)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    backupActions()
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_updates)) {
                ToggleRow(
                    title = stringResource(R.string.settings_auto_check_updates),
                    subtitle = stringResource(R.string.settings_update_note_short),
                    checked = settings.autoCheckForUpdates,
                    onCheckedChange = onAutoCheckForUpdatesChanged,
                )
                Hairline()
                SettingsRow {
                    TitleBlock(
                        title = updateStatusText,
                        subtitle = stringResource(R.string.settings_update_install_note),
                    )
                    PillButton(
                        text = stringResource(R.string.settings_check_updates),
                        onClick = onCheckUpdates,
                        enabled = !updateBusy,
                    )
                }
                if (updateAvailable) {
                    Hairline()
                    SettingsRow {
                        TitleBlock(
                            title = stringResource(R.string.settings_update_available_title),
                            subtitle = stringResource(R.string.settings_update_available_body, updateVersionName),
                        )
                        Button(
                            onClick = onDownloadUpdate,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Text(text = stringResource(R.string.settings_update_download), fontSize = 15.sp)
                        }
                    }
                }
                if (updateCanInstall) {
                    Hairline()
                    SettingsRow {
                        TitleBlock(
                            title = stringResource(R.string.settings_update_ready_title),
                            subtitle = stringResource(R.string.settings_update_ready_body, updateVersionName),
                        )
                        Button(
                            onClick = onInstallUpdate,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Text(text = stringResource(R.string.settings_install_update), fontSize = 15.sp)
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_diagnostics)) {
                ToggleRow(
                    title = stringResource(R.string.settings_verbose_logging),
                    subtitle = stringResource(R.string.settings_verbose_logging_desc_short),
                    checked = settings.verboseLogging,
                    onCheckedChange = onVerboseLoggingChanged,
                )
                Hairline()
                SettingsRow {
                    Text(
                        text = stringResource(R.string.settings_log_size, formatBytes(logSizeBytes)),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    logActions()
                }
                // Debug builds only: the entry lives here now that the old home screen
                // (which owned it) is gone. ADR-016 needs the manual index rebuild.
                onOpenDiagnostics?.let { open ->
                    Hairline()
                    ChevronRow(
                        title = stringResource(R.string.settings_open_diagnostics),
                        onClick = open,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                ChevronRow(
                    title = stringResource(R.string.settings_about_version, versionName),
                    onClick = onOpenAbout,
                )
                Hairline()
                ChevronRow(
                    title = stringResource(R.string.settings_about_donate),
                    onClick = onOpenDonate,
                )
            }
        }
    }

    // Shown when the switch was refused: the app lock delegates to the system
    // and a device with no credential at all could never pass its own gate.
    if (appLockUnavailable) {
        AlertDialog(
            onDismissRequest = onDismissAppLockUnavailable,
            title = { Text(text = stringResource(R.string.settings_app_lock_unavailable_title)) },
            text = { Text(text = stringResource(R.string.settings_app_lock_unavailable_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissAppLockUnavailable()
                        onOpenSecuritySettings()
                    },
                ) {
                    Text(text = stringResource(R.string.settings_open_security_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissAppLockUnavailable) {
                    Text(text = stringResource(R.string.settings_app_lock_unavailable_dismiss))
                }
            },
        )
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            ChevronIcon(previous = true, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(20.dp),
                ),
            content = content,
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
private fun SettingsRow(
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

@Composable
private fun RowScope.TitleBlock(
    title: String,
    subtitle: String? = null,
) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
        RowLabel(text = title)
        subtitle?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowLabel(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow {
        TitleBlock(title = title, subtitle = subtitle)
        MeteorSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChevronRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    SettingsRow(onClick = onClick) {
        TitleBlock(title = title, subtitle = subtitle)
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            ChevronIcon(previous = false, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MeteorSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
private fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(20.dp),
                    )
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun ChevronIcon(previous: Boolean, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        val path = Path().apply {
            if (previous) {
                moveTo(15f * unit, 6f * unit)
                lineTo(9f * unit, 12f * unit)
                lineTo(15f * unit, 18f * unit)
            } else {
                moveTo(9f * unit, 6f * unit)
                lineTo(15f * unit, 12f * unit)
                lineTo(9f * unit, 18f * unit)
            }
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun NotificationSettingsSection(
    enabled: Boolean,
    notificationsAllowed: Boolean,
    canScheduleExactAlarms: Boolean,
    permissionDenied: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onDismissPermissionDenied: () -> Unit,
) {
    ToggleRow(
        title = stringResource(R.string.settings_notifications_enabled),
        subtitle = stringResource(R.string.settings_notifications_desc),
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )

    if (enabled) {
        if (!notificationsAllowed) {
            Hairline()
            SettingsRow {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_notifications_unavailable),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text(text = stringResource(R.string.settings_open_notification_settings))
                    }
                }
            }
        }

        // Exact alarms are only surfaced when the system withholds them; the
        // reminder still fires with inexact precision (ADR-039).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms) {
            Hairline()
            SettingsRow {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_exact_alarm_unavailable),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenExactAlarmSettings) {
                        Text(text = stringResource(R.string.settings_request_exact_alarm_settings))
                    }
                }
            }
        }
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = onDismissPermissionDenied,
            title = { Text(text = stringResource(R.string.settings_notification_permission_denied_title)) },
            text = { Text(text = stringResource(R.string.settings_notification_permission_denied_body)) },
            confirmButton = {
                TextButton(onClick = onDismissPermissionDenied) {
                    Text(text = stringResource(R.string.settings_notification_permission_dismiss))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDismissPermissionDenied()
                        onOpenNotificationSettings()
                    },
                ) {
                    Text(text = stringResource(R.string.settings_open_notification_settings))
                }
            },
        )
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
