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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.meteor.memory.core.storage.MediaKind

/**
 * Formatting bar: what the editor can insert (links, media, bold, colour) plus an
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
    /** False hides the image entry entirely (a host without a media import path). */
    showImageAction: Boolean = true,
    onBold: () -> Unit = {},
    onColor: (String) -> Unit = { },
    colors: List<String> = MarkdownInlineFormat.COLORS,
    /**
     * The format mode currently switched on, if any.
     *
     * ADR-051: the buttons are **toggles**, not one-shot commands, so they have
     * to show what is active. The bold button tints, and the colour button
     * becomes a filled dot in the chosen colour.
     */
    activeFormat: InlineFormat? = null,
    /**
     * Host-supplied media insert, i.e. ADR-052 §5 D's `onPickMedia(kinds)`.
     *
     * Null — the default — keeps the pre-ADR-052 single image button wired to
     * [onImage]: a host that has not adopted the media archive keeps working
     * unchanged (R2). Non-null replaces that one button with **three** insert
     * actions (image / video / audio), each reporting the kinds it wants the
     * host to let the user choose from. The editor never imports anything
     * itself: ownership (`personId` / `_self`) is decided by the host.
     */
    onPickMedia: ((Set<MediaKind>) -> Unit)? = null,
    /** True while an import is running: repeat taps are disabled (ADR-052 §5 D). */
    mediaInserting: Boolean = false,
) {
    var paletteOpen by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    // Jumping to preview hides the palette anyway; closing the state keeps the
    // flag from staying "open" behind the preview.
    LaunchedEffect(isPreviewing) {
        if (isPreviewing) {
            paletteOpen = false
            mediaOpen = false
        }
    }

    val bold = activeFormat as? InlineFormat.Bold
    val activeColor = (activeFormat as? InlineFormat.Color)?.hex

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

            if (showImageAction) {
                if (onPickMedia == null) {
                    val imageLabel = stringResource(R.string.editor_image)
                    ToolbarAction(
                        label = imageLabel,
                        enabled = !isPreviewing,
                        onClick = onImage,
                    ) { ImageIcon() }
                } else {
                    // Three insert actions do not fit beside link/bold/colour/preview
                    // on a 360dp screen, so they live in a second row of the same
                    // card — the shape the colour palette already established. The
                    // row keeps the toolbar height stable and nothing scrolls away.
                    val insertLabel = stringResource(R.string.editor_insert_media)
                    ToolbarAction(
                        label = insertLabel,
                        enabled = !isPreviewing && !mediaInserting,
                        onClick = {
                            paletteOpen = false
                            mediaOpen = !mediaOpen
                        },
                    ) { InsertMediaIcon() }
                }
            }

            val boldLabel = stringResource(R.string.editor_fmt_bold)
            ToolbarAction(
                label = boldLabel,
                enabled = !isPreviewing,
                onClick = onBold,
                selected = bold != null,
            ) { BoldIcon(selected = bold != null) }

            val colorLabel = stringResource(R.string.editor_fmt_color)
            ToolbarAction(
                label = colorLabel,
                enabled = !isPreviewing,
                onClick = {
                    mediaOpen = false
                    paletteOpen = !paletteOpen
                },
                selected = activeColor != null,
            ) { ColorIcon(paletteOpen = paletteOpen, activeColor = activeColor) }

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

        if (onPickMedia != null && mediaOpen && !isPreviewing) {
            MediaInsertRow(
                enabled = !mediaInserting,
                onPick = { kinds ->
                    mediaOpen = false
                    onPickMedia(kinds)
                },
            )
        }

        if (paletteOpen && !isPreviewing && colors.isNotEmpty()) {
            ColorPalette(
                colors = colors,
                activeColor = activeColor,
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
private fun ColorPalette(
    colors: List<String>,
    activeColor: String?,
    onPick: (String) -> Unit,
) {
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
            val active = activeColor != null && activeColor.equals(hex, ignoreCase = true)
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
                            // The active swatch gets a heavy ring so "on" is
                            // visible without relying on colour alone.
                            BorderStroke(
                                if (active) ACTIVE_RING else 1.dp,
                                if (active) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
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
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            // A selected action gets a tinted pill: the toggle has to be visible
            // at a glance, since it changes what typing does (ADR-051).
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A bold "B", drawn as two stacked bowls so no font is required. */
@Composable
private fun BoldIcon(selected: Boolean) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.secondary
    }
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
 * "colour". While a colour mode is on the bar becomes a filled dot **in that
 * colour**, so the active format is readable at a glance (ADR-051).
 */
@Composable
private fun ColorIcon(paletteOpen: Boolean, activeColor: String?) {
    val selected = activeColor != null
    val tint = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val markColor = activeColor?.let(::swatchColor) ?: tint

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
        if (paletteOpen || selected) {
            // Filled dot: either the palette is open, or a colour is active.
            drawCircle(
                color = markColor,
                radius = 2.6f * unit,
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

/**
 * The three insert actions of ADR-052 §5 D, as a second row inside the same card.
 *
 * Each one reports **the kinds it wants the user to choose from**; the host opens
 * the picker, imports, and appends the Markdown. Keeping this row inside the card
 * (rather than a popup) means it cannot end up off-screen and the toolbar height
 * never jumps while the user is aiming at a button.
 */
@Composable
private fun MediaInsertRow(
    enabled: Boolean,
    onPick: (Set<MediaKind>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MediaInsertAction(
            label = stringResource(R.string.editor_insert_image),
            enabled = enabled,
            onClick = { onPick(setOf(MediaKind.IMAGE)) },
        ) { ImageIcon() }

        MediaInsertAction(
            label = stringResource(R.string.editor_insert_video),
            enabled = enabled,
            onClick = { onPick(setOf(MediaKind.VIDEO)) },
        ) { VideoIcon() }

        MediaInsertAction(
            label = stringResource(R.string.editor_insert_audio),
            enabled = enabled,
            onClick = { onPick(setOf(MediaKind.AUDIO)) },
        ) { AudioIcon() }

        Spacer(modifier = Modifier.weight(1f))
    }
}

/** A labelled insert chip: same 44dp target, but with the word next to the glyph. */
@Composable
private fun MediaInsertAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * A "＋" over a small frame: "insert media", opening the second row.
 *
 * `Icons.Default` is not on `:feature-editor`'s dependency list (the module draws
 * its own glyphs), so this is drawn like every other toolbar icon.
 */
@Composable
private fun InsertMediaIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(3f * unit, 5f * unit),
            size = Size(12f * unit, 12f * unit),
            cornerRadius = CornerRadius(2.5f * unit),
            style = Stroke(width = stroke),
        )
        val plus = Path().apply {
            moveTo(18f * unit, 13f * unit)
            lineTo(18f * unit, 21f * unit)
            moveTo(14f * unit, 17f * unit)
            lineTo(22f * unit, 17f * unit)
        }
        drawPath(
            path = plus,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** A rounded rectangle with a play triangle: "video". */
@Composable
private fun VideoIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val unit = size.width / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(2f * unit, 5f * unit),
            size = Size(20f * unit, 14f * unit),
            cornerRadius = CornerRadius(3f * unit),
            style = Stroke(width = stroke),
        )
        val triangle = Path().apply {
            moveTo(10f * unit, 9f * unit)
            lineTo(15.5f * unit, 12f * unit)
            lineTo(10f * unit, 15f * unit)
            close()
        }
        drawPath(path = triangle, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

/** Two stacked note bars with a dot: "audio". */
@Composable
private fun AudioIcon() {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val unit = size.width / 24f
        val notes = Path().apply {
            moveTo(10f * unit, 16f * unit)
            lineTo(10f * unit, 6f * unit)
            lineTo(18f * unit, 4.5f * unit)
            lineTo(18f * unit, 14f * unit)
        }
        drawPath(path = notes, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(color = tint, radius = 2.4f * unit, center = Offset(7.6f * unit, 16.4f * unit))
        drawCircle(color = tint, radius = 2.4f * unit, center = Offset(15.6f * unit, 14.6f * unit))
    }
}

/** Small dot, but a full-size tap target: 5 swatches fit a 360dp-wide toolbar. */
private val SWATCH_TOUCH = 44.dp
private val SWATCH_DOT = 26.dp
private val ACTIVE_RING = 2.5.dp
