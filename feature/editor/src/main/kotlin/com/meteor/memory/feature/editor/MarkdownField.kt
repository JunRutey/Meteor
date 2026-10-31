package com.meteor.memory.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * The whole Markdown editing surface: toolbar, text field (with the markup
 * hidden), preview, and the strip of images currently referenced by the body.
 *
 * Shared by the diary editor and the person editor so both get the same caret
 * handling — the part that used to drift between callers. Edits are reported
 * through [onTextChanged]; the composable keeps its own [TextFieldValue] so the
 * selection survives, and resyncs only when the text changes from outside
 * (inserted image or link).
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
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
) {
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(text) {
        if (text != field.text) field = TextFieldValue(text, TextRange(text.length))
    }

    fun apply(result: EditResult) {
        field = TextFieldValue(result.text, TextRange(result.selectionStart, result.selectionEnd))
        onTextChanged(result.text)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorToolbar(
            isPreviewing = previewing,
            onBold = {
                apply(MarkdownEditing.toggleBold(field.text, field.selection.min, field.selection.max))
            },
            onLink = { showLinkDialog = true },
            onImage = onPickImage,
            onTogglePreview = onPreviewToggle,
        )

        val images = remember(text) { MarkdownEditing.imagesOf(text) }
        if (!previewing && images.isNotEmpty()) {
            ImageStrip(
                images = images,
                resolveImage = resolveImage,
                onRemove = { token -> pendingRemoval = token },
            )
        }

        if (previewing) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                MarkdownPreview(
                    body = text,
                    resolveImage = resolveImage,
                    onActivate = onActivate,
                )
            }
        } else {
            OutlinedTextField(
                value = field,
                onValueChange = { value ->
                    field = value
                    onTextChanged(value.text)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge,
                visualTransformation = MarkdownStyledTransformation(),
                placeholder = placeholder,
            )
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
}

/** Thumbnails of every image in the body, each with its own delete button. */
@Composable
private fun ImageStrip(
    images: List<AttachedImage>,
    resolveImage: (String) -> ImageBitmap?,
    onRemove: (String) -> Unit,
) {
    val removeLabel = stringResource(R.string.editor_remove_image)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(modifier = Modifier.padding(top = 4.dp, end = 4.dp)) {
                ImageThumbnail(image, resolveImage)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { onRemove(image.token) }
                        .semantics { contentDescription = removeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageThumbnail(image: AttachedImage, resolveImage: (String) -> ImageBitmap?) {
    val bitmap = resolveImage(image.token)
    val shape = RoundedCornerShape(6.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = image.label.takeIf { it.isNotBlank() },
            modifier = Modifier.size(THUMBNAIL).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(THUMBNAIL)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.editor_image_short),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

private val THUMBNAIL = 56.dp
