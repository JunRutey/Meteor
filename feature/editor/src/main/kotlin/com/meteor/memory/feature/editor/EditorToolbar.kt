package com.meteor.memory.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Formatting bar: what the editor can insert (links, images, bold, colour) plus an
 * optional preview toggle.
 *
 * Everything here **inserts Markdown syntax** into the plain-text field; nothing
 * is hidden while editing (ADR-021, ADR-048). The preview renders the markers.
 *
 * Styled after the design sheet: a white 16dp card with 44dp icon targets.
 * No horizontal scrolling: every action must stay on screen, so the colour
 * palette opens as a second row inside the same card instead of widening the
 * first one.
 */
@Composable
fun EditorToolbar(
    isPreviewing: Boolean,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
    showPreviewAction: Boolean = true,
    onBold: () -> Unit = {},
    onColor: (String) -> Unit = { },
    colors: List<String> = MarkdownInlineFormat.COLORS,
) {
    var paletteOpen by remember { mutableStateOf(false) }
    // Jumping to preview hides the palette anyway; closing the state keeps the
    // flag from staying "open" behind the preview.
    LaunchedEffect(isPreviewing) {
        if (isPreviewing) paletteOpen = false
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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

            val boldLabel = stringResource(R.string.editor_fmt_bold)
            ToolbarAction(
                label = boldLabel,
                enabled = !isPreviewing,
                onClick = onBold,
            ) { BoldIcon() }

            val colorLabel = stringResource(R.string.editor_fmt_color)
            ToolbarAction(
                label = colorLabel,
                enabled = !isPreviewing,
                onClick = { paletteOpen = !paletteOpen },
            ) { ColorIcon(paletteOpen = paletteOpen) }

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

        if (paletteOpen && !isPreviewing && colors.isNotEmpty()) {
            ColorPalette(
                colors = colors,
                onPick = { hex ->
                    paletteOpen = false
                    onColor(hex)
                },
            )
        }
    }
}

/**
 * One row of swatches, drawn inline rather than in a popup so the toolbar keeps
 * its height while open and the palette can never end up off-screen.
 */
@Composable
private fun ColorPalette(colors: List<String>, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        colors.forEach { hex ->
            val swatch = swatchColor(hex) ?: MaterialTheme.colorScheme.secondary
            val label = swatchLabel(hex)
            Box(
                modifier = Modifier
                    .size(SWATCH_TOUCH)
                    .clickable { onPick(hex) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(SWATCH_DOT)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            CircleShape,
                        ),
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * Spoken name of a swatch.
 *
 * The palette is a data list, so a colour added there without a string of its
 * own must not crash the toolbar: those fall back to the hex value instead of a
 * hard-coded name or an unresolved id.
 */
@Composable
private fun swatchLabel(hex: String): String {
    val name = when (hex.uppercase()) {
        "#E53935" -> R.string.editor_fmt_color_red
        "#FB8C00" -> R.string.editor_fmt_color_orange
        "#43A047" -> R.string.editor_fmt_color_green
        "#1E88E5" -> R.string.editor_fmt_color_blue
        "#8E24AA" -> R.string.editor_fmt_color_purple
        else -> null
    }
    return if (name != null) {
        stringResource(name)
    } else {
        stringResource(R.string.editor_fmt_color_swatch, hex)
    }
}

/**
 * `#RRGGBB` to a drawable colour, or null when the value is not that shape.
 *
 * Parsed here rather than through `android.graphics.Color` so a bad value only
 * costs a neutral swatch, never a crash while the toolbar composes.
 */
private fun swatchColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return null
    if (digits.any { it.digitToIntOrNull(16) == null }) return null
    return Color(digits.toLong(16) or 0xFF000000L)
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

/** A bold "B", drawn as two stacked bowls so no font is required. */
@Composable
private fun BoldIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.2.dp.toPx()
        val unit = size.width / 24f
        val path = Path().apply {
            moveTo(7.5f * unit, 4.5f * unit)
            lineTo(7.5f * unit, 19.5f * unit)
            moveTo(7.5f * unit, 4.5f * unit)
            lineTo(13.5f * unit, 4.5f * unit)
            cubicTo(
                16.4f * unit, 4.5f * unit,
                18f * unit, 6.1f * unit,
                18f * unit, 8.7f * unit,
            )
            cubicTo(
                18f * unit, 11f * unit,
                16.4f * unit, 12f * unit,
                13.5f * unit, 12f * unit,
            )
            lineTo(7.5f * unit, 12f * unit)
            moveTo(7.5f * unit, 12f * unit)
            lineTo(14.4f * unit, 12f * unit)
            cubicTo(
                17.4f * unit, 12f * unit,
                19f * unit, 13.6f * unit,
                19f * unit, 15.8f * unit,
            )
            cubicTo(
                19f * unit, 18f * unit,
                17.4f * unit, 19.5f * unit,
                14.4f * unit, 19.5f * unit,
            )
            lineTo(7.5f * unit, 19.5f * unit)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * An "A" with a colour bar underneath: the glyph says "text", the bar says
 * "colour". When the palette is open the bar becomes a filled dot so the state is
 * visible without reading the content description.
 */
@Composable
private fun ColorIcon(paletteOpen: Boolean) {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        val glyph = Path().apply {
            moveTo(6f * unit, 15.5f * unit)
            lineTo(12f * unit, 5f * unit)
            lineTo(18f * unit, 15.5f * unit)
            moveTo(8.6f * unit, 12f * unit)
            lineTo(15.4f * unit, 12f * unit)
        }
        drawPath(
            path = glyph,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        if (paletteOpen) {
            drawCircle(
                color = tint,
                radius = 2.4f * unit,
                center = Offset(12f * unit, 19f * unit),
            )
        } else {
            val bar = Path().apply {
                moveTo(7f * unit, 19f * unit)
                lineTo(17f * unit, 19f * unit)
            }
            drawPath(
                path = bar,
                color = tint,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
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

/** Small dot, but a full-size tap target: 5 swatches fit a 360dp-wide toolbar. */
private val SWATCH_TOUCH = 44.dp
private val SWATCH_DOT = 26.dp

