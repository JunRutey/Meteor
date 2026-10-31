package com.meteor.memory.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 极简 Markdown 行渲染：标题 / 无序列表 / 段落，够用即可。
 *
 * Why not reuse MarkdownPreview: it adds its own scrolling container.
 */
@Composable
internal fun DocsLine(raw: String) {
    // Assets stay real Markdown; this renderer strips inline formatting only.
    val line = raw.trimEnd().replace("**", "").replace("`", "")
    when {
        line.isBlank() -> Spacer(Modifier.height(6.dp))
        line.startsWith("### ") ->
            Text(line.removePrefix("### "), style = MaterialTheme.typography.titleSmall)
        line.startsWith("## ") ->
            Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium)
        line.startsWith("# ") ->
            Text(line.removePrefix("# "), style = MaterialTheme.typography.headlineSmall)
        line.startsWith("- ") || line.startsWith("* ") ->
            Text("\u2022 " + line.drop(2), style = MaterialTheme.typography.bodyMedium)
        else -> Text(line, style = MaterialTheme.typography.bodyMedium)
    }
}
