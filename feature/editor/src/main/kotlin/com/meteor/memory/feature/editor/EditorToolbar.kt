package com.meteor.memory.feature.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Formatting bar for the Markdown editor.
 *
 * Stateless on purpose: it only emits intents, so the same bar can drive a
 * future rich-text implementation of [EditorModule].
 */
@Composable
fun EditorToolbar(
    isPreviewing: Boolean,
    onBold: () -> Unit,
    onItalic: () -> Unit,
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
    ) {
        TextButton(onClick = onBold, enabled = !isPreviewing) {
            Text(stringResource(R.string.editor_bold), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onItalic, enabled = !isPreviewing) {
            Text(stringResource(R.string.editor_italic), style = MaterialTheme.typography.labelLarge)
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
