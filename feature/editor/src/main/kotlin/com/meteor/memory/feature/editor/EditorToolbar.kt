package com.meteor.memory.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Colours offered by the formatting bar; kept in sync with the preview parser. */
object EditorColors {
    const val RED = "#E53935"
    const val BLUE = "#1E88E5"
    const val GREEN = "#43A047"

    val all = listOf(RED, BLUE, GREEN)
}

/**
 * Formatting bar laid out in two fixed rows.
 *
 * No horizontal scrolling: every action must stay on screen, including 图片 and
 * 预览 (a scrolling row previously pushed 图片 out of reach).
 */
@Composable
fun EditorToolbar(
    isPreviewing: Boolean,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onColor: (String) -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBold, enabled = !isPreviewing) {
                Text("B", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onItalic, enabled = !isPreviewing) {
                Text("I", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic))
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorColors.all.forEachIndexed { index, hex ->
                    val label = stringResource(R.string.editor_color, index + 1)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MarkdownStyles.parseColor(hex))
                            .clickable(enabled = !isPreviewing) { onColor(hex) }
                            .semantics { contentDescription = label },
                    )
                }
            }

            TextButton(onClick = onTogglePreview) {
                Text(
                    text = stringResource(
                        if (isPreviewing) R.string.editor_edit else R.string.editor_preview,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onLink, enabled = !isPreviewing) {
                Text(stringResource(R.string.editor_link), style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onImage, enabled = !isPreviewing) {
                Text(stringResource(R.string.editor_image), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
