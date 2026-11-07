package com.meteor.memory.feature.settings

import android.app.TimePickerDialog
import android.os.Build
import android.text.format.DateFormat
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
    onHideLauncherIconChanged: (Boolean) -> Unit,
    /**
     * MIUI / HyperOS only lets the dial code bring the app back once 「自启动」 and
     * 「后台弹出界面」 are granted (ADR-031), so the confirm dialog has to say so
     * and offer the two system pages. False on every other ROM.
     */
    launcherIconNeedsSystemGrants: Boolean = false,
    onOpenLauncherPermissions: () -> Unit = {},
    onOpenAutoStartSettings: () -> Unit = {},
    /**
     * Whether the system currently allows the dial-code receiver to bring the
     * app to the foreground. `BLOCKED` keeps the hide switch from being
     * confirmed; `UNKNOWN` relies on the user's own pre-hide test (ADR-031).
     */
    launcherRecovery: LauncherRecovery = LauncherRecovery.UNKNOWN,
    /**
     * True once a dial code actually returned to the app in this process. A
     * blocked launch never comes back, so this is the only proof the dialog
     * accepts before hiding the icon (ADR-031).
     */
    dialCodeVerified: Boolean = false,
    onOpenLauncherRecoverySettings: () -> Unit = {},
    onVerboseLoggingChanged: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDonate: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenBirthDate: () -> Unit,
    onAutoCheckForUpdatesChanged: (Boolean) -> Unit,
    updateStatusText: String,
    updateBusy: Boolean,
    updateCanInstall: Boolean,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    logSizeBytes: Long,
    notificationFeatureAvailable: Boolean,
    reminderNotificationsAllowed: Boolean,
    canScheduleExactAlarms: Boolean,
    notificationPermissionDenied: Boolean,
    onReminderEnabledChanged: (Boolean) -> Unit,
    onReminderTimeChanged: (Int) -> Unit,
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

            SettingsSection(title = stringResource(R.string.settings_section_security)) {
                ToggleRow(
                    title = stringResource(R.string.settings_app_lock),
                    subtitle = stringResource(R.string.settings_app_lock_desc),
                    checked = settings.appLockEnabled,
                    onCheckedChange = onAppLockChanged,
                )
                Hairline()
                HideLauncherIconRow(
                    hidden = settings.hideLauncherIcon,
                    needsSystemGrants = launcherIconNeedsSystemGrants,
                    recovery = launcherRecovery,
                    dialCodeVerified = dialCodeVerified,
                    onHiddenChange = onHideLauncherIconChanged,
                    onOpenPermissions = onOpenLauncherPermissions,
                    onOpenAutoStart = onOpenAutoStartSettings,
                    onOpenRecovery = onOpenLauncherRecoverySettings,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_ai)) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        aiActions()
                    }
                }
            }

            if (notificationFeatureAvailable) {
                SettingsSection(title = stringResource(R.string.settings_section_notifications)) {
                    ReminderSettingsSection(
                        enabled = settings.diaryReminderEnabled,
                        timeMinutes = settings.diaryReminderTimeMinutes,
                        reminderNotificationsAllowed = reminderNotificationsAllowed,
                        canScheduleExactAlarms = canScheduleExactAlarms,
                        permissionDenied = notificationPermissionDenied,
                        onEnabledChange = onReminderEnabledChanged,
                        onTimeChanged = onReminderTimeChanged,
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onDismissPermissionDenied = onDismissNotificationPermissionDenied,
                    )
                }
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
                if (updateCanInstall) {
                    Hairline()
                    SettingsRow {
                        TitleBlock(
                            title = stringResource(R.string.settings_update_ready_title),
                            subtitle = stringResource(R.string.settings_update_ready_body, versionName),
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
                Hairline()
                ChevronRow(
                    title = stringResource(R.string.settings_about_changelog),
                    onClick = onOpenChangelog,
                )
            }
        }
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
private fun ReminderSettingsSection(
    enabled: Boolean,
    timeMinutes: Int,
    reminderNotificationsAllowed: Boolean,
    canScheduleExactAlarms: Boolean,
    permissionDenied: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChanged: (Int) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onDismissPermissionDenied: () -> Unit,
) {
    val context = LocalContext.current

    ToggleRow(
        title = stringResource(R.string.settings_diary_reminder),
        subtitle = stringResource(R.string.settings_diary_reminder_desc),
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )

    val hour = timeMinutes / 60
    val minute = timeMinutes % 60
    val time = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
    }.time

    Hairline()
    SettingsRow(
        onClick = {
            TimePickerDialog(
                context,
                { _, selectedHour, selectedMinute ->
                    onTimeChanged(selectedHour * 60 + selectedMinute)
                },
                hour,
                minute,
                DateFormat.is24HourFormat(context),
            ).show()
        },
    ) {
        TitleBlock(
            title = stringResource(R.string.settings_diary_reminder_time),
            subtitle = stringResource(R.string.settings_diary_reminder_time_desc),
        )
        Text(
            text = DateFormat.getTimeFormat(context).format(time),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (enabled) {
        if (!reminderNotificationsAllowed) {
            Hairline()
            SettingsRow {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_diary_reminder_notification_unavailable),
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Hairline()
            SettingsRow {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (canScheduleExactAlarms) {
                                R.string.settings_exact_alarm_available
                            } else {
                                R.string.settings_exact_alarm_unavailable
                            },
                        ),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenExactAlarmSettings) {
                        Text(
                            text = stringResource(
                                if (canScheduleExactAlarms) {
                                    R.string.settings_open_exact_alarm_settings
                                } else {
                                    R.string.settings_request_exact_alarm_settings
                                },
                            ),
                        )
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

/**
 * Hiding the launcher entry is the only setting that can lock the user out of
 * the app, so switching it on asks for confirmation first and stays disabled
 * until a real dial-code return has been observed.
 *
 * The way back in is the dial code, which the ROM has to allow twice: the dialer
 * must dispatch it and the system must let a background receiver start an
 * activity. MIUI / HyperOS gates the second half behind 「自启动」 and
 * 「后台弹出界面」, AOSP behind 「显示在其他应用上层」. The state is checked by
 * `:app` and passed in; the dialog only renders it. On top of that `:app`
 * records a dial code that actually returned to the app, so hiding stays
 * disabled until a real launch (not just the permission) has been observed.
 * Switching it off never asks: the app is right here. See ADR-031.
 */
@Composable
private fun HideLauncherIconRow(
    hidden: Boolean,
    needsSystemGrants: Boolean,
    recovery: LauncherRecovery,
    dialCodeVerified: Boolean,
    onHiddenChange: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    ToggleRow(
        title = stringResource(R.string.settings_hide_launcher_icon),
        subtitle = stringResource(R.string.settings_hide_launcher_icon_desc),
        checked = hidden,
        onCheckedChange = { next ->
            if (next) confirming = true else onHiddenChange(false)
        },
    )

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.settings_hide_launcher_icon_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = stringResource(R.string.settings_hide_launcher_icon_confirm_body))

                    when (recovery) {
                        LauncherRecovery.READY -> Text(
                            text = stringResource(R.string.settings_hide_launcher_icon_recovery_ready),
                            style = MaterialTheme.typography.bodySmall,
                        )

                        LauncherRecovery.BLOCKED -> {
                            Text(
                                text = stringResource(R.string.settings_hide_launcher_icon_recovery_blocked),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onOpenRecovery) {
                                Text(text = stringResource(R.string.settings_hide_launcher_icon_open_recovery))
                            }
                        }

                        LauncherRecovery.UNKNOWN -> {
                            Text(
                                text = stringResource(R.string.settings_hide_launcher_icon_recovery_unknown),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onOpenRecovery) {
                                Text(text = stringResource(R.string.settings_hide_launcher_icon_open_recovery))
                            }
                        }
                    }

                    // MIUI / HyperOS answers "start the app from the background"
                    // with two switches of its own; without them the dial code is
                    // a dead end (ADR-031).
                    if (needsSystemGrants) {
                        Text(
                            text = stringResource(R.string.settings_hide_launcher_icon_grants),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onOpenPermissions) {
                                Text(text = stringResource(R.string.settings_hide_launcher_icon_open_permissions))
                            }
                            TextButton(onClick = onOpenAutoStart) {
                                Text(text = stringResource(R.string.settings_hide_launcher_icon_open_autostart))
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.settings_hide_launcher_icon_test_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(
                            if (dialCodeVerified) {
                                R.string.settings_hide_launcher_icon_test_done
                            } else {
                                R.string.settings_hide_launcher_icon_test_missing
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dialCodeVerified) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    // A recorded return is direct proof and outranks the state
                    // probe; the probe only explains why a test would fail.
                    enabled = dialCodeVerified,
                    onClick = {
                        confirming = false
                        onHiddenChange(true)
                    },
                ) {
                    Text(text = stringResource(R.string.settings_hide_launcher_icon_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.settings_hide_launcher_icon_confirm_cancel))
                }
            },
        )
    }
}
