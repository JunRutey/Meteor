package com.meteor.memory.ui.diary

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.feature.editor.MarkdownField
import com.meteor.memory.feature.editor.MediaBlockState
import com.meteor.memory.feature.media.MediaPickerScreen
import com.meteor.memory.feature.media.PickedMedia
import com.meteor.memory.feature.editor.R as EditorR

/**
 * 日记编辑页：页头（返回 / 日期 / 预览胶囊）+ 格式栏 + 正文 + 本地提示 + 保存。
 *
 * 正文仍然是纯文本 Markdown（ADR-021），视觉按设计稿：2dp 主题色描边的
 * 20dp 圆角输入框、17sp/26sp 行高、52dp 胶囊保存按钮。
 *
 * 插入图片 / 视频 / 音频（ADR-052 §5 D）：工具栏只报出想要的 kind，这一页负责
 * 打开应用内选择器 [MediaPickerScreen]，选完交给 [onImportMedia]。导入（含归属
 * 决定 `_self`）在 ViewModel 里，所以导航宿主只需要接一个回调，不必知道选择器
 * 的存在。传 null 时退化为旧的单张图片入口（[onPickImage]）。
 *
 * 返回确认与删除（T10）：有未保存正文时，系统返回键和页头返回箭头都先弹同一个
 * 对话框（确认返回 / 保存并返回）；已存到档案的日记可在页头删除，删除前二次确认。
 * 这两处文案与人物编辑页保持一致。
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
    /**
     * Deletes this day's diary; [onDeleted] is the host's cue to pop the page,
     * and it only runs once the archive file is gone.
     */
    onDeleteDiary: (onDeleted: () -> Unit) -> Unit,
    /**
     * Saves and then hands the success signal back: the host pops only when the
     * write really landed, so a failed save keeps the page and its error.
     */
    onSaveAndReturn: (onSaved: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
    onImageCaptionChanged: (String, String) -> Unit = { _, _ -> },
    /**
     * 导入用户在媒体选择器里挑中的文件；null 表示这个宿主还没接入 ADR-052。
     *
     * 接入后工具栏出现「插入 → 图片 / 视频 / 音频」，日记的媒体一律归 `_self`
     * （由 ViewModel 的 `personId = null` 决定）。
     */
    onImportMedia: ((List<PickedMedia>) -> Unit)? = null,
    /**
     * Host-owned picker entry (ADR-059). When set, the toolbar opens the host's
     * picker (standalone in-app route or the platform document picker) instead
     * of the in-editor overlay.
     */
    onRequestMediaPicker: ((Set<MediaKind>) -> Unit)? = null,
    resolveMedia: (String) -> MediaBlockState = { MediaBlockState.Pending },
    onRemoveMedia: (String) -> Unit = {},
    onMediaCaptionChanged: (String, String) -> Unit = { _, _ -> },
) {
    // Purely a view concern: the record never stores whether it was last shown
    // as preview.
    var previewing by rememberSaveable { mutableStateOf(false) }
    var confirmingExit by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val mediaPicker = rememberDiaryMediaPicker()

    // System back must not silently drop unsaved text: it goes through exactly
    // the same dialog as the header arrow (T10). Not dirty -> let the system pop
    // as usual, which is why the handler is only installed when there is work
    // worth protecting.
    BackHandler(enabled = state.hasUnsavedChanges) { confirmingExit = true }

    /** Header back: ask first, then pop. */
    val requestBack: () -> Unit = {
        if (state.hasUnsavedChanges) confirmingExit = true else onBack()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            DiaryScreenHeader(
                dateLabel = diaryDayLabel(state.date),
                onBack = requestBack,
                supportingContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 只有「记录于」一行：日期本身已经在标题里，再放一行裸的
                        // 时间戳就是同一个时刻显示两遍（保存后尤其明显）。
                        state.recordedAtLabel?.let { stamped ->
                            Text(
                                text = stringResource(R.string.diary_signed_at, stamped),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.isInserting) {
                            Text(
                                text = insertingLabel(state),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // Only a stored diary can be deleted; a brand-new day has no
                    // record yet, so the entry stays hidden instead of offering
                    // to delete something that does not exist.
                    if (state.exists) {
                        DeleteDiaryChip(
                            enabled = !state.isSaving && !state.deleting && !state.isInserting,
                            onClick = { confirmingDelete = true },
                        )
                    }
                    PreviewChip(
                        previewing = previewing,
                        enabled = !state.isSaving && !state.deleting,
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
                onImageCaptionChanged = onImageCaptionChanged,
                onPickMedia = when {
                    // The host owns the picker choice (ADR-059): it may open the
                    // standalone route or the platform document picker.
                    onRequestMediaPicker != null -> onRequestMediaPicker
                    onImportMedia != null -> mediaPicker::open
                    else -> null
                },
                // Background imports must not disable the toolbar (ADR-061): the
                // user is supposed to be able to add another file while one is
                // still encrypting. Only the legacy in-place image insert blocks it.
                mediaInserting = state.isInsertingImage,
                resolveMedia = resolveMedia,
                onRemoveMedia = onRemoveMedia,
                onMediaCaptionChanged = onMediaCaptionChanged,
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
                enabled = !state.isSaving && !state.isInserting && !state.deleting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Text(
                    text = if (state.isSaving || state.isInserting) {
                        insertingLabel(state)
                    } else {
                        stringResource(R.string.diary_save)
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // The in-editor overlay is only used by hosts that still import in place.
        // When the host installs [onRequestMediaPicker] the picker is a standalone
        // destination instead, so the editor is never covered (ADR-059).
        if (onImportMedia != null && onRequestMediaPicker == null) {
            DiaryMediaPicker(state = mediaPicker, onPicked = onImportMedia)
        }

        if (confirmingExit) {
            UnsavedChangesDialog(
                saving = state.isSaving,
                canSave = !state.isSaving && !state.isInserting && !state.deleting,
                onDismiss = { confirmingExit = false },
                onDiscard = {
                    confirmingExit = false
                    onBack()
                },
                onSaveAndReturn = {
                    confirmingExit = false
                    // The host pops only after the write really landed; a failure
                    // leaves this page up with state.error explaining why.
                    onSaveAndReturn { onBack() }
                },
            )
        }

        if (confirmingDelete) {
            DeleteDiaryDialog(
                deleting = state.deleting,
                onDismiss = { confirmingDelete = false },
                onConfirm = {
                    confirmingDelete = false
                    // Deleting pops the page itself once the file is gone.
                    onDeleteDiary { onBack() }
                },
            )
        }
    }
}

/**
 * 插入进行中的文案（ADR-052 §5 D）。
 *
 * 沿用 `diary_adding_image` 的模式：说清「在做什么」而不是笼统的「请稍候」，
 * 并且按 kind 分开，让用户知道导入的是视频还是音频（大文件耗时明显更长）。
 */
@Composable
private fun insertingLabel(state: DiaryEditorState): String = when {
    state.isSaving -> stringResource(R.string.diary_saving)
    state.insertingMedia == MediaKind.VIDEO -> stringResource(R.string.diary_adding_video)
    state.insertingMedia == MediaKind.AUDIO -> stringResource(R.string.diary_adding_audio)
    state.insertingMedia == MediaKind.IMAGE -> stringResource(R.string.diary_adding_media_image)
    else -> stringResource(R.string.diary_adding_image)
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

/**
 * 「删除日记」胶囊：与 [PreviewChip] 同一形状，但用错误色表示不可恢复。
 *
 * The label is the destructive action itself; the confirmation dialog carries the
 * consequences, so the header stays compact next to 预览.
 */
@Composable
private fun DeleteDiaryChip(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.diary_delete),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * 返回确认（T10）：有未保存内容时统一拦截。
 *
 * 「保存并返回」是主操作：它只在保存成功后由宿主 pop；保存失败时对话框已经关掉，
 * 页面留在原地并由错误通道提示。[onDismiss] 表示留在本页继续编辑。
 */
@Composable
private fun UnsavedChangesDialog(
    saving: Boolean,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onSaveAndReturn: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_body)) },
        confirmButton = {
            TextButton(
                onClick = onSaveAndReturn,
                enabled = canSave && !saving,
            ) {
                Text(stringResource(R.string.editor_unsaved_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = stringResource(R.string.editor_unsaved_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/**
 * 删除当天日记的二次确认。
 *
 * The body spells out both consequences: the record is gone for good, and the
 * pictures / videos / audio it referenced are **not** deleted, so the warning
 * cannot make the user think the media goes away too.
 */
@Composable
private fun DeleteDiaryDialog(
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diary_delete_confirm_title)) },
        text = { Text(stringResource(R.string.diary_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(
                    text = stringResource(
                        if (deleting) R.string.diary_deleting else R.string.diary_delete_confirm_action,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(R.string.diary_delete_cancel))
            }
        },
    )
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
