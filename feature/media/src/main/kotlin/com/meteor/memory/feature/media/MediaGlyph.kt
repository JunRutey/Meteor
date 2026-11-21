package com.meteor.memory.feature.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn line icons for the media surfaces.
 *
 * `:feature-gallery` already avoids a Material icon dependency this way and the
 * stroke language matches the calendar, people and diary screens.
 */
internal enum class MediaGlyphKind {
    Back,
    Play,
    Pause,
    Check,
    Missing,
    Video,
    Audio,
    Image,
    Fullscreen,
    FullscreenExit,
}

@Composable
internal fun MediaGlyph(
    kind: MediaGlyphKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 22.dp,
) {
    Canvas(modifier.size(iconSize)) {
        drawMediaGlyph(kind, tint)
    }
}

private fun DrawScope.drawMediaGlyph(kind: MediaGlyphKind, color: Color) {
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
    fun path(fill: Boolean = false, block: Path.() -> Unit) {
        val built = Path().apply(block)
        if (fill) {
            built.fillType = PathFillType.NonZero
            drawPath(built, color)
        } else {
            drawPath(built, color, style = stroke)
        }
    }

    when (kind) {
        MediaGlyphKind.Back -> path {
            move(15f, 5.5f)
            line(8.5f, 12f)
            line(15f, 18.5f)
        }

        MediaGlyphKind.Play -> path(fill = true) {
            move(8.5f, 5.5f)
            line(19f, 12f)
            line(8.5f, 18.5f)
            close()
        }

        MediaGlyphKind.Pause -> {
            drawLine(color, p(9f, 5.5f), p(9f, 18.5f), 2.6f * unit, StrokeCap.Round)
            drawLine(color, p(15f, 5.5f), p(15f, 18.5f), 2.6f * unit, StrokeCap.Round)
        }

        MediaGlyphKind.Check -> path {
            move(5f, 12.4f)
            line(10f, 17.2f)
            line(19f, 7f)
        }

        MediaGlyphKind.Missing -> {
            path {
                move(4.5f, 6.5f)
                line(19.5f, 6.5f)
                line(19.5f, 18f)
                line(4.5f, 18f)
                close()
            }
            line(8f, 15.5f, 11f, 12f)
            line(11f, 12f, 13.5f, 14.5f)
            line(13.5f, 14.5f, 15.5f, 12.5f)
            drawCircle(color, radius = 1.1f * unit, center = p(14.6f, 9.6f))
        }

        MediaGlyphKind.Video -> {
            path {
                move(4f, 7f)
                line(14.5f, 7f)
                line(14.5f, 17f)
                line(4f, 17f)
                close()
            }
            path {
                move(16.5f, 11f)
                line(20f, 8.5f)
                line(20f, 15.5f)
                line(16.5f, 13f)
            }
        }

        MediaGlyphKind.Audio -> {
            path {
                move(9f, 16.5f)
                line(9f, 7f)
                line(17f, 5.5f)
                line(17f, 15f)
            }
            drawCircle(color, radius = 2.2f * unit, center = p(6.8f, 16.5f))
            drawCircle(color, radius = 2.2f * unit, center = p(14.8f, 15f))
        }

        MediaGlyphKind.Image -> {
            path {
                move(4.5f, 6.5f)
                line(19.5f, 6.5f)
                line(19.5f, 18f)
                line(4.5f, 18f)
                close()
            }
            drawCircle(color, radius = 1.6f * unit, center = p(9f, 10.5f))
            line(6.5f, 16f, 11f, 11.5f)
            line(11f, 11.5f, 15.5f, 16f)
        }

        // Landscape fullscreen is "grow into the four corners".
        MediaGlyphKind.Fullscreen -> {
            path {
                move(9f, 4f)
                line(4f, 4f)
                line(4f, 9f)
            }
            path {
                move(15f, 4f)
                line(20f, 4f)
                line(20f, 9f)
            }
            path {
                move(9f, 20f)
                line(4f, 20f)
                line(4f, 15f)
            }
            path {
                move(15f, 20f)
                line(20f, 20f)
                line(20f, 15f)
            }
        }

        // Exiting fullscreen pulls the corners back in.
        MediaGlyphKind.FullscreenExit -> {
            path {
                move(4f, 9f)
                line(9f, 9f)
                line(9f, 4f)
            }
            path {
                move(20f, 9f)
                line(15f, 9f)
                line(15f, 4f)
            }
            path {
                move(4f, 15f)
                line(9f, 15f)
                line(9f, 20f)
            }
            path {
                move(20f, 15f)
                line(15f, 15f)
                line(15f, 20f)
            }
        }
    }
}
