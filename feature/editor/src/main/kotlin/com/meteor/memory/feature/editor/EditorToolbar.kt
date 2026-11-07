package com.meteor.memory.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Formatting bar: the two things the editor can insert (ADR-021) plus an
 * optional preview toggle.
 *
 * Styled after the design sheet: a white 16dp card with 44dp icon targets.
 * No horizontal scrolling: every action must stay on screen.
 */
@Composable
fun EditorToolbar(
    isPreviewing: Boolean,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
    showPreviewAction: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val linkLabel = stringResource(R.string.editor_link)
        ToolbarAction(
            label = linkLabel,
            enabled = !isPreviewing,
            onClick = onLink,
        ) { LinkIcon() }

        val imageLabel = stringResource(R.string.editor_image)
        ToolbarAction(
            label = imageLabel,
            enabled = !isPreviewing,
            onClick = onImage,
        ) { ImageIcon() }

        if (showPreviewAction) {
            val previewLabel = stringResource(
                if (isPreviewing) R.string.editor_edit else R.string.editor_preview,
            )
            ToolbarAction(
                label = previewLabel,
                enabled = true,
                onClick = onTogglePreview,
            ) { PreviewIcon(isPreviewing = isPreviewing) }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ToolbarAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun LinkIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        val path = Path().apply {
            moveTo(10f * unit, 14f * unit)
            cubicTo(
                11.6f * unit, 15.6f * unit,
                14.2f * unit, 15.6f * unit,
                15.7f * unit, 14f * unit,
            )
            lineTo(18.7f * unit, 11f * unit)
            cubicTo(
                20.2f * unit, 9.4f * unit,
                20.2f * unit, 6.9f * unit,
                18.7f * unit, 5.3f * unit,
            )
            cubicTo(
                17.1f * unit, 3.7f * unit,
                14.6f * unit, 3.7f * unit,
                13f * unit, 5.3f * unit,
            )
            lineTo(12f * unit, 6.3f * unit)
            moveTo(14f * unit, 10f * unit)
            cubicTo(
                12.4f * unit, 8.4f * unit,
                9.8f * unit, 8.4f * unit,
                8.3f * unit, 10f * unit,
            )
            lineTo(5.3f * unit, 13f * unit)
            cubicTo(
                3.7f * unit, 14.6f * unit,
                3.7f * unit, 17.1f * unit,
                5.3f * unit, 18.7f * unit,
            )
            cubicTo(
                6.9f * unit, 20.2f * unit,
                9.4f * unit, 20.2f * unit,
                11f * unit, 18.7f * unit,
            )
            lineTo(12f * unit, 17.7f * unit)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun ImageIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(3f * unit, 4f * unit),
            size = Size(18f * unit, 16f * unit),
            cornerRadius = CornerRadius(3f * unit),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = tint,
            radius = 1.6f * unit,
            center = Offset(9f * unit, 10f * unit),
            style = Stroke(width = stroke),
        )
        val path = Path().apply {
            moveTo(4f * unit, 18f * unit)
            lineTo(9f * unit, 13f * unit)
            lineTo(13f * unit, 17f * unit)
            lineTo(16f * unit, 14f * unit)
            lineTo(20f * unit, 18f * unit)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun PreviewIcon(isPreviewing: Boolean) {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        if (isPreviewing) {
            // Pencil: "back to editing".
            val path = Path().apply {
                moveTo(4f * unit, 20f * unit)
                lineTo(4.8f * unit, 15.6f * unit)
                lineTo(15.6f * unit, 4.8f * unit)
                lineTo(19.2f * unit, 8.4f * unit)
                lineTo(8.4f * unit, 19.2f * unit)
                close()
                moveTo(13.6f * unit, 6.8f * unit)
                lineTo(17.2f * unit, 10.4f * unit)
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            val eye = Path().apply {
                moveTo(2.5f * unit, 12f * unit)
                cubicTo(
                    5f * unit, 7.5f * unit,
                    8.3f * unit, 5.5f * unit,
                    12f * unit, 5.5f * unit,
                )
                cubicTo(
                    15.7f * unit, 5.5f * unit,
                    19f * unit, 7.5f * unit,
                    21.5f * unit, 12f * unit,
                )
                cubicTo(
                    19f * unit, 16.5f * unit,
                    15.7f * unit, 18.5f * unit,
                    12f * unit, 18.5f * unit,
                )
                cubicTo(
                    8.3f * unit, 18.5f * unit,
                    5f * unit, 16.5f * unit,
                    2.5f * unit, 12f * unit,
                )
                close()
            }
            drawPath(path = eye, color = tint, style = Stroke(width = stroke))
            drawCircle(
                color = tint,
                radius = 2.6f * unit,
                center = Offset(12f * unit, 12f * unit),
                style = Stroke(width = stroke),
            )
        }
    }
}
