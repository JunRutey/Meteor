package com.meteor.memory.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 极简 Markdown 行渲染：标题 / 无序列表 / 段落，够用即可。
 *
 * 为什么不复用 MarkdownPreview：那个组件自己带 verticalScroll，套在外层滚动容器里
 * 会触发 "infinite maximum height" 崩溃。
 */
@Composable
internal fun DocsText(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
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
    }
}
