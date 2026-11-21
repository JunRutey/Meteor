package com.meteor.memory.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaStore

/**
 * Read-only preview with inline media and **jumpable** references.
 *
 * The preview shows exactly what the editor shows (ADR-049, ADR-055): a legacy
 * `meteor://image/` line becomes the picture, and a `meteor://media/` line becomes
 * a picture, a playable video card or an audio card according to [resolveMedia] —
 * the same [MediaBlockState] the editing surface consumes, so the two modes cannot
 * drift apart.
 *
 * Tapping a link or a reference hands the raw target back to the caller
 * (`https://…`, `meteor://image/…`, `meteor://media/…`, `meteor://person/…`),
 * which decides whether to open a browser, the viewer, or a person page.
 */
@Composable
fun MarkdownPreview(
    body: String,
    modifier: Modifier = Modifier,
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
    /**
     * How to draw one `meteor://media/<id>` line (ADR-055).
     *
     * Defaults to the neutral pending frame, so a caller that has not adopted the
     * media archive still renders the reference as a calm placeholder instead of
     * crashing or leaking internal Markdown into the reading view.
     */
    resolveMedia: (String) -> MediaBlockState = { MediaBlockState.Pending },
) {
    val imageLabel = stringResource(R.string.editor_image_placeholder)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                body.lines().forEach { line ->
                    PreviewLine(line, imageLabel, resolveImage, onActivate, resolveMedia)
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(
    line: String,
    imageLabel: String,
    resolveImage: (String) -> ImageBitmap?,
    onActivate: (String) -> Unit,
    resolveMedia: (String) -> MediaBlockState,
) {
    val trimmed = line.trim()
    val typography = MaterialTheme.typography

    // A whole-line reference is drawn as media, never as Markdown (ADR-049/055).
    val reference = MarkdownReferences.of(line)
    if (reference != null) {
        PreviewReference(reference, imageLabel, resolveImage, onActivate, resolveMedia)
        return
    }

    val (style, content) = when {
        trimmed.startsWith("### ") -> typography.titleSmall to trimmed.removePrefix("### ")
        trimmed.startsWith("## ") -> typography.titleMedium to trimmed.removePrefix("## ")
        trimmed.startsWith("# ") -> typography.titleLarge to trimmed.removePrefix("# ")
        trimmed.startsWith("- ") -> typography.bodyMedium to "• ${trimmed.removePrefix("- ")}"
        else -> typography.bodyMedium to trimmed
    }

    when {
        trimmed.isBlank() -> Unit

        trimmed == "---" -> HorizontalDivider()

        else -> {
            val annotated = MarkdownStyles.annotate(content, imageLabel)
            ClickableText(
                text = annotated,
                style = TextStyle(
                    color = style.color,
                    fontSize = style.fontSize,
                    fontWeight = style.fontWeight,
                    lineHeight = style.lineHeight,
                ),
                onClick = { offset ->
                    annotated
                        .getStringAnnotations(TAG_TARGET, offset, offset)
                        .firstOrNull()
                        ?.let { annotation -> onActivate(annotation.item) }
                },
            )
        }
    }
}

/**
 * One reference line in the preview: the same surface the editor renders, minus
 * the editing affordances (no remove button, no description dialog).
 */
@Composable
private fun PreviewReference(
    reference: LineReference,
    imageLabel: String,
    resolveImage: (String) -> ImageBitmap?,
    onActivate: (String) -> Unit,
    resolveMedia: (String) -> MediaBlockState,
) {
    val typography = MaterialTheme.typography
    val target = when (reference) {
        is LineReference.Image -> ImageStore.IMAGE_SCHEME + reference.token
        is LineReference.Media -> MediaStore.MEDIA_SCHEME + reference.id
    }
    val caption = reference.caption

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (reference) {
            is LineReference.Image -> {
                // resolveImage takes the bare path, exactly like ImageStore; the
                // `meteor://image/` prefix only exists inside a body.
                val bitmap = resolveImage(reference.token)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = caption.takeIf { it.isNotBlank() } ?: imageLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onActivate(target) },
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Text(
                        text = imageLabel,
                        style = typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onActivate(target) },
                    )
                }
            }

            is LineReference.Media -> StoredMediaSurface(
                block = BodyBlock.Media(raw = "", id = reference.id, caption = caption),
                state = resolveMedia(reference.id),
                onActivate = onActivate,
                frameHeight = PREVIEW_MEDIA_HEIGHT,
            )
        }

        // The alt text is the reference's description (ADR-049): worth showing,
        // since it is what survives in every export.
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

private val PREVIEW_MEDIA_HEIGHT = 160.dp
