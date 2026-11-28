package com.meteor.memory.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 更多外观设置（1.7.4）: the appearance rows that do not need to be on the
 * first settings page. The theme choice stays on the main page; these are the
 * secondary switches the person rarely touches.
 */
@Composable
fun AppearanceSettingsScreen(
    settings: AppSettings,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationsChanged: (Boolean) -> Unit,
    onHideStatusBarChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SettingsHeader(
            onBack = onBack,
            title = stringResource(R.string.settings_more_appearance),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                // Dynamic colour is an Android 12+ capability; older releases
                // fall back to the built-in palette, so the switch would lie.
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
                Hairline()
                ToggleRow(
                    title = stringResource(R.string.settings_hide_status_bar),
                    subtitle = stringResource(R.string.settings_hide_status_bar_desc),
                    checked = settings.hideStatusBar,
                    onCheckedChange = onHideStatusBarChanged,
                )
            }
        }
    }
}

/**
 * 更多通知设置（1.7.4）: the main page keeps only the master switch; the
 * per-reminder switches and the system-permission guidance live here.
 */
@Composable
fun NotificationSettingsScreen(
    settings: AppSettings,
    notificationsAllowed: Boolean,
    canScheduleExactAlarms: Boolean,
    onDiaryReminderEnabledChanged: (Boolean) -> Unit,
    onQuestionReminderEnabledChanged: (Boolean) -> Unit,
    onUpdateNotificationEnabledChanged: (Boolean) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = settings.notificationsEnabled
    Column(modifier = modifier.fillMaxSize()) {
        SettingsHeader(
            onBack = onBack,
            title = stringResource(R.string.settings_more_notifications),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            if (!enabled) {
                Text(
                    text = stringResource(R.string.settings_notifications_detail_off),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsSection(title = stringResource(R.string.settings_section_notifications)) {
                ToggleRow(
                    title = stringResource(R.string.settings_diary_reminder),
                    subtitle = stringResource(R.string.settings_diary_reminder_desc),
                    checked = settings.diaryReminderEnabled,
                    onCheckedChange = onDiaryReminderEnabledChanged,
                    enabled = enabled,
                )
                Hairline()
                ToggleRow(
                    title = stringResource(R.string.settings_question_reminder),
                    subtitle = stringResource(R.string.settings_question_reminder_desc),
                    checked = settings.questionReminderEnabled,
                    onCheckedChange = onQuestionReminderEnabledChanged,
                    enabled = enabled,
                )
                Hairline()
                ToggleRow(
                    title = stringResource(R.string.settings_update_notification),
                    subtitle = stringResource(R.string.settings_update_notification_desc),
                    checked = settings.updateNotificationEnabled,
                    onCheckedChange = onUpdateNotificationEnabledChanged,
                    enabled = enabled,
                )
                if (enabled) {
                    if (!notificationsAllowed) {
                        Hairline()
                        StatusActionRow(
                            text = stringResource(R.string.settings_notifications_unavailable),
                            action = stringResource(R.string.settings_open_notification_settings),
                            onAction = onOpenNotificationSettings,
                        )
                    }
                    // Exact alarms are only surfaced when the system withholds
                    // them; reminders still fire with inexact precision.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms) {
                        Hairline()
                        StatusActionRow(
                            text = stringResource(R.string.settings_exact_alarm_unavailable),
                            action = stringResource(R.string.settings_request_exact_alarm_settings),
                            onAction = onOpenExactAlarmSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusActionRow(
    text: String,
    action: String,
    onAction: () -> Unit,
) {
    SettingsRow {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAction) {
                Text(text = action)
            }
        }
    }
}
