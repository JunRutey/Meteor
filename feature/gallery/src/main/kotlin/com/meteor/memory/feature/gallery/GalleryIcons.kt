package com.meteor.memory.feature.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn 24dp line icons for the gallery and viewer.
 *
 * Keeping them here means `:feature-gallery` needs no Material icon dependency
 * and the stroke language matches the diary, people and calendar screens.
 */
internal enum class GalleryIconKind {
    Back,
    Share,
    Delete,
    Check,
    Video,
    Audio,
    Sort,
}

@Composable
internal fun GalleryIcon(
    kind: GalleryIconKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 22.dp,
) {
    Canvas(modifier.size(iconSize)) {
        drawGalleryIcon(kind, tint)
    }
}

private fun DrawScope.drawGalleryIcon(kind: GalleryIconKind, color: Color) {
    val w = size.width
    val h = size.height
    val unit = size.minDimension / 24f
    val stroke = Stroke(
        width = 1.8f * unit,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    fun p(x: Float, y: Float) = Offset(x * w / 24f, y * h / 24f)
    fun Path.move(x: Float, y: Float) = moveTo(p(x, y).x, p(x, y).y)
    fun Path.line(x: Float, y: Float) = lineTo(p(x, y).x, p(x, y).y)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
    }

    fun path(block: Path.() -> Unit) {
        drawPath(Path().apply(block), color, style = stroke)
    }

    when (kind) {
        GalleryIconKind.Back -> path {
            move(15f, 5.5f)
            line(8.5f, 12f)
            line(15f, 18.5f)
        }

        GalleryIconKind.Share -> {
            line(7.5f, 12f, 16.5f, 6.5f)
            line(7.5f, 12f, 16.5f, 17.5f)
            drawCircle(color, radius = 2.1f * unit, center = p(17.5f, 5.5f))
            drawCircle(color, radius = 2.1f * unit, center = p(6.5f, 12f))
            drawCircle(color, radius = 2.1f * unit, center = p(17.5f, 18.5f))
        }

        GalleryIconKind.Delete -> path {
            move(4.5f, 7f)
            line(19.5f, 7f)
            move(9f, 7f)
            line(9f, 4.8f)
            line(15f, 4.8f)
            line(15f, 7f)
            move(6.8f, 7f)
            line(7.6f, 19.2f)
            line(16.4f, 19.2f)
            line(17.2f, 7f)
            move(10.2f, 10.4f)
            line(10.5f, 15.8f)
            move(13.8f, 10.4f)
            line(13.5f, 15.8f)
        }

        GalleryIconKind.Check -> path {
            move(5f, 12.4f)
            line(10f, 17.2f)
            line(19f, 7f)
        }

        GalleryIconKind.Video -> {
            // A play triangle inside a rounded frame.
            path {
                move(4f, 7f)
                line(4f, 17f)
                line(9.5f, 13.6f)
                line(20f, 18f)
                line(20f, 6f)
                line(9.5f, 10.4f)
                close()
            }
            path {
                move(10.2f, 12f)
                line(14f, 14.4f)
                line(14f, 9.6f)
                close()
            }
        }

        GalleryIconKind.Audio -> {
            // A musical note: stem plus a filled head, mirroring the video badge
            // language but distinct enough to read as sound.
            path {
                move(9f, 6.5f)
                line(9f, 16.5f)
                move(9f, 16.5f)
                line(15f, 13.5f)
                line(15f, 5f)
                line(9f, 6.5f)
            }
            drawCircle(color, radius = 1.9f * unit, center = p(7.5f, 17.5f))
            drawCircle(color, radius = 1.9f * unit, center = p(13.5f, 14f))
        }

        GalleryIconKind.Sort -> {
            // Three descending bars plus an arrow — a "sort" affordance.
            path {
                move(5f, 8f)
                line(19f, 8f)
                move(5f, 12f)
                line(15f, 12f)
                move(5f, 16f)
                line(11f, 16f)
            }
        }
    }
}
