package com.meteor.memory.feature.question

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant

/**
 * 提问页（ADR-069）：一屏只做一件事——把此刻想到的问题写下来。
 *
 * 旧布局把这段表单塞在列表第一项：打开列表先看到一个大输入框，回看旧问题
 * 反而要往下滑。现在提问有独立页面，正文区域足够大，提醒放在下方的卡片里，
 * 时间选择展开后与生辰页同一套滚轮。
 */
@Composable
fun QuestionComposeScreen(
    state: QuestionComposerState,
    onDraftChange: (String) -> Unit,
    onAddReminder: (ReminderPick) -> Unit,
    onRemoveReminder: (Instant) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var confirmingExit by remember { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        if (state.submitting) {
            // 写盘进行中：不弹确认也不退栈，等保存回调决定去向。
        } else if (state.draft.isNotBlank()) {
            confirmingExit = true
        } else {
            onBack()
        }
    }

    // 系统返回键与页头返回走同一条确认：不让人在键盘上误触一下就丢掉刚写的字。
    // 写盘进行中先把返回吃掉，等保存回调决定去向。
    BackHandler(enabled = state.draft.isNotBlank()) {
        if (!state.submitting) confirmingExit = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        QuestionScreenHeader(
            title = stringResource(R.string.question_compose_title),
            onBack = requestBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.dateLabel?.let { day ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.question_compose_for_day, day),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                // 问题可以是一段话，但页面上只留一块能看见行数的高度。
                minLines = 5,
                maxLines = 10,
                shape = RoundedCornerShape(16.dp),
                placeholder = {
                    Text(text = stringResource(R.string.question_compose_placeholder))
                },
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                QuestionReminderEditor(
                    reminders = state.reminders,
                    onAdd = onAddReminder,
                    onRemove = onRemoveReminder,
                    modifier = Modifier.padding(14.dp),
                    animationsEnabled = animationsEnabled,
                )
            }

            Text(
                text = stringResource(R.string.question_compose_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            QuestionFade(visible = state.error != null, animationsEnabled = animationsEnabled) {
                val message = state.error
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
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
            onClick = onSave,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.submitting) R.string.question_saving else R.string.question_save,
                ),
            )
        }
    }

    if (confirmingExit) {
        AlertDialog(
            onDismissRequest = { confirmingExit = false },
            title = { Text(text = stringResource(R.string.question_discard_title)) },
            text = { Text(text = stringResource(R.string.question_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingExit = false
                        onBack()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.question_discard_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingExit = false }) {
                    Text(text = stringResource(R.string.question_discard_keep))
                }
            },
        )
    }
}
