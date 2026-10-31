package com.meteor.memory.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Formatting bar: exactly the three things v0.4 supports (ADR-019) plus the
 * preview toggle.
 *
 * No horizontal scrolling: every action must stay on screen, including 图片 and
 * 预览 (a scrolling row previously pushed 图片 out of reach).
 */
@Composable
fun EditorToolbar(
    isPreviewing: Boolean,
    onBold: () -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(onClick = onBold, enabled = !isPreviewing) {
            Text(
                text = "B",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        TextButton(onClick = onLink, enabled = !isPreviewing) {
            Text(stringResource(R.string.editor_link), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onImage, enabled = !isPreviewing) {
            Text(stringResource(R.string.editor_image), style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.weight(1f))

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
}
