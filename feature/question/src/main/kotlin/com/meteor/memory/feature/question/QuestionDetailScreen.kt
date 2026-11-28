package com.meteor.memory.feature.question

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.feature.editor.MarkdownField
import com.meteor.memory.feature.editor.MarkdownPreview
import java.time.Instant

/**
 * 一个问题及其全部回答（ADR-068 / ADR-069）。
 *
 * 页面分成两种明确形态，而不是把编辑器、提醒、回答堆在同一列里：
 *
 * - **阅读**：问题 → 提醒 chips → 回答列表；「回答 / 再回答一次」固定钉在底部，
 *   永远不用滚到列表末尾去找。
 * - **回答**：整屏就是编辑器，问题缩成上方一张引用卡；取消、预览、保存在同一
 *   条页头上。
 *
 * 详情永远先阅读（ADR-069）：从列表点进来、从通知点进来，看到的都是问题和已有
 * 回答；要写，再按底部的按钮。未回答的问题不再被直接弹进编辑器。
 *
 * 两种形态共用同一套页头与提醒编辑器，所以提醒在哪里都能改，切模式不会丢。
 */
@Composable
fun QuestionDetailScreen(
    state: QuestionDetailUi,
    onDraftChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onStartAnswer: () -> Unit,
    onCancelAnswer: () -> Unit,
    onSaveAnswer: () -> Unit,
    onDeleteAnswer: (String) -> Unit,
    onAddReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (Instant) -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onDelete: (onDeleted: () -> Unit) -> Unit,
    onBack: () -> Unit,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingExit by remember { mutableStateOf(false) }
    var pendingAnswerDelete by remember { mutableStateOf<String?>(null) }

    // 编辑模式里返回 = 退出编辑回到阅读；有没保存的字就先问一句。
    val requestCancel: () -> Unit = {
        if (!state.saving) {
            if (state.hasUnsavedChanges) confirmingExit = true else onCancelAnswer()
        }
    }
    val requestBack: () -> Unit = {
        if (state.editing) requestCancel() else onBack()
    }

    // 系统返回键不静默丢掉正在写的回答；保存中由 requestCancel 自己忽略。
    BackHandler(enabled = state.editing) { requestCancel() }

    if (state.editing && !state.loading) {
        EditingBody(
            state = state,
            onDraftChange = onDraftChange,
            onTogglePreview = onTogglePreview,
            onInsertLink = onInsertLink,
            onSaveAnswer = onSaveAnswer,
            onCancel = requestCancel,
            modifier = modifier,
        )
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            QuestionScreenHeader(
                title = stringResource(R.string.question_title),
                onBack = requestBack,
                trailing = {
                    if (!state.loading && state.exists) {
                        QuestionOverflowMenu(
                            onRequestDelete = { confirmingDelete = true },
                            enabled = !state.deleting && !state.saving,
                        )
                    }
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading -> DetailLoadingPlaceholder()
                    !state.exists -> MissingBody(message = state.error, onBack = onBack)
                    else -> ReadingBody(
                        state = state,
                        onAddReminder = onAddReminder,
                        onRemoveReminder = onRemoveReminder,
                        onRequestDeleteAnswer = { pendingAnswerDelete = it },
                        animationsEnabled = animationsEnabled,
                    )
                }
            }

            if (!state.loading && state.exists) {
                Button(
                    onClick = onStartAnswer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (state.hasAnswers) {
                                R.string.question_answer_again
                            } else {
                                R.string.question_answer_action
                            },
                        ),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
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

    if (pendingAnswerDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.question_answer_delete_confirm_title),
            body = stringResource(R.string.question_answer_delete_confirm_body),
            confirmLabel = stringResource(R.string.question_answer_delete_confirm_action),
            cancelLabel = stringResource(R.string.question_delete_cancel),
            onDismiss = { pendingAnswerDelete = null },
            onConfirm = {
                val answerId = pendingAnswerDelete
                pendingAnswerDelete = null
                if (answerId != null) onDeleteAnswer(answerId)
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
                onCancelAnswer()
            },
        )
    }
}

/** 阅读：问题卡 → 提醒 → 回答列表；滑动到底部也不需要找「回答」。 */
@Composable
private fun ReadingBody(
    state: QuestionDetailUi,
    onAddReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (Instant) -> Unit,
    onRequestDeleteAnswer: (String) -> Unit,
    animationsEnabled: Boolean,
) {
    val listState = rememberLazyListState()

    // 刚保存完回答时滚到最新一条：保存按钮在底部，人应该立刻看到自己写的东西。
    // 同时以回答数为 key：state.saved 先到、load() 的新回答后到，只监听 saved
    // 会在列表还没刷新时滚到旧末尾。
    LaunchedEffect(state.saved, state.answers.size) {
        if (state.saved && state.answers.isNotEmpty()) {
            listState.animateScrollToItem(index = 2 + state.answers.size)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "question-info") {
            QuestionInfoCard(state = state)
        }
        item(key = "question-reminders") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuestionReminderEditor(
                        reminders = state.reminders,
                        onAdd = onAddReminder,
                        onRemove = onRemoveReminder,
                        animationsEnabled = animationsEnabled,
                    )
                    state.error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item(key = "answer-section") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.question_answer_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.hasAnswers) {
                    Text(
                        text = state.answers.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.hasAnswers) {
            items(state.answers, key = { it.id }) { answer ->
                AnswerCard(
                    answer = answer,
                    onDelete = { onRequestDeleteAnswer(answer.id) },
                    enabled = !state.deleting && !state.saving,
                )
            }
        } else {
            item(key = "answer-empty") {
                Text(
                    text = stringResource(R.string.question_answer_empty),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 回答编辑：问题缩成引用卡，其余高度全部留给编辑器。 */
@Composable
private fun EditingBody(
    state: QuestionDetailUi,
    onDraftChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onSaveAnswer: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        QuestionScreenHeader(
            title = stringResource(
                if (state.hasAnswers) R.string.question_answer_again else R.string.question_answer_action,
            ),
            onBack = onCancel,
            backLabel = stringResource(R.string.question_cancel),
            trailing = {
                PreviewChip(
                    previewing = state.previewing,
                    enabled = !state.saving,
                    onToggle = onTogglePreview,
                )
            },
        )

        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                )
                Text(
                    text = stringResource(R.string.question_created_at, state.createdLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MarkdownField(
            text = state.draft,
            previewing = state.previewing,
            onTextChanged = onDraftChange,
            onPreviewToggle = onTogglePreview,
            onInsertLink = onInsertLink,
            onPickImage = { },
            onRemoveImage = { },
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            placeholder = { Text(stringResource(R.string.question_answer_placeholder)) },
            showPreviewAction = false,
            // 回答暂时没有媒体导入链路，隐藏图片入口，避免一个按了没反应的按钮。
            showImageAction = false,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp),
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
            enabled = !state.saving && state.draft.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.text,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuestionStatusPill(answered = state.hasAnswers)
                Text(
                    text = stringResource(R.string.question_created_at, state.createdLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnswerCard(
    answer: AnswerRow,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    // 回答时间精确到分钟：这是「什么时候想通的」那一半信息。
                    text = stringResource(R.string.question_answered_at, answer.answeredAtLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onDelete, enabled = enabled) {
                    Text(text = stringResource(R.string.question_answer_delete))
                }
            }
            MarkdownPreview(
                body = answer.text,
                modifier = Modifier.fillMaxWidth().heightIn(max = ANSWER_MAX_HEIGHT),
            )
        }
    }
}

/** 页头预览胶囊：与日记页同形状（36dp 高、18dp 圆角）。 */
@Composable
private fun PreviewChip(
    previewing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .height(36.dp)
            .clickable(enabled = enabled, onClick = onToggle),
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (previewing) R.string.question_edit else R.string.question_preview,
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary,
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
private fun MissingBody(message: String?, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message ?: stringResource(R.string.question_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onBack, shape = RoundedCornerShape(20.dp)) {
            Text(text = stringResource(R.string.question_back))
        }
    }
}

/** 确认框：删除问题与放弃草稿共用一套结构，只有文案不同。 */
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

/** 回答正文的最大高度：超过后正文在卡片内部滚动，避免一条长回答吃掉整个列表。 */
private val ANSWER_MAX_HEIGHT = 420.dp
