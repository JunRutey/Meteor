package com.meteor.memory.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.feature.media.AudioPlayerCard
import com.meteor.memory.feature.media.LocalMediaHost
import com.meteor.memory.feature.media.VideoPlayerCard

/**
 * The kind-specific surface of one `![描述](meteor://media/<id>)` line (ADR-055).
 *
 * Shared by the editing surface and [MarkdownPreview] so the two modes cannot
 * disagree about what a stored reference looks like. The body carries only the id;
 * the host answers the kind through [MediaBlockState].
 *
 * Video and audio become the real `:feature-media` players whenever a
 * `MediaHost` is installed in the composition (`:app` installs one for the whole
 * tree). Without it the cards would have no way to decrypt, so they fall back to a
 * labelled frame instead of a player that can only ever fail.
 */
@Composable
internal fun StoredMediaSurface(
    block: BodyBlock.Media,
    state: MediaBlockState,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier,
    frameHeight: Dp = MEDIA_FRAME_HEIGHT,
) {
    val ref = MediaStore.MEDIA_SCHEME + block.id
    val shape = RoundedCornerShape(14.dp)
    val mediaHost = LocalMediaHost.current

    when (state) {
        MediaBlockState.Pending -> MediaFrame(
            label = stringResource(R.string.editor_media_pending),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = shape,
            height = frameHeight,
            modifier = modifier,
        )

        MediaBlockState.Importing -> MediaFrame(
            label = stringResource(R.string.editor_media_importing),
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = shape,
            height = frameHeight,
            modifier = modifier,
        )

        MediaBlockState.Missing -> MediaFrame(
            label = stringResource(R.string.editor_media_missing),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            shape = shape,
            height = frameHeight,
            modifier = modifier,
        )

        is MediaBlockState.Image -> {
            val bitmap = state.bitmap
            if (bitmap == null) {
                MediaFrame(
                    label = stringResource(R.string.editor_media_pending),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = shape,
                    height = frameHeight,
                    modifier = modifier,
                )
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = block.caption.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.editor_image_placeholder),
                    modifier = modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .clickable { onActivate(ref) },
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        MediaBlockState.Video -> if (mediaHost != null) {
            VideoPlayerCard(ref = ref, modifier = modifier.fillMaxWidth())
        } else {
            MediaFrame(
                label = stringResource(R.string.editor_media_video),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = shape,
                height = frameHeight,
                modifier = modifier.clickable { onActivate(ref) },
            )
        }

        MediaBlockState.Audio -> if (mediaHost != null) {
            AudioPlayerCard(ref = ref, modifier = modifier.fillMaxWidth())
        } else {
            MediaFrame(
                label = stringResource(R.string.editor_media_audio),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = shape,
                height = AUDIO_FRAME_HEIGHT,
                modifier = modifier.clickable { onActivate(ref) },
            )
        }
    }
}

/**
 * One media reference inside the **editing surface**.
 *
 * Adds the two editing affordances on top of [StoredMediaSurface]: a remove button
 * in the corner and an editable description row underneath (the alt text, ADR-049).
 * A missing entry shows the "cannot display" frame instead of silently rendering
 * nothing.
 */
@Composable
internal fun MediaBlock(
    block: BodyBlock.Media,
    state: MediaBlockState,
    enabled: Boolean,
    onActivate: (String) -> Unit,
    onDescribe: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            StoredMediaSurface(
                block = block,
                state = state,
                onActivate = onActivate,
            )

            // An importing card has no stored id yet, so removing it would only
            // orphan a job already running; the remove affordance appears once it
            // resolves (ADR-061).
            if (enabled && state != MediaBlockState.Importing) {
                val removeLabel = stringResource(R.string.editor_remove_media)
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
        Text(
            text = block.caption.ifBlank {
                stringResource(R.string.editor_media_description_add)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (block.caption.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onDescribe)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

/** The neutral "loading" / "cannot display" frame, same height as an image slot. */
@Composable
internal fun MediaFrame(
    label: String,
    container: Color,
    content: Color,
    shape: Shape,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
    }
}

@Composable
internal fun RemoveMediaDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.editor_remove_media_title)) },
        text = { Text(text = stringResource(R.string.editor_remove_media_body)) },
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
internal fun MediaCaptionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var caption by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.editor_media_description_title)) },
        text = {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3,
                placeholder = { Text(text = stringResource(R.string.editor_media_description_add)) },
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

internal val MEDIA_FRAME_HEIGHT = 140.dp
internal val AUDIO_FRAME_HEIGHT = 64.dp
