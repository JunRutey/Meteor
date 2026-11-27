package com.meteor.memory.feature.question

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 每日一问（ADR-069）三个页面共用的页头与小组件。
 *
 * 页头只有「返回 / 标题 / 右侧动作」三件事，提问、浏览、回答三页因此长得一样：
 * 打开任何一个页面，第一眼都能看到自己在哪、能往哪走。
 */

/** 页面淡入 / 淡出时长，与 `:app` 的 `MotionTokens` 同值（200ms 进、150ms 出）。 */
private const val ENTER_MS = 200
private const val EXIT_MS = 150

@Composable
internal fun QuestionScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时左侧显示文字按钮（回答编辑页的「取消」），否则显示返回箭头。 */
    backLabel: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (backLabel == null) {
            QuestionBackButton(onClick = onBack)
        } else {
            TextButton(onClick = onBack) {
                Text(
                    text = backLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        trailing?.invoke(this)
    }
}

/**
 * 返回箭头：48dp 触达区，图标按 24 单位画，和日历 / 相册页的线条语言一致。
 *
 * 画出来而不是引 Material 图标：`:feature-question` 不依赖 `material-icons`。
 */
@Composable
internal fun QuestionBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.question_back)
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val unit = size.minDimension / 24f
            val stroke = 1.8f * unit
            drawLine(
                color = tint,
                start = Offset(15f * unit, 5f * unit),
                end = Offset(8f * unit, 12f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(8f * unit, 12f * unit),
                end = Offset(15f * unit, 19f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 列表页右上角的「＋」：40dp 圆形主色浅底，点击进入提问页。 */
@Composable
internal fun QuestionPlusButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.question_new_action)
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val arm = size.width * 0.3f
            drawLine(
                color = tint,
                start = Offset(center.x - arm, center.y),
                end = Offset(center.x + arm, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(center.x, center.y - arm),
                end = Offset(center.x, center.y + arm),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 「⋯」溢出菜单：列表卡片与详情页头共用，只放「删除问题」。
 *
 * 删除不可恢复，所以收进菜单而不是常驻在正文旁边；点开菜单才会看到它，
 * 少了一次误触。
 */
@Composable
internal fun QuestionOverflowMenu(
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.question_more)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { expanded = true }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val radius = 1.7.dp.toPx()
                val x = size.width / 2f
                listOf(0.24f, 0.5f, 0.76f).forEach { fraction ->
                    drawCircle(color = tint, radius = radius, center = Offset(x, size.height * fraction))
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.question_delete)) },
                onClick = {
                    expanded = false
                    onRequestDelete()
                },
            )
        }
    }
}

/** 「待回答 / 已回答」状态小胶囊。 */
@Composable
internal fun QuestionStatusPill(answered: Boolean) {
    val container = if (answered) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (answered) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = stringResource(
                if (answered) R.string.question_answered_badge else R.string.question_open_badge,
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}

/** 淡入 / 淡出容器；关掉动效时直接出现，不做任何过渡。 */
@Composable
internal fun QuestionFade(
    visible: Boolean,
    animationsEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (animationsEnabled) {
            fadeIn(tween(durationMillis = ENTER_MS, easing = FastOutSlowInEasing))
        } else {
            EnterTransition.None
        },
        exit = if (animationsEnabled) {
            fadeOut(tween(durationMillis = EXIT_MS, easing = FastOutLinearInEasing))
        } else {
            ExitTransition.None
        },
    ) {
        content()
    }
}
