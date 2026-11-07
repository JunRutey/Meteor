package com.meteor.memory.ui.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.feature.editor.MarkdownField
import com.meteor.memory.feature.editor.R as EditorR

/**
 * 日记编辑页：页头（返回 / 日期 / 预览胶囊）+ 格式栏 + 正文 + 本地提示 + 保存。
 *
 * 正文仍然是纯文本 Markdown（ADR-021），视觉按设计稿：2dp 主题色描边的
 * 20dp 圆角输入框、17sp/26sp 行高、52dp 胶囊保存按钮。
 */
@Composable
fun DiaryEditScreen(
    state: DiaryEditorState,
    onTextChanged: (String) -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
) {
    // Purely a view concern: the record never stores whether it was last shown
    // as preview.
    var previewing by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        DiaryScreenHeader(
            dateLabel = state.dateLabel,
            onBack = onBack,
            supportingContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.signedAtLabel?.let { stamped ->
                        Text(
                            text = stringResource(R.string.diary_signed_at, stamped),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.isInsertingImage) {
                        Text(
                            text = stringResource(R.string.diary_adding_image),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                PreviewChip(
                    previewing = previewing,
                    enabled = !state.isSaving,
                    onToggle = { previewing = !previewing },
                )
            },
        )

        MarkdownField(
            text = state.text,
            previewing = previewing,
            onTextChanged = onTextChanged,
            onPreviewToggle = { previewing = !previewing },
            onInsertLink = onInsertLink,
            onPickImage = onPickImage,
            onRemoveImage = onRemoveImage,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            placeholder = { Text(text = stringResource(R.string.diary_hint)) },
            missingImages = state.missingImages,
            resolveImage = resolveImage,
            onActivate = onActivate,
            showPreviewAction = false,
        )

        state.error?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LockGlyph()
                Text(
                    text = stringResource(R.string.diary_only_local),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (state.saved) {
                    stringResource(R.string.diary_saved)
                } else {
                    stringResource(R.string.diary_char_count, state.text.length)
                },
                fontSize = 13.sp,
                color = if (state.saved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Button(
            onClick = onSave,
            enabled = !state.isSaving && !state.isInsertingImage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        state.isSaving -> R.string.diary_saving
                        state.isInsertingImage -> R.string.diary_adding_image
                        else -> R.string.diary_save
                    },
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PreviewChip(
    previewing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(
                if (previewing) EditorR.string.editor_edit else EditorR.string.editor_preview,
            ),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun LockGlyph() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val unit = size.width / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(5f * unit, 11f * unit),
            size = Size(14f * unit, 9f * unit),
            cornerRadius = CornerRadius(2f * unit),
            style = Stroke(width = stroke),
        )
        val shackle = androidx.compose.ui.graphics.Path().apply {
            moveTo(8f * unit, 11f * unit)
            lineTo(8f * unit, 8f * unit)
            cubicTo(
                8f * unit, 5.8f * unit,
                9.8f * unit, 4f * unit,
                12f * unit, 4f * unit,
            )
            cubicTo(
                14.2f * unit, 4f * unit,
                16f * unit, 5.8f * unit,
                16f * unit, 8f * unit,
            )
            lineTo(16f * unit, 11f * unit)
        }
        drawPath(
            path = shackle,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
