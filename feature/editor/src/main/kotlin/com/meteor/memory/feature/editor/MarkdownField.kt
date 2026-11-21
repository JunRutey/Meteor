package com.meteor.memory.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.core.storage.ImageStore

/**
 * The whole Markdown editing surface: toolbar, the body, and the preview.
 *
 * **An image reference is shown as the picture, not as its Markdown** (ADR-049).
 * The body is split into blocks by [MarkdownBlocks]: text runs become text
 * fields, and a line that held only `![描述](meteor://image/<token>)` becomes an
 * image card with an editable description. The file on disk still holds the
 * plain reference, so exports, the search index and the AI tools are unchanged.
 *
 * Nothing is hidden inside a text field. That was the ADR-021 rule and it still
 * holds: hiding characters needs an offset mapping, and the offset mapping was
 * exactly what used to crash and jump the caret. Images escape it because a
 * whole line is lifted out of the text field, not a span of characters.
 */
@Composable
fun MarkdownField(
    text: String,
    previewing: Boolean,
    onTextChanged: (String) -> Unit,
    onPreviewToggle: () -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: (@Composable () -> Unit)? = null,
    /** Tokens whose ciphertext exists but could not be decoded. */
    missingImages: Set<String> = emptySet(),
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
    /**
     * Reports whether a text block currently holds focus.
     *
     * The person editor uses it to shrink its identity card while the keyboard
     * is open (ADR-046). Default is a no-op so the diary editor is unaffected.
     * Preview replaces the body, so losing the field always reports `false`.
     */
    onFocusChanged: (Boolean) -> Unit = {},
    /** 日记页把「预览」放进页头胶囊时传 false，工具栏只留插入类动作。 */
    showPreviewAction: Boolean = true,
    /** Writes the description (alt text) of one image reference. */
    onImageCaptionChanged: (String, String) -> Unit = { _, _ -> },
) {
    var showLinkDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var describing by remember { mutableStateOf<BodyBlock.Image?>(null) }

    // The blocks are the editing state. They are only re-parsed when the body
    // changed from *outside* (an inserted image or link, or a freshly loaded
    // record); typing updates one block in place so no field loses focus.
    var blocks by remember { mutableStateOf(MarkdownBlocks.parse(text)) }
    LaunchedEffect(text) {
        if (MarkdownBlocks.serialize(blocks) != text) blocks = MarkdownBlocks.parse(text)
    }

    val focusedBlocks = remember { mutableStateListOf<Int>() }

    // Formatting acts on the block that holds the caret, so the selection has to
    // live above the individual text fields. -1 means "no block has focus yet".
    var activeBlock by remember { mutableStateOf(-1) }
    var activeSelection by remember { mutableStateOf(TextRange.Zero) }
    // Selection a formatting action wants restored after it rewrites a block.
    var pendingSelection by remember { mutableStateOf<TextRange?>(null) }

    /**
     * The format mode currently on, if any (ADR-051).
     *
     * Held here, next to the text, so the toolbar's highlight and what typing
     * actually produces are the same state — a mode stored in two places would
     * eventually disagree.
     */
    var activeFormat by remember { mutableStateOf<InlineFormat?>(null) }

    // Preview removes the body, so nothing can keep focus. Report the blur
    // explicitly instead of leaving a stale "focused" flag in the host screen.
    LaunchedEffect(previewing) {
        if (previewing) {
            focusedBlocks.clear()
            activeBlock = -1
            activeFormat = null
            onFocusChanged(false)
        }
    }

    fun setBlockText(index: Int, value: String) {
        if (blocks.getOrNull(index) !is BodyBlock.Text) return
        val next = blocks.toMutableList()
        next[index] = BodyBlock.Text(value)
        blocks = next
        onTextChanged(MarkdownBlocks.serialize(next))
    }

    /** The block a formatting toggle should act on, with the caret inside it. */
    fun targetBlock(): Pair<Int, BodyBlock.Text>? {
        val index = activeBlock.takeIf { blocks.getOrNull(it) is BodyBlock.Text }
            ?: blocks.indexOfFirst { it is BodyBlock.Text }
        val block = blocks.getOrNull(index) as? BodyBlock.Text ?: return null
        return index to block
    }

    /**
     * Switches one format mode on or off (ADR-051).
     *
     * Two shapes, because both are natural:
     *
     * - **a selection exists** — wrap it right now, and arm the mode so the caret
     *   lands just past the run and keeps typing in that style;
     * - **no selection** — arm the mode and write the opening marker so typing
     *   starts inside the run, then close it when the mode is switched off.
     *
     * Tapping the same format again always turns it off and balances the markers.
     */
    fun toggleFormat(tapped: InlineFormat) {
        val target = targetBlock() ?: return
        val (index, block) = target
        val selection = if (activeBlock == index) {
            activeSelection
        } else {
            TextRange(block.raw.length)
        }

        val wasActive = activeFormat == tapped

        // A real selection is a one-shot wrap; the mode stays armed afterwards.
        if (!wasActive && !selection.collapsed) {
            val wrapped = wrapSelection(block.raw, selection, tapped)
            activeBlock = index
            activeFormat = tapped
            setBlockText(index, wrapped.text)
            pendingSelection = TextRange(wrapped.selectionEnd, wrapped.selectionEnd)
            return
        }

        val caret = selection.max

        if (wasActive) {
            val closed = MarkdownInlineEditing.close(block.raw, caret, tapped)
            activeBlock = index
            activeFormat = null
            if (closed.text != block.raw) {
                setBlockText(index, closed.text)
                pendingSelection = TextRange(closed.selectionStart, closed.selectionEnd)
            }
            return
        }

        // Switching on, or switching straight to another colour: retire the old
        // run first so two spans never nest.
        var text = block.raw
        var at = caret
        activeFormat?.let { previous ->
            val closed = MarkdownInlineEditing.close(text, at, previous)
            text = closed.text
            at = closed.selectionStart
        }

        val opened = MarkdownInlineEditing.open(text, at, tapped)
        activeBlock = index
        activeFormat = tapped
        setBlockText(index, opened.text)
        pendingSelection = TextRange(opened.selectionStart, opened.selectionEnd)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EditorToolbar(
            isPreviewing = previewing,
            onLink = { showLinkDialog = true },
            onImage = onPickImage,
            onTogglePreview = onPreviewToggle,
            showPreviewAction = showPreviewAction,
            onBold = { toggleFormat(InlineFormat.Bold) },
            onColor = { hex -> toggleFormat(InlineFormat.Color(hex)) },
            activeFormat = activeFormat,
        )

        if (previewing) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                MarkdownPreview(
                    body = text,
                    resolveImage = resolveImage,
                    onActivate = onActivate,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(EDITOR_SHAPE)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), EDITOR_SHAPE),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    blocks.forEachIndexed { index, block ->
                        key(index) {
                            when (block) {
                                is BodyBlock.Text -> TextBlock(
                                    value = block.raw,
                                    enabled = enabled,
                                    // The hint belongs to the first *text* block,
                                    // which may not be the first block at all.
                                    placeholder = placeholder.takeIf {
                                        index == blocks.indexOfFirst { it is BodyBlock.Text }
                                    },
                                    onValueChange = { setBlockText(index, it) },
                                    onFocusChanged = { focused ->
                                        if (focused) {
                                            if (index !in focusedBlocks) focusedBlocks.add(index)
                                            activeBlock = index
                                        } else {
                                            focusedBlocks.remove(index)
                                        }
                                        onFocusChanged(focusedBlocks.isNotEmpty())
                                    },
                                    onSelectionChanged = { selection ->
                                        if (activeBlock == index) activeSelection = selection
                                    },
                                    restoredSelection = pendingSelection.takeIf { activeBlock == index },
                                    onSelectionRestored = { pendingSelection = null },
                                )

                                is BodyBlock.Image -> ImageBlock(
                                    block = block,
                                    bitmap = resolveImage(block.token),
                                    isMissing = block.token in missingImages,
                                    enabled = enabled,
                                    onOpen = { onActivate(ImageStore.IMAGE_SCHEME + block.token) },
                                    onDescribe = { describing = block },
                                    onRemove = { pendingRemoval = block.token },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLinkDialog) {
        LinkDialog(
            onDismiss = { showLinkDialog = false },
            onConfirm = { target, label ->
                showLinkDialog = false
                onInsertLink(target, label)
            },
        )
    }

    pendingRemoval?.let { token ->
        RemoveImageDialog(
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                pendingRemoval = null
                onRemoveImage(token)
            },
        )
    }

    describing?.let { image ->
        CaptionDialog(
            initial = image.caption,
            onDismiss = { describing = null },
            onConfirm = { caption ->
                describing = null
                onImageCaptionChanged(image.token, caption)
            },
        )
    }
}

/** Wraps a non-empty selection in one format's markers (ADR-051). */
private fun wrapSelection(text: String, selection: TextRange, format: InlineFormat): InlineEdit {
    val open = MarkdownInlineEditing.openingMarker(format)
    val close = MarkdownInlineEditing.closingMarker(format)
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    return InlineEdit(
        text = text.substring(0, start) + open + text.substring(start, end) + close +
            text.substring(end),
        selectionStart = start + open.length,
        selectionEnd = end + open.length,
    )
}

/** A run of ordinary lines. Borderless: the surrounding box draws the frame. */
@Composable
private fun TextBlock(
    value: String,
    enabled: Boolean,
    placeholder: (@Composable () -> Unit)?,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSelectionChanged: (TextRange) -> Unit,
    restoredSelection: TextRange?,
    onSelectionRestored: () -> Unit,
) {
    // The field keeps its own value object so the selection is available to the
    // formatting actions. [value] is always the *source* text — the stored
    // Markdown — and every caret this composable reads or writes is a source
    // offset, which is what the transformation maps between (ADR-051).
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    // The last text this field itself echoed upward. Only a change that did not
    // come from here is an outside edit (an inserted image or link), and only an
    // outside edit may reset the caret.
    var echo by remember { mutableStateOf(value) }

    LaunchedEffect(value) {
        // A formatting action brings its own selection; letting the resync also
        // run would overwrite it with "caret at the end".
        if (restoredSelection != null) {
            echo = value
            return@LaunchedEffect
        }
        if (value != echo) {
            field = TextFieldValue(value, selection = TextRange(value.length))
            echo = value
        }
    }

    // A formatting action rewrote this block: put the caret back where it asked.
    LaunchedEffect(restoredSelection, value) {
        val target = restoredSelection ?: return@LaunchedEffect
        val clamped = TextRange(
            target.start.coerceIn(0, field.text.length),
            target.end.coerceIn(0, field.text.length),
        )
        field = field.copy(selection = clamped)
        onSelectionRestored()
    }

    // Report the caret so a toolbar action applied from outside knows what to
    // wrap. The effect re-runs on every keystroke and every caret move.
    LaunchedEffect(field.selection, field.text) {
        onSelectionChanged(field.selection)
    }

    OutlinedTextField(
        value = field,
        onValueChange = { next ->
            field = next
            echo = next.text
            onValueChange(next.text)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused) },
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 17.sp,
            lineHeight = 26.sp,
        ),
        // Markers are hidden and the text they wrap is drawn styled. The source
        // string keeps the markers, so storage is unchanged and the caret is
        // mapped rather than guessed (ADR-051).
        visualTransformation = remember { MarkdownStyledTransformation() },
        placeholder = placeholder,
        shape = RoundedCornerShape(0.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * The picture itself, plus its description.
 *
 * Three states, all honest: a real picture, a "cannot decode" frame for a token
 * whose file is gone, and an empty frame while the decode is still running.
 */
@Composable
private fun ImageBlock(
    block: BodyBlock.Image,
    bitmap: ImageBitmap?,
    isMissing: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDescribe: () -> Unit,
    onRemove: () -> Unit,
) {
    val removeLabel = stringResource(R.string.editor_remove_image)
    val shape = RoundedCornerShape(14.dp)

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = block.caption.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.editor_image_placeholder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .clickable(enabled = true, onClick = onOpen),
                    contentScale = ContentScale.FillWidth,
                )

                isMissing -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MISSING_IMAGE_HEIGHT)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.editor_image_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MISSING_IMAGE_HEIGHT)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            if (enabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 6.dp, end = 6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable(onClick = onRemove)
                        .semantics { contentDescription = removeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // The description doubles as the Markdown alt text, so it is worth
        // showing: it is what survives in an export and in the search index.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onDescribe)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.caption.ifBlank {
                    stringResource(R.string.editor_image_description_add)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (block.caption.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun CaptionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var caption by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.editor_image_description_title)) },
        text = {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = { Text(text = stringResource(R.string.editor_image_description_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(caption) }) {
                Text(text = stringResource(R.string.editor_image_description_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.editor_cancel))
            }
        },
    )
}

@Composable
private fun RemoveImageDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.editor_remove_image_title)) },
        text = { Text(text = stringResource(R.string.editor_remove_image_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.editor_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.editor_cancel))
            }
        },
    )
}

@Composable
private fun LinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    var target by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.editor_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.editor_link_target)) },
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.editor_link_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(target.trim(), label.trim().takeIf { it.isNotEmpty() }) },
                enabled = target.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.editor_link_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.editor_link_cancel))
            }
        },
    )
}

private val EDITOR_SHAPE = RoundedCornerShape(20.dp)
private val MISSING_IMAGE_HEIGHT = 140.dp
