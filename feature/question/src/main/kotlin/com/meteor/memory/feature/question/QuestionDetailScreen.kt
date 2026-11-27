package com.meteor.memory.feature.question

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.feature.editor.MarkdownField
import com.meteor.memory.feature.editor.MarkdownPreview

/**
 * 一个问题及其全部回答（ADR-068）。
 *
 * **这一页的中心规则**：未回答的问题直接是编辑界面，已回答的直接是阅读界面。
 * `editing` 由视图模型在读记录时决定（见 [QuestionDetailUi]），本屏只负责渲染，
 * 不在两个模式之间自作主张地切换——否则刷新一次就可能把人从正在写的地方弹走。
 *
 * 阅读模式用 [MarkdownPreview]、编辑模式用 [MarkdownField]，和日记页是同一套
 * 编辑器：回答也是一段可以带链接、图片、标题的 Markdown，两处渲染必须一致。
 */
@Composable
fun QuestionDetailScreen(
    state: QuestionDetailUi,
    onDraftChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onStartAnswer: () -> Unit,
    onSaveAnswer: () -> Unit,
    onAddDefaultReminder: () -> Unit,
    onAddCustomReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (java.time.Instant) -> Unit,
    onDelete: (onDeleted: () -> Unit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingExit by remember { mutableStateOf(false) }

    // 有未保存的草稿时返回要先问一句；没有草稿就直接走，不拿对话框烦人。
    val requestBack: () -> Unit = {
        if (state.hasUnsavedChanges) confirmingExit = true else onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(
            state = state,
            onBack = requestBack,
            onTogglePreview = onTogglePreview,
            onRequestDelete = { confirmingDelete = true },
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> DetailLoadingPlaceholder()
                state.editing -> EditingBody(
                    state = state,
                    onDraftChange = onDraftChange,
                    onTogglePreview = onTogglePreview,
                    onAddDefaultReminder = onAddDefaultReminder,
                    onAddCustomReminder = onAddCustomReminder,
                    onRemoveReminder = onRemoveReminder,
                )

                else -> ReadingBody(
                    state = state,
                    onStartAnswer = onStartAnswer,
                    onAddDefaultReminder = onAddDefaultReminder,
                    onAddCustomReminder = onAddCustomReminder,
                    onRemoveReminder = onRemoveReminder,
                )
            }
        }

        // 只在编辑区真的画出来之后才给保存栏：加载中同时出现骨架和保存按钮，
        // 会让人以为可以先写。
        if (state.editing && !state.loading) {
            SaveAnswerBar(state = state, onSaveAnswer = onSaveAnswer)
        }
    }

    if (confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.question_delete_confirm_title),
            body = stringResource(R.string.question_delete_confirm_body),
            confirmLabel = stringResource(R.string.question_delete_confirm_action),
            cancelLabel = stringResource(R.string.question_delete_cancel),
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                // 删成功后宿主通过这个回调通知「可以走了」；本屏拿不到回退栈，
                // 所以把返回动作交出去。传空 lambda 会让用户停在一个已不存在的问题上。
                onDelete { onBack() }
            },
        )
    }

    if (confirmingExit) {
        ConfirmDialog(
            title = stringResource(R.string.question_unsaved_title),
            body = stringResource(R.string.question_unsaved_body),
            confirmLabel = stringResource(R.string.question_unsaved_discard),
            cancelLabel = stringResource(R.string.question_unsaved_keep),
            onDismiss = { confirmingExit = false },
            onConfirm = {
                confirmingExit = false
                onBack()
            },
        )
    }
}

/**
 * 页头：返回 + 标题 + 「预览 / 编辑」胶囊 +「删除问题」胶囊。
 *
 * 删除用错误色，因为它不可恢复；预览切换只在编辑模式下出现，阅读模式没有可切换的东西。
 */
@Composable
private fun DetailHeader(
    state: QuestionDetailUi,
    onBack: () -> Unit,
    onTogglePreview: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuestionBackButton(onClick = onBack)
        Text(
            text = stringResource(R.string.question_title),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.editing && !state.loading) {
            HeaderChip(
                label = stringResource(
                    if (state.previewing) R.string.question_edit else R.string.question_preview,
                ),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.secondary,
                enabled = !state.saving,
                onClick = onTogglePreview,
            )
        }
        if (!state.loading) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                HeaderChip(
                    label = stringResource(
                        if (state.deleting) R.string.question_deleting else R.string.question_delete,
                    ),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.error,
                    // 保存中不许删：一次点击可能既写回答又删问题。
                    enabled = !state.deleting && !state.saving,
                    onClick = onRequestDelete,
                )
            }
        }
    }
}

/** 页头胶囊：与日记页的「预览」「删除日记」同一形状（36dp 高、18dp 圆角）。 */
@Composable
private fun HeaderChip(
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .height(36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        // 禁用时整块变淡，而不是只把文字变淡：胶囊看起来还「可按」最误导人。
        color = if (enabled) container else container.copy(alpha = 0.4f),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 1,
            )
        }
    }
}

/** 阅读模式：问题信息 + 提醒 + 全部回答 +「再回答一次」。 */
@Composable
private fun ReadingBody(
    state: QuestionDetailUi,
    onStartAnswer: () -> Unit,
    onAddDefaultReminder: () -> Unit,
    onAddCustomReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (java.time.Instant) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "question-info") {
            QuestionInfoCard(state = state)
        }
        item(key = "question-reminders") {
            ReminderCard(
                reminders = state.reminders,
                onAddDefaultReminder = onAddDefaultReminder,
                onAddCustomReminder = onAddCustomReminder,
                onRemoveReminder = onRemoveReminder,
            )
        }

        state.error?.let { message ->
            item(key = "question-error") { ErrorLine(message) }
        }

        if (state.hasAnswers) {
            item(key = "answer-section") {
                Text(
                    text = stringResource(R.string.question_answer_section),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(state.answers, key = { it.id }) { answer ->
                AnswerCard(answer = answer)
            }
            item(key = "answer-again") {
                OutlinedButton(
                    onClick = onStartAnswer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(text = stringResource(R.string.question_answer_again))
                }
            }
        } else {
            // 未回答的问题本来就直接进编辑区，这条空态只在「编辑被取消但还没有回答」
            // 这类中间状态下短暂出现，所以给的是同一个起点按钮。
            item(key = "answer-empty") {
                EmptyAnswerCard(onStartAnswer = onStartAnswer)
            }
        }
    }
}

/** 编辑模式：问题信息 + 提醒 + 编辑器。 */
@Composable
private fun EditingBody(
    state: QuestionDetailUi,
    onDraftChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onAddDefaultReminder: () -> Unit,
    onAddCustomReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (java.time.Instant) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "question-info") {
            QuestionInfoCard(state = state)
        }
        item(key = "question-reminders") {
            ReminderCard(
                reminders = state.reminders,
                onAddDefaultReminder = onAddDefaultReminder,
                onAddCustomReminder = onAddCustomReminder,
                onRemoveReminder = onRemoveReminder,
            )
        }
        state.error?.let { message ->
            item(key = "question-error") { ErrorLine(message) }
        }
        item(key = "answer-editor") {
            // 高度必须给死：整页在 LazyColumn 里滚动，MarkdownField 内部用 weight
            // 分栏，父级高度无界时加权子项会被算成 0。给一个固定编辑区，长回答
            // 在编辑器内部滚动，和日记页的用法一致。
            MarkdownField(
                text = state.draft,
                previewing = state.previewing,
                onTextChanged = onDraftChange,
                onPreviewToggle = onTogglePreview,
                onInsertLink = { _, _ -> },
                onPickImage = { },
                onRemoveImage = { },
                modifier = Modifier.fillMaxWidth().height(EDITOR_HEIGHT),
                placeholder = { Text(stringResource(R.string.question_answer_placeholder)) },
                showPreviewAction = false,
            )
        }
    }
}

@Composable
private fun QuestionInfoCard(state: QuestionDetailUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = state.text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.question_created_at, state.createdLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReminderCard(
    reminders: List<ReminderRow>,
    onAddDefaultReminder: () -> Unit,
    onAddCustomReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (java.time.Instant) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            QuestionReminderSection(
                reminders = reminders,
                onAddDefault = onAddDefaultReminder,
                onAddCustom = onAddCustomReminder,
                onRemove = onRemoveReminder,
            )
        }
    }
}

@Composable
private fun AnswerCard(answer: AnswerRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                // 回答时间精确到分钟：这是「什么时候想通的」那一半信息。
                text = stringResource(R.string.question_answered_at, answer.answeredAtLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            MarkdownPreview(
                body = answer.text,
                modifier = Modifier.fillMaxWidth().heightIn(max = ANSWER_MAX_HEIGHT),
            )
        }
    }
}

@Composable
private fun EmptyAnswerCard(onStartAnswer: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.question_answer_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(
                onClick = onStartAnswer,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(text = stringResource(R.string.question_answer_action))
            }
        }
    }
}

@Composable
private fun SaveAnswerBar(
    state: QuestionDetailUi,
    onSaveAnswer: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.question_only_local),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.question_char_count, state.draft.length),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onSaveAnswer,
            modifier = Modifier.fillMaxWidth(),
            // 空草稿没有可存的东西；保存中再点一次会写两份回答。
            enabled = !state.saving && state.draft.isNotBlank(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.saving) {
                        R.string.question_answer_saving
                    } else {
                        R.string.question_answer_save
                    },
                ),
            )
        }
    }
}

/**
 * 加载占位：三条灰条，不放任何文字。
 *
 * 用骨架而不是「加载中…」：这一页通常在几十毫秒内就绪，一句会闪一下的提示
 * 反而比留白更吵；而且占位不需要新文案，也就不必碰 `strings.xml`。
 */
@Composable
private fun DetailLoadingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) { index ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (index == 0) 22.dp else 14.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 确认框：删除问题与放弃草稿共用一套结构，只有文案与确认按钮的颜色不同。 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = cancelLabel)
            }
        },
    )
}

/**
 * 编辑器高度：整页滚动，所以编辑区拿不到 weight，必须给一个确定的数值。
 *
 * 320dp 在常见手机上约等于「一屏能看见十行左右」，更长的回答在编辑区内部滚动。
 */
private val EDITOR_HEIGHT = 320.dp

/** 回答正文的最大高度：超过后正文在卡片内部滚动，避免一条长回答吃掉整个列表。 */
private val ANSWER_MAX_HEIGHT = 420.dp
