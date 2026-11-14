package com.meteor.memory.feature.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The AI screen uses the app's hand-drawn icon language instead of a Material
 * icon pack: 24dp grid, 1.8dp rounded strokes and a single tint. Keeping the
 * paths local also keeps `:feature-ai` free of a new dependency.
 */
internal enum class AiIconKind {
    Back,
    ChevronDown,
    ChevronRight,
    Plus,
    Close,
    More,
    Sparkle,
    Send,
    ArrowUpRight,
    Paperclip,
    Globe,
    Archive,
    Tune,
    Edit,
    Trash,
    Check,
    User,
    Alert,
}

@Composable
internal fun AiIcon(
    kind: AiIconKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 22.dp,
) {
    Canvas(modifier.size(iconSize)) {
        drawAiIcon(kind, tint)
    }
}

private fun DrawScope.drawAiIcon(kind: AiIconKind, color: Color) {
    val w = size.width
    val h = size.height

    fun p(x: Float, y: Float) = Offset(x * w / 24f, y * h / 24f)
    fun Path.move(x: Float, y: Float) = moveTo(p(x, y).x, p(x, y).y)
    fun Path.line(x: Float, y: Float) = lineTo(p(x, y).x, p(x, y).y)
    fun Path.cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val first = p(x1, y1)
        val second = p(x2, y2)
        val third = p(x3, y3)
        cubicTo(first.x, first.y, second.x, second.y, third.x, third.y)
    }

    val strokeWidth = size.minDimension * (1.8f / 22f)
    val stroke = Stroke(
        width = strokeWidth,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    fun strokePath(block: Path.() -> Unit) {
        drawPath(Path().apply(block), color, style = stroke)
    }

    fun fill(block: Path.() -> Unit) {
        drawPath(Path().apply(block), color)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth, StrokeCap.Round)
    }

    fun circle(centerX: Float, centerY: Float, radius: Float) {
        drawCircle(
            color = color,
            radius = radius * w / 24f,
            center = p(centerX, centerY),
            style = stroke,
        )
    }

    fun dot(centerX: Float, centerY: Float, radius: Float = 1.2f) {
        drawCircle(
            color = color,
            radius = radius * w / 24f,
            center = p(centerX, centerY),
        )
    }

    when (kind) {
        AiIconKind.Back -> strokePath {
            move(15.5f, 5f)
            line(8.5f, 12f)
            line(15.5f, 19f)
        }

        AiIconKind.ChevronDown -> strokePath {
            move(7f, 10f)
            line(12f, 15f)
            line(17f, 10f)
        }

        AiIconKind.ChevronRight -> strokePath {
            move(10f, 7f)
            line(15f, 12f)
            line(10f, 17f)
        }

        AiIconKind.Plus -> {
            line(12f, 5.5f, 12f, 18.5f)
            line(5.5f, 12f, 18.5f, 12f)
        }

        AiIconKind.Close -> {
            line(6.5f, 6.5f, 17.5f, 17.5f)
            line(17.5f, 6.5f, 6.5f, 17.5f)
        }

        AiIconKind.More -> {
            dot(6.5f, 12f, 1.35f)
            dot(12f, 12f, 1.35f)
            dot(17.5f, 12f, 1.35f)
        }

        AiIconKind.Sparkle -> fill {
            move(12f, 2.5f)
            cubic(12.8f, 7.6f, 16.4f, 11.2f, 21.5f, 12f)
            cubic(16.4f, 12.8f, 12.8f, 16.4f, 12f, 21.5f)
            cubic(11.2f, 16.4f, 7.6f, 12.8f, 2.5f, 12f)
            cubic(7.6f, 11.2f, 11.2f, 7.6f, 12f, 2.5f)
            close()
        }

        AiIconKind.Send -> {
            line(12f, 19f, 12f, 5.5f)
            strokePath {
                move(6.5f, 11f)
                line(12f, 5.5f)
                line(17.5f, 11f)
            }
        }

        AiIconKind.ArrowUpRight -> {
            line(6.5f, 17.5f, 17.5f, 6.5f)
            strokePath {
                move(8f, 6.5f)
                line(17.5f, 6.5f)
                line(17.5f, 16f)
            }
        }

        AiIconKind.Paperclip -> strokePath {
            move(16.8f, 6.2f)
            line(8.6f, 14.4f)
            cubic(7.2f, 15.8f, 7.2f, 17.8f, 8.6f, 19.1f)
            cubic(10f, 20.4f, 12.2f, 20.4f, 13.5f, 19.1f)
            line(19.7f, 12.9f)
            cubic(21.4f, 11.2f, 21.4f, 8.3f, 19.7f, 6.6f)
            cubic(18f, 4.9f, 15.1f, 4.9f, 13.4f, 6.6f)
            line(6.4f, 13.6f)
            cubic(4.7f, 15.3f, 4.7f, 18.2f, 6.4f, 19.9f)
        }

        AiIconKind.Globe -> {
            circle(12f, 12f, 8.5f)
            drawOval(
                color = color,
                topLeft = p(7.75f, 3.5f),
                size = Size(8.5f * w / 24f, 17f * h / 24f),
                style = stroke,
            )
            line(3.5f, 12f, 20.5f, 12f)
        }

        AiIconKind.Archive -> {
            strokePath {
                move(12f, 3.5f)
                line(20f, 7.5f)
                line(12f, 11.5f)
                line(4f, 7.5f)
                close()
            }
            strokePath {
                move(4f, 12f)
                line(12f, 16f)
                line(20f, 12f)
            }
            strokePath {
                move(4f, 16.5f)
                line(12f, 20.5f)
                line(20f, 16.5f)
            }
        }

        AiIconKind.Tune -> {
            line(4f, 7f, 20f, 7f)
            line(4f, 12f, 20f, 12f)
            line(4f, 17f, 20f, 17f)
            dot(9f, 7f, 1.8f)
            dot(15f, 12f, 1.8f)
            dot(8f, 17f, 1.8f)
        }

        AiIconKind.Edit -> strokePath {
            move(4f, 20f)
            line(5f, 15f)
            line(15.5f, 4.5f)
            cubic(16.5f, 3.5f, 18f, 3.5f, 19f, 4.5f)
            cubic(20f, 5.5f, 20f, 7f, 19f, 8f)
            line(8.5f, 18.5f)
            close()
        }

        AiIconKind.Trash -> {
            line(5f, 7f, 19f, 7f)
            strokePath {
                move(9.5f, 7f)
                line(9.5f, 5f)
                line(14.5f, 5f)
                line(14.5f, 7f)
            }
            strokePath {
                move(7f, 7f)
                line(8f, 20f)
                line(16f, 20f)
                line(17f, 7f)
            }
            line(10.5f, 11f, 10.5f, 16.5f)
            line(13.5f, 11f, 13.5f, 16.5f)
        }

        AiIconKind.Check -> strokePath {
            move(5f, 12.5f)
            line(10f, 17.5f)
            line(19f, 6.5f)
        }

        AiIconKind.User -> {
            circle(12f, 8.5f, 3.5f)
            strokePath {
                move(4.5f, 20f)
                cubic(5.5f, 15.5f, 8.5f, 14f, 12f, 14f)
                cubic(15.5f, 14f, 18.5f, 15.5f, 19.5f, 20f)
            }
        }

        AiIconKind.Alert -> {
            circle(12f, 12f, 8.5f)
            line(12f, 7.5f, 12f, 13f)
            dot(12f, 16.5f)
        }
    }
}
