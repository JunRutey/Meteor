package com.meteor.memory.feature.question

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 每日一问首页（ADR-068）：常驻的提问区 + 已提问题的列表。
 *
 * 提问区是这个功能的主入口，所以它固定排在列表第一项而不是藏在按钮后面：
 * 「写下问题 → 保存」应当是打开页面就能做的一件事。把它放进 `LazyColumn`
 * 而不是固定在页头，是因为提醒可以加到 8 条，固定住会把列表挤没。
 *
 * This file also holds the three small pieces the detail page reuses
 * ([QuestionBackButton], [QuestionRemoveButton], [QuestionReminderSection]):
 * both screens show the same reminder rows with the same add / remove / expired
 * treatment, and two copies of that markup would drift.
 */

/**
 * 面板淡入 / 淡出时长，与 `:app` 的 `MotionTokens.enter()` / `exit()` 同值
 * （200ms 进、150ms 出）。
 *
 * feature 模块不能依赖 `:app`（R1 单向依赖），所以数值在这里复制一份；
 * `feature/gallery/GalleryScreen.kt` 用的是同一对常量，面板在任何屏幕上都该一样快。
 */
private const val ENTER_MS = 200
private const val EXIT_MS = 150

/** Sticky-ish keys for the two non-question rows of the list. */
private const val COMPOSER_KEY = "question-composer"
private const val EMPTY_KEY = "question-empty"

@Composable
fun QuestionListScreen(
    rows: List<QuestionRow>,
    composer: QuestionComposerState,
    onDraftChange: (String) -> Unit,
    onAddDefaultReminder: () -> Unit,
    onAddCustomReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (java.time.Instant) -> Unit,
    onSubmit: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 删除要二次确认，但确认框的宿主状态属于本屏：列表本身只报告「点了删除」。
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ListHeader(onBack = onBack)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = COMPOSER_KEY) {
                ComposerCard(
                    composer = composer,
                    onDraftChange = onDraftChange,
                    onAddDefaultReminder = onAddDefaultReminder,
                    onAddCustomReminder = onAddCustomReminder,
                    onRemoveReminder = onRemoveReminder,
                    onSubmit = onSubmit,
                    animationsEnabled = animationsEnabled,
                )
            }

            if (rows.isEmpty()) {
                item(key = EMPTY_KEY) { EmptyQuestions() }
            } else {
                items(rows, key = { it.id }) { row ->
                    QuestionCard(
                        row = row,
                        onOpen = { onOpen(row.id) },
                        onRequestDelete = { pendingDelete = row.id },
                        modifier = if (animationsEnabled) Modifier.animateItem() else Modifier,
                    )
                }
            }
        }
    }

    val deletingId = pendingDelete
    if (deletingId != null) {
        DeleteQuestionDialog(
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(deletingId)
            },
        )
    }
}

@Composable
private fun ListHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuestionBackButton(onClick = onBack)
        Text(
            text = stringResource(R.string.question_title),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 提问区：写问题 → 配提醒 → 保存。
 *
 * 提醒默认为「明天同一时刻」由视图模型预置，所以只想立刻记下一个念头的人
 * 什么都不用配；想改时间的人从这里加一条或自己挑日期时间。
 */
@Composable
private fun ComposerCard(
    composer: QuestionComposerState,
    onDraftChange: (String) -> Unit,
    onAddDefaultReminder: () -> Unit,
    onAddCustomReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (java.time.Instant) -> Unit,
    onSubmit: () -> Unit,
    animationsEnabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = composer.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                // 问题可以是一段话，但页面上只留一块能看见行数的高度。
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                placeholder = {
                    Text(text = stringResource(R.string.question_composer_placeholder))
                },
            )

            QuestionReminderSection(
                reminders = composer.reminders,
                onAddDefault = onAddDefaultReminder,
                onAddCustom = onAddCustomReminder,
                onRemove = onRemoveReminder,
            )

            QuestionFade(visible = composer.error != null, animationsEnabled = animationsEnabled) {
                val message = composer.error
                if (message != null) {
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = composer.canSubmit,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = stringResource(
                        if (composer.submitting) R.string.question_saving else R.string.question_save,
                    ),
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(
    row: QuestionRow,
    onOpen: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleteLabel = stringResource(R.string.question_delete)
    val answerPreview = row.answerPreview
    val lastAnsweredLabel = row.lastAnsweredLabel
    val nextReminderLabel = row.nextReminderLabel

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = row.text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    // 列表是「扫一眼」，完整正文属于详情页。
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                QuestionRemoveButton(description = deleteLabel, onClick = onRequestDelete)
            }

            Text(
                text = stringResource(R.string.question_created_at, row.createdLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnsweredBadge(answered = row.isAnswered)
                if (row.isAnswered) {
                    Text(
                        text = stringResource(R.string.question_answer_count, row.answeredCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (answerPreview != null) {
                Text(
                    text = answerPreview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (lastAnsweredLabel != null) {
                Text(
                    text = stringResource(R.string.question_answered_at, lastAnsweredLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = if (nextReminderLabel != null) {
                    stringResource(R.string.question_next_reminder, nextReminderLabel)
                } else {
                    stringResource(R.string.question_no_reminder)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnsweredBadge(answered: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (answered) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = stringResource(
                if (answered) R.string.question_answered_badge else R.string.question_open_badge,
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (answered) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun EmptyQuestions() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = stringResource(R.string.question_empty),
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 返回箭头：48dp 触达区，图标按 24 单位画，和日历 / 相册页的线条语言一致。
 *
 * 画出来而不是引 Material 图标：`:feature-question` 不依赖 `material-icons`。
 */
@Composable
internal fun QuestionBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.question_back)
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val unit = size.minDimension / 24f
            val stroke = 1.8f * unit
            drawLine(
                color = tint,
                start = Offset(15f * unit, 5f * unit),
                end = Offset(8f * unit, 12f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(8f * unit, 12f * unit),
                end = Offset(15f * unit, 19f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 一条提醒右侧的删除按钮（×），触达区 32dp。 */
@Composable
internal fun QuestionRemoveButton(
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val unit = size.minDimension / 24f
            val stroke = 1.8f * unit
            val color = if (enabled) tint else tint.copy(alpha = 0.4f)
            drawLine(
                color = color,
                start = Offset(5f * unit, 5f * unit),
                end = Offset(19f * unit, 19f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(19f * unit, 5f * unit),
                end = Offset(5f * unit, 19f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 提醒区块：逐条列出、可删、可加（默认「明天同一时刻」或自选日期时间）。
 *
 * 两个添加按钮在到达上限后一起禁用，并说明上限是多少——只把按钮变灰而不说
 * 原因，人会以为是页面卡住了。过期条目用次要色加「已过期」标记：它已经
 * 不会再触发，只能删掉。
 */
@Composable
internal fun QuestionReminderSection(
    reminders: List<ReminderRow>,
    onAddDefault: () -> Unit,
    onAddCustom: (ReminderPick) -> Unit,
    onRemove: (java.time.Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickCustom = rememberCustomReminderLauncher(onPicked = onAddCustom)
    val limitReached = reminders.size >= QuestionSchedule.MAX_REMINDERS
    val removeLabel = stringResource(R.string.question_reminder_remove)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.question_reminder_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        if (reminders.isEmpty()) {
            Text(
                text = stringResource(R.string.question_no_reminder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            reminders.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.expired) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (row.expired) {
                        Text(
                            text = stringResource(R.string.question_reminder_expired),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    QuestionRemoveButton(
                        description = removeLabel,
                        onClick = { onRemove(row.at) },
                    )
                }
            }
        }

        if (limitReached) {
            Text(
                text = stringResource(
                    R.string.question_reminder_limit,
                    QuestionSchedule.MAX_REMINDERS,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = onAddDefault,
            modifier = Modifier.fillMaxWidth(),
            enabled = !limitReached,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(text = stringResource(R.string.question_reminder_add_default))
        }
        OutlinedButton(
            onClick = pickCustom,
            modifier = Modifier.fillMaxWidth(),
            enabled = !limitReached,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(text = stringResource(R.string.question_reminder_add_custom))
        }
    }
}

/** 淡入 / 淡出容器；关掉动效时直接出现，不做任何过渡。 */
@Composable
internal fun QuestionFade(
    visible: Boolean,
    animationsEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (animationsEnabled) {
            fadeIn(tween(durationMillis = ENTER_MS, easing = FastOutSlowInEasing))
        } else {
            EnterTransition.None
        },
        exit = if (animationsEnabled) {
            fadeOut(tween(durationMillis = EXIT_MS, easing = FastOutLinearInEasing))
        } else {
            ExitTransition.None
        },
    ) {
        content()
    }
}

@Composable
private fun DeleteQuestionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.question_delete_confirm_title)) },
        text = { Text(text = stringResource(R.string.question_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.question_delete_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.question_delete_cancel))
            }
        },
    )
}
