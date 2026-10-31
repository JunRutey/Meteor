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
import androidx.compose.ui.unit.dp

/**
 * The whole Markdown editing surface: toolbar, plain text field, preview, and the
 * strip of images the body currently references.
 *
 * The field is **plain text** (ADR-021): no markup is hidden, so what you see is
 * exactly what is stored. Shared by the diary editor and the person editor so
 * both get the same insert/remove/preview behaviour from one place.
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
) {
    var showLinkDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }

    // The field keeps its own copy of the text: routing every keystroke through
    // the view model makes Chinese IME composition stutter. It resyncs only when
    // the text changes from outside (inserted image or link).
    var draft by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text != draft) draft = text
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorToolbar(
            isPreviewing = previewing,
            onLink = { showLinkDialog = true },
            onImage = onPickImage,
            onTogglePreview = onPreviewToggle,
        )

        val images = remember(text) { MarkdownEditing.imagesOf(text) }
        if (!previewing && images.isNotEmpty()) {
            ImageStrip(
                images = images,
                resolveImage = resolveImage,
                missing = missingImages,
                onRemove = { token -> pendingRemoval = token },
            )
        }

        if (previewing) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                MarkdownPreview(
                    body = draft,
                    resolveImage = resolveImage,
                    onActivate = onActivate,
                )
            }
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { value ->
                    draft = value
                    onTextChanged(value)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge,
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

/**
 * Thumbnails of every image in the body, each with its own delete button.
 *
 * Three states, all honest: a real thumbnail, a "cannot decode" glyph for a
 * token whose file exists but yields no bitmap, and an empty frame while the
 * decode is still running.
 */
@Composable
private fun ImageStrip(
    images: List<AttachedImage>,
    resolveImage: (String) -> ImageBitmap?,
    missing: Set<String>,
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
                ImageThumbnail(
                    image = image,
                    bitmap = resolveImage(image.token),
                    isMissing = image.token in missing,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { onRemove(image.token) }
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
    }
}

@Composable
private fun ImageThumbnail(image: AttachedImage, bitmap: ImageBitmap?, isMissing: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = image.label.takeIf { it.isNotBlank() },
            modifier = Modifier.size(THUMBNAIL).clip(shape),
            contentScale = ContentScale.Crop,
        )

        isMissing -> Box(
            modifier = Modifier
                .size(THUMBNAIL)
                .clip(shape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.editor_image_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        else -> Box(
            modifier = Modifier
                .size(THUMBNAIL)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
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

private val THUMBNAIL = 64.dp
