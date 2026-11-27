package com.meteor.memory.feature.question

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

/**
 * 提醒区（ADR-069）：一排 chips + 一个「加一条提醒」。
 *
 * 旧布局把每条提醒排成一整行、下面再放两个全宽按钮，提醒一多就把正文挤到
 * 屏幕之外。这里把提醒压成可横向滚动的胶囊：最多 8 条也只占一行；点「加一条
 * 提醒」展开与生辰页同款的滚轮，选完自动收起。
 */

private val ChipShape = RoundedCornerShape(16.dp)
private const val CHIP_HEIGHT = 32

@Composable
internal fun QuestionReminderEditor(
    reminders: List<ReminderRow>,
    onAdd: (ReminderPick) -> Unit,
    onRemove: (Instant) -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val limitReached = reminders.size >= QuestionSchedule.MAX_REMINDERS

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.question_reminder_section),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    R.string.question_reminder_limit_hint,
                    QuestionSchedule.MAX_REMINDERS,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (reminders.isEmpty()) {
            Text(
                text = stringResource(R.string.question_no_reminder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(reminders, key = { it.at.toString() }) { row ->
                ReminderChip(row = row, onRemove = { onRemove(row.at) })
            }
            if (!limitReached) {
                item(key = "add-reminder") {
                    AddReminderChip(onClick = { pickerOpen = !pickerOpen })
                }
            }
        }

        AnimatedVisibility(
            visible = pickerOpen,
            enter = if (animationsEnabled) {
                expandVertically(expandFrom = Alignment.Top) + fadeIn()
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            } else {
                ExitTransition.None
            },
        ) {
            QuestionWheelPicker(
                onCancel = { pickerOpen = false },
                onConfirm = { pick ->
                    pickerOpen = false
                    onAdd(pick)
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ReminderChip(
    row: ReminderRow,
    onRemove: () -> Unit,
) {
    val removeLabel = stringResource(R.string.question_reminder_remove)
    val contentColor = if (row.expired) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(shape = ChipShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .height(CHIP_HEIGHT.dp)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = row.label,
                fontSize = 13.sp,
                color = contentColor,
            )
            if (row.expired) {
                Text(
                    text = stringResource(R.string.question_reminder_expired),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = removeLabel },
                contentAlignment = Alignment.Center,
            ) {
                CrossGlyph(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddReminderChip(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .height(CHIP_HEIGHT.dp)
            .clickable(onClick = onClick),
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlusGlyph(tint = tint)
            Text(
                text = stringResource(R.string.question_reminder_add),
                fontSize = 13.sp,
                color = tint,
            )
        }
    }
}

@Composable
private fun CrossGlyph(tint: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(12.dp)) {
        val unit = size.minDimension / 12f
        drawLine(
            color = tint,
            start = Offset(2f * unit, 2f * unit),
            end = Offset(10f * unit, 10f * unit),
            strokeWidth = 1.6f * unit,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(10f * unit, 2f * unit),
            end = Offset(2f * unit, 10f * unit),
            strokeWidth = 1.6f * unit,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun PlusGlyph(tint: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.8f * (size.minDimension / 12f)
        val center = size.width / 2f
        drawLine(
            color = tint,
            start = Offset(center - size.width * 0.35f, center),
            end = Offset(center + size.width * 0.35f, center),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(center, center - size.height * 0.35f),
            end = Offset(center, center + size.height * 0.35f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
