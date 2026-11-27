package com.meteor.memory.feature.question

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 每日一问首页（ADR-068 / ADR-069）：只负责浏览，提问在独立页面。
 *
 * 旧布局把「写问题」的大表单固定在第一项，已提问题被挤到下面；这一版把
 * 表单整个搬去 `QuestionComposeScreen`，列表只保留筛选与卡片。卡片上只放
 * 四件事：状态、问题、最近一条回答、提问时间——剩下的都在详情页。
 */

private enum class QuestionFilter { ALL, OPEN, ANSWERED }

@Composable
fun QuestionListScreen(
    rows: List<QuestionRow>,
    onCompose: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val filter = QuestionFilter.entries[filterIndex]

    val openCount = rows.count { !it.isAnswered }
    val answeredCount = rows.count { it.isAnswered }
    val visible = when (filter) {
        QuestionFilter.ALL -> rows
        QuestionFilter.OPEN -> rows.filter { !it.isAnswered }
        QuestionFilter.ANSWERED -> rows.filter { it.isAnswered }
    }

    Column(modifier = modifier.fillMaxSize()) {
        QuestionScreenHeader(
            title = stringResource(R.string.question_title),
            onBack = onBack,
            trailing = { QuestionPlusButton(onClick = onCompose) },
        )

        FilterRow(
            filter = filter,
            totalCount = rows.size,
            openCount = openCount,
            answeredCount = answeredCount,
            onSelect = { filterIndex = it.ordinal },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (visible.isEmpty()) {
                item(key = "question-empty") {
                    EmptyQuestions(filter = filter, onCompose = onCompose)
                }
            } else {
                items(visible, key = { it.id }) { row ->
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

/** 筛选按钮：全部 / 待回答 / 已回答，各带数量。 */
@Composable
private fun FilterRow(
    filter: QuestionFilter,
    totalCount: Int,
    openCount: Int,
    answeredCount: Int,
    onSelect: (QuestionFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(
            label = stringResource(R.string.question_filter_all),
            count = totalCount,
            selected = filter == QuestionFilter.ALL,
            onClick = { onSelect(QuestionFilter.ALL) },
        )
        FilterPill(
            label = stringResource(R.string.question_open_badge),
            count = openCount,
            selected = filter == QuestionFilter.OPEN,
            onClick = { onSelect(QuestionFilter.OPEN) },
        )
        FilterPill(
            label = stringResource(R.string.question_answered_badge),
            count = answeredCount,
            selected = filter == QuestionFilter.ANSWERED,
            onClick = { onSelect(QuestionFilter.ANSWERED) },
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    Surface(
        modifier = Modifier.height(30.dp).clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = count.toString(),
                fontSize = 12.sp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
    val answered = row.isAnswered
    val status = when {
        row.nextReminderLabel != null -> stringResource(
            R.string.question_next_reminder,
            row.nextReminderLabel,
        )
        answered -> stringResource(R.string.question_answer_count, row.answeredCount)
        else -> stringResource(R.string.question_no_reminder)
    }
    val answerPreview = row.answerPreview

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuestionStatusPill(answered = answered)
                Text(
                    text = status,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = if (row.nextReminderLabel != null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                QuestionOverflowMenu(
                    onRequestDelete = onRequestDelete,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            Text(
                text = row.text,
                modifier = Modifier.padding(end = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                // 列表是「扫一眼」，完整正文属于详情页。
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (answerPreview != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = answerPreview,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = stringResource(R.string.question_created_at, row.createdLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyQuestions(
    filter: QuestionFilter,
    onCompose: () -> Unit,
) {
    val message = when (filter) {
        QuestionFilter.ALL -> stringResource(R.string.question_empty_all)
        QuestionFilter.OPEN -> stringResource(R.string.question_empty_pending)
        QuestionFilter.ANSWERED -> stringResource(R.string.question_empty_answered)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (filter != QuestionFilter.ANSWERED) {
                Button(
                    onClick = onCompose,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(text = stringResource(R.string.question_empty_action))
                }
            }
        }
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
