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

/**
 * Read-only preview with inline images and **jumpable** references.
 *
 * Tapping a link or an image hands the raw target back to the caller
 * (`https://…`, `meteor://image/…`, `meteor://person/…`), which decides whether
 * to open a browser, the image viewer, or a person page.
 */
@Composable
fun MarkdownPreview(
    body: String,
    modifier: Modifier = Modifier,
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
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
                    PreviewLine(line, imageLabel, resolveImage, onActivate)
                }
            }
        }
    }
}

private val IMAGE_LINE = Regex("""^!\[[^\]]*\]\(([^)]+)\)$""")

@Composable
private fun PreviewLine(
    line: String,
    imageLabel: String,
    resolveImage: (String) -> ImageBitmap?,
    onActivate: (String) -> Unit,
) {
    val trimmed = line.trim()
    val typography = MaterialTheme.typography

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

        IMAGE_LINE.matches(trimmed) -> {
            val target = IMAGE_LINE.find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
            val bitmap = resolveImage(target)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = imageLabel,
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
