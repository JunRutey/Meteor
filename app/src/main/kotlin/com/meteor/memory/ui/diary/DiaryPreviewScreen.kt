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
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.feature.editor.MarkdownPreview
import com.meteor.memory.feature.editor.MediaBlockState

/**
 * 只读预览：正文用与编辑器同一套 Markdown 渲染，右上角「修改日记」跳编辑。
 * 记录不存在时不显示修改入口。
 *
 * ADR-055：`meteor://media/` 引用在这里和编辑模式画的是同一张卡（图片 / 视频
 * 播放器 / 音频播放器），所以「编辑」与「预览」不会对同一份正文给出两种样子。
 */
@Composable
fun DiaryPreviewScreen(
    state: DiaryPreviewState,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onActivate: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    resolveMedia: (String) -> MediaBlockState = { MediaBlockState.Pending },
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiaryScreenHeader(
            // 主日期是日记所属那天。当天写的带写入时刻（15:16），补写的只剩
            // 那天本身，写入时刻挪到下面的「记录于」。
            dateLabel = if (state.backfilled) {
                diaryDayLabel(state.date)
            } else {
                state.writtenAtLabel ?: diaryDayLabel(state.date)
            },
            onBack = onBack,
            supportingContent = {
                // 「记录于」只在补写时出现：当天写的写入时刻已经在主日期里，
                // 再来一行就是把同一个时刻显示两遍。
                if (state.backfilled) {
                    state.writtenAtLabel?.let { stamped ->
                        Text(
                            text = stringResource(R.string.diary_signed_at, stamped),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                if (state.exists) {
                    TextButton(onClick = onEdit) {
                        Text(text = stringResource(R.string.diary_preview_edit))
                    }
                }
            },
        )

        if (state.exists) {
            MarkdownPreview(
                body = state.body,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.medium,
                    ),
                resolveImage = { token -> state.images[token] },
                onActivate = onActivate,
                resolveMedia = resolveMedia,
            )
        } else {
            Text(
                text = stringResource(R.string.diary_row_empty),
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
