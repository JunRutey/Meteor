package com.meteor.memory.ui.about

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal Markdown line renderer for bundled changelog data: headings, plain
 * paragraphs and unordered lists. Why not reuse MarkdownPreview: it adds its
 * own scrolling container.
 */
@Composable
internal fun DocsLine(raw: String) {
    // Assets stay real Markdown; this renderer strips inline formatting only.
    val line = raw.trimEnd().replace("**", "").replace("`", "")
    when {
        line.isBlank() -> Spacer(Modifier.height(6.dp))
        line.startsWith("### ") ->
            Text(
                text = line.removePrefix("### "),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )

        line.startsWith("## ") ->
            Text(
                text = line.removePrefix("## "),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

        line.startsWith("# ") ->
            Text(
                text = line.removePrefix("# "),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

        line.startsWith("- ") || line.startsWith("* ") ->
            Text(
                text = "\u2022 " + line.drop(2),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp,
            )

        else -> Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp,
        )
    }
}
