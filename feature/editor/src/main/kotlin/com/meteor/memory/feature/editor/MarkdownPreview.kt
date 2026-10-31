package com.meteor.memory.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Read-only Markdown preview.
 *
 * Intentionally a small renderer: headings, bullets, bold, italic, links and an
 * image placeholder. A full Markdown engine can replace the body of this
 * composable without touching [EditorModule].
 */
@Composable
fun MarkdownPreview(body: String, modifier: Modifier = Modifier) {
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
                    PreviewLine(line)
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(line: String) {
    val trimmed = line.trimEnd()
    when {
        trimmed.isBlank() -> Unit

        trimmed.startsWith("### ") -> Text(
            text = inline(trimmed.removePrefix("### ")),
            style = MaterialTheme.typography.titleSmall,
        )

        trimmed.startsWith("## ") -> Text(
            text = inline(trimmed.removePrefix("## ")),
            style = MaterialTheme.typography.titleMedium,
        )

        trimmed.startsWith("# ") -> Text(
            text = inline(trimmed.removePrefix("# ")),
            style = MaterialTheme.typography.titleLarge,
        )

        trimmed.startsWith("!") && trimmed.contains("](") -> Text(
            text = stringResource(R.string.editor_image_placeholder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        trimmed.startsWith("- ") -> Text(
            text = inline("• ${trimmed.removePrefix("- ")}"),
            style = MaterialTheme.typography.bodyMedium,
        )

        trimmed == "---" -> HorizontalDivider()

        else -> Text(text = inline(trimmed), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Minimal inline parsing: `**bold**`, `*italic*`, `[label](target)`. */
internal fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))

        val label = match.groupValues[1]
        val bold = match.groupValues[3]
        val italic = match.groupValues[4]

        when {
            match.value.startsWith("[") -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                append(label)
                pop()
            }

            bold.isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(bold)
                pop()
            }

            else -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(italic)
                pop()
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

private val INLINE = Regex("""\[([^\]]*)\]\(([^)]*)\)|\*\*([^*]+)\*\*|\*([^*]+)\*""")
