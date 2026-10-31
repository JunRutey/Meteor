package com.meteor.memory.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * Formatting bar. Stateless: it only emits intents, so the same bar can drive a
 * future rich-text implementation of [EditorModule].
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBold, enabled = !isPreviewing) {
            Text("B", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onItalic, enabled = !isPreviewing) {
            Text("I", style = MaterialTheme.typography.bodyLarge.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            ))
        }

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

        TextButton(onClick = onLink, enabled = !isPreviewing) {
            Text(stringResource(R.string.editor_link), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onImage, enabled = !isPreviewing) {
            Text(stringResource(R.string.editor_image), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onTogglePreview) {
            Text(
                text = stringResource(
                    if (isPreviewing) R.string.editor_edit else R.string.editor_preview,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Preview swatch colour, exposed for tests and the toolbar above. */
internal fun colorOf(hex: String): Color = MarkdownStyles.parseColor(hex)
