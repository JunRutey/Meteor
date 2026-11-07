package com.meteor.memory.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import com.meteor.memory.feature.editor.MarkdownPreview

/**
 * 只读预览：正文用与编辑器同一套 Markdown 渲染，右上角「修改日记」跳编辑。
 * 记录不存在时不显示修改入口。
 */
@Composable
fun DiaryPreviewScreen(
    state: DiaryPreviewState,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onActivate: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiaryScreenHeader(
            dateLabel = state.dateLabel,
            supportingContent = {
                state.signedAtLabel?.let { stamped ->
                    Text(
                        text = stringResource(R.string.diary_signed_at, stamped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            if (state.exists) {
                TextButton(onClick = onEdit) {
                    Text(text = stringResource(R.string.diary_preview_edit))
                }
            }
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.diary_preview_back))
            }
        }

        if (state.exists) {
            MarkdownPreview(
                body = state.body,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.medium,
                    ),
                resolveImage = { token -> state.images[token] },
                onActivate = onActivate,
            )
        } else {
            Text(
                text = stringResource(R.string.diary_row_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
