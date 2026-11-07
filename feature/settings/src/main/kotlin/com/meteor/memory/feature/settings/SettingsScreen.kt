package com.meteor.memory.feature.settings

import android.app.TimePickerDialog
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Settings screen: appearance / security / logs / notifications / backup / AI / about.
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
    /** Debug-only extra entry; `null` keeps the section clean in release builds. */
    onOpenDiagnostics: (() -> Unit)? = null,
    backupActions: @Composable () -> Unit = {},
    logActions: @Composable () -> Unit = {},
    aiActions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
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

            ToggleRow(
                title = stringResource(R.string.settings_animations),
                subtitle = stringResource(R.string.settings_animations_desc),
                checked = settings.animationsEnabled,
                onCheckedChange = onAnimationsChanged,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_profile)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenBirthDate)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_birth_date),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_birth_date_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(text = "›", style = MaterialTheme.typography.titleMedium)
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_security)) {
            ToggleRow(
                title = stringResource(R.string.settings_app_lock),
                subtitle = stringResource(R.string.settings_app_lock_desc),
                checked = settings.appLockEnabled,
                onCheckedChange = onAppLockChanged,
            )

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

        SettingsSection(title = stringResource(R.string.settings_section_logs)) {
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
            backupActions()
        }

        SettingsSection(title = stringResource(R.string.settings_section_ai)) {
            aiActions()
        }

        SettingsSection(title = stringResource(R.string.settings_section_updates)) {
            ToggleRow(
                title = stringResource(R.string.settings_auto_check_updates),
                subtitle = stringResource(R.string.settings_auto_check_updates_desc),
                checked = settings.autoCheckForUpdates,
                onCheckedChange = onAutoCheckForUpdatesChanged,
            )
            Text(text = updateStatusText, style = MaterialTheme.typography.bodySmall)
            Text(
                text = stringResource(R.string.settings_update_install_note),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onCheckUpdates,
                enabled = !updateBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_check_updates))
            }
            if (updateCanInstall) {
                Button(onClick = onInstallUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.settings_install_update))
                }
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_about)) {
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
            OutlinedButton(onClick = onOpenDonate, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_about_donate))
            }
            OutlinedButton(onClick = onOpenChangelog, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_about_changelog))
            }

            // Debug builds only: the entry lives here now that the old home screen
            // (which owned it) is gone. ADR-016 needs the manual index rebuild.
            onOpenDiagnostics?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.settings_open_diagnostics))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                TimePickerDialog(
                    context,
                    { _, selectedHour, selectedMinute ->
                        onTimeChanged(selectedHour * 60 + selectedMinute)
                    },
                    hour,
                    minute,
                    DateFormat.is24HourFormat(context),
                ).show()
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(R.string.settings_diary_reminder_time))
        Text(
            text = DateFormat.getTimeFormat(context).format(time),
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (enabled) {
        if (!reminderNotificationsAllowed) {
            Text(
                text = stringResource(R.string.settings_diary_reminder_notification_unavailable),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onOpenNotificationSettings) {
                Text(text = stringResource(R.string.settings_open_notification_settings))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text(
                text = stringResource(
                    if (canScheduleExactAlarms) {
                        R.string.settings_exact_alarm_available
                    } else {
                        R.string.settings_exact_alarm_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
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
                    Button(
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = label)
                    }
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
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
