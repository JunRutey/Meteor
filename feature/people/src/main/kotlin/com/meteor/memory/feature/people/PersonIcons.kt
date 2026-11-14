package com.meteor.memory.feature.people

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
 * Hand-drawn 24dp icons matching the rest of the app (AI, calendar, search).
 * Keeping them local means `:feature-people` needs no icon dependency and the
 * stroke language stays identical to the other feature screens.
 */
internal enum class PersonIconKind {
    Back,
    Edit,
    More,
    Close,
}

@Composable
internal fun PersonIcon(
    kind: PersonIconKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 22.dp,
) {
    Canvas(modifier.size(iconSize)) {
        drawPersonIcon(kind, tint)
    }
}

private fun DrawScope.drawPersonIcon(kind: PersonIconKind, color: Color) {
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

    val stroke = Stroke(
        width = size.minDimension * (1.8f / 22f),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
    }

    fun dot(x: Float, y: Float, radius: Float = 1.35f) {
        drawCircle(color = color, radius = radius * w / 24f, center = p(x, y))
    }

    fun path(block: Path.() -> Unit) {
        drawPath(Path().apply(block), color, style = stroke)
    }

    when (kind) {
        PersonIconKind.Back -> path {
            move(15.5f, 5f)
            line(8.5f, 12f)
            line(15.5f, 19f)
        }

        PersonIconKind.Edit -> path {
            move(4f, 20f)
            line(5f, 15f)
            line(15.5f, 4.5f)
            cubic(16.5f, 3.5f, 18f, 3.5f, 19f, 4.5f)
            cubic(20f, 5.5f, 20f, 7f, 19f, 8f)
            line(8.5f, 18.5f)
            close()
        }

        PersonIconKind.More -> {
            dot(6.5f, 12f)
            dot(12f, 12f)
            dot(17.5f, 12f)
        }

        PersonIconKind.Close -> {
            line(6.5f, 6.5f, 17.5f, 17.5f)
            line(17.5f, 6.5f, 6.5f, 17.5f)
        }
    }
}
