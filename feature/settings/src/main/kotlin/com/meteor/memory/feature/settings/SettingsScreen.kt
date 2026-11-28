package com.meteor.memory.feature.settings

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
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
    onAppLockChanged: (Boolean) -> Unit,
    onScreenshotProtectionChanged: (Boolean) -> Unit,
    onMediaPickerModeChanged: (MediaPickerMode) -> Unit,
    onVerboseLoggingChanged: (Boolean) -> Unit,
    onOpenAppearanceSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDonate: () -> Unit,
    onOpenBirthDate: () -> Unit,
    onAutoCheckForUpdatesChanged: (Boolean) -> Unit,
    updateStatusText: String,
    updateBusy: Boolean,
    /** Version shown by the install row: the pending release, or the current one when none. */
    updateVersionName: String,
    updateCanInstall: Boolean,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    logSizeBytes: Long,
    notificationPermissionDenied: Boolean,
    onNotificationsEnabledChanged: (Boolean) -> Unit,
    onOpenNotificationDetail: () -> Unit,
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
        SettingsHeader(
            onBack = onBack,
            title = stringResource(R.string.settings_title),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_dark_mode),
                    options = listOf(
                        ChoiceOption(
                            value = DarkMode.FOLLOW_SYSTEM,
                            label = stringResource(R.string.settings_dark_follow_system),
                        ),
                        ChoiceOption(
                            value = DarkMode.DARK,
                            label = stringResource(R.string.settings_dark_dark),
                        ),
                        ChoiceOption(
                            value = DarkMode.LIGHT,
                            label = stringResource(R.string.settings_dark_light),
                        ),
                    ),
                    selected = settings.darkMode,
                    icon = { mode -> ThemeChoiceGlyph(mode) },
                    onSelect = onDarkModeChanged,
                )
                Hairline()
                ChevronRow(
                    title = stringResource(R.string.settings_more_appearance),
                    subtitle = stringResource(R.string.settings_more_appearance_desc),
                    onClick = onOpenAppearanceSettings,
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
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_media_picker),
                    options = listOf(
                        ChoiceOption(
                            value = MediaPickerMode.IN_APP,
                            label = stringResource(R.string.settings_media_picker_in_app),
                            description = stringResource(R.string.settings_media_picker_in_app_desc),
                        ),
                        ChoiceOption(
                            value = MediaPickerMode.SYSTEM,
                            label = stringResource(R.string.settings_media_picker_system),
                            description = stringResource(R.string.settings_media_picker_system_desc),
                        ),
                    ),
                    selected = settings.mediaPickerMode,
                    onSelect = onMediaPickerModeChanged,
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
                ToggleRow(
                    title = stringResource(R.string.settings_notifications_enabled),
                    subtitle = stringResource(R.string.settings_notifications_desc),
                    checked = settings.notificationsEnabled,
                    onCheckedChange = onNotificationsEnabledChanged,
                )
                Hairline()
                ChevronRow(
                    title = stringResource(R.string.settings_more_notifications),
                    subtitle = stringResource(R.string.settings_more_notifications_desc),
                    onClick = onOpenNotificationDetail,
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

    if (notificationPermissionDenied) {
        AlertDialog(
            onDismissRequest = onDismissNotificationPermissionDenied,
            title = { Text(text = stringResource(R.string.settings_notification_permission_denied_title)) },
            text = { Text(text = stringResource(R.string.settings_notification_permission_denied_body)) },
            confirmButton = {
                TextButton(onClick = onDismissNotificationPermissionDenied) {
                    Text(text = stringResource(R.string.settings_notification_permission_dismiss))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDismissNotificationPermissionDenied()
                        onOpenNotificationSettings()
                    },
                ) {
                    Text(text = stringResource(R.string.settings_open_notification_settings))
                }
            },
        )
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
internal fun SettingsHeader(
    onBack: () -> Unit,
    title: String,
) {
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
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun SettingsSection(
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
internal fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
internal fun SettingsRow(
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
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingsRow {
        TitleBlock(title = title, subtitle = subtitle)
        MeteorSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
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
private fun MeteorSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
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

/** One option in the popup selection block. */
internal data class ChoiceOption<T>(
    val value: T,
    val label: String,
    val description: String? = null,
)

/**
 * The theme / file-picker row with an anchored dropdown menu (1.7.4).
 *
 * Deliberately not an AlertDialog: the current value and the up/down chevrons
 * stay in place, and the compact menu opens directly under that value, the way
 * the interface-settings screen does.
 */
@Composable
private fun <T> SettingsChoiceRow(
    title: String,
    options: List<ChoiceOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: (@Composable (T) -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                ChevronUpDownIcon()
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(0.dp, 4.dp),
                modifier = Modifier.widthIn(min = 190.dp),
                // The 1.7.4 menu reused Material's 4dp default, which read as a
                // hard rectangle next to the app's 20–24dp cards. Round it to
                // match the paper surfaces around it.
                shape = RoundedCornerShape(22.dp),
            ) {
                options.forEach { option ->
                    val glyph = icon
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = option.label,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                option.description?.let { description ->
                                    Text(
                                        text = description,
                                        modifier = Modifier.padding(top = 2.dp),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        leadingIcon = if (glyph != null) {
                            { glyph(option.value) }
                        } else {
                            null
                        },
                        trailingIcon = if (selected == option.value) {
                            {
                                Text(
                                    text = "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            onSelect(option.value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChevronUpDownIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.padding(start = 4.dp).size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        val unit = size.width / 16f
        val up = Path().apply {
            moveTo(4f * unit, 6.5f * unit)
            lineTo(8f * unit, 2.5f * unit)
            lineTo(12f * unit, 6.5f * unit)
        }
        val down = Path().apply {
            moveTo(4f * unit, 9.5f * unit)
            lineTo(8f * unit, 13.5f * unit)
            lineTo(12f * unit, 9.5f * unit)
        }
        drawPath(up, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(down, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Small line-art equivalents of the system / light / dark options. */
@Composable
private fun ThemeChoiceGlyph(mode: DarkMode) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val menuSurface = MaterialTheme.colorScheme.surfaceContainer
    Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 20f
        val cx = size.width / 2f
        val cy = size.height / 2f
        when (mode) {
            DarkMode.FOLLOW_SYSTEM -> {
                val stroke = 1.5.dp.toPx()
                drawCircle(
                    color = tint,
                    radius = 5.5f * unit,
                    center = Offset(cx, cy),
                    style = Stroke(width = stroke),
                )
                for (index in 0 until 8) {
                    val angle = Math.toRadians((index * 45).toDouble())
                    drawLine(
                        color = tint,
                        start = Offset(
                            cx + (7f * unit * kotlin.math.cos(angle)).toFloat(),
                            cy + (7f * unit * kotlin.math.sin(angle)).toFloat(),
                        ),
                        end = Offset(
                            cx + (8.8f * unit * kotlin.math.cos(angle)).toFloat(),
                            cy + (8.8f * unit * kotlin.math.sin(angle)).toFloat(),
                        ),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }

            DarkMode.LIGHT -> {
                drawCircle(color = tint, radius = 3.6f * unit, center = Offset(cx, cy))
                val stroke = 1.5.dp.toPx()
                for (index in 0 until 8) {
                    val angle = Math.toRadians((index * 45).toDouble())
                    drawLine(
                        color = tint,
                        start = Offset(
                            cx + (6f * unit * kotlin.math.cos(angle)).toFloat(),
                            cy + (6f * unit * kotlin.math.sin(angle)).toFloat(),
                        ),
                        end = Offset(
                            cx + (8.8f * unit * kotlin.math.cos(angle)).toFloat(),
                            cy + (8.8f * unit * kotlin.math.sin(angle)).toFloat(),
                        ),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }

            DarkMode.DARK -> {
                drawCircle(color = tint, radius = 6.4f * unit, center = Offset(cx, cy))
                drawCircle(
                    color = menuSurface,
                    radius = 5.5f * unit,
                    center = Offset(cx + 3.2f * unit, cy - 2.4f * unit),
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

/** Byte counts are units, not prose, so they stay out of string resources. */
private fun formatBytes(bytes: Long): String {
    val locale = java.util.Locale.getDefault()
    return when {
        bytes >= 1024L * 1024L -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(locale, "%.1f KB", bytes / 1024.0)
        else -> String.format(locale, "%d B", bytes)
    }
}
