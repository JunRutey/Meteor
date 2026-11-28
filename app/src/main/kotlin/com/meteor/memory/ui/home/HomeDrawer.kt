package com.meteor.memory.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.feature.calendar.R as CalendarR

/**
 * 首页左侧功能抽屉（ADR-071）：点顶部「Meteor」滑出。
 *
 * 它不替换首页已有的快捷入口，只做一个「功能总入口」：每日一问的总览、
 * 相册、人际关系、AI、搜索、设置都在这里。面板本身不含导航，只把点击
 * 交给宿主的回调；关闭由 [onClose] 与遮罩负责。
 *
 * 视觉与首页卡片一致：白色面板、右侧 24dp 圆角、40dp 浅色图标方块。
 */
@Composable
internal fun HomeDrawer(
    versionName: String,
    onOpenQuestions: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAi: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    drawerOrder: List<String> = DEFAULT_DRAWER_ORDER,
    drawerVisibleItems: Set<String> = DEFAULT_DRAWER_VISIBLE_ITEMS,
    /**
     * How far the surface was pushed past the host's safe-area padding. The
     * panel paints the whole window, but its content must stay clear of the
     * status bar / cutout and the gesture bar, so those amounts come back as
     * extra padding here.
     */
    contentTopInset: Dp = 0.dp,
    contentBottomInset: Dp = 0.dp,
) {
    // Settings is not in this list on purpose: it is pinned below the scroll
    // area and never moves, even when the person reorders or hides functions.
    val visibleItems = normalizedDrawerOrder(drawerOrder)
        .mapNotNull(DrawerItem::fromId)
        .filter { item ->
            item.id in drawerVisibleItems &&
                (item != DrawerItem.AI || onOpenAi != null)
        }
    val shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
    Surface(
        modifier = modifier.width(300.dp).fillMaxHeight(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 20.dp,
                    end = 16.dp,
                    top = 16.dp + contentTopInset,
                    bottom = 16.dp + contentBottomInset,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                CloseButton(onClick = onClose)
            }
            Text(
                text = stringResource(R.string.home_drawer_tagline),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 22.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_drawer_section),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(6.dp))

                visibleItems.forEach { item ->
                    DrawerRow(
                        label = stringResource(item.labelRes),
                        hint = stringResource(item.hintRes),
                        onClick = {
                            when (item) {
                                DrawerItem.QUESTIONS -> onOpenQuestions()
                                DrawerItem.ALBUM -> onOpenAlbum()
                                DrawerItem.PEOPLE -> onOpenPeople()
                                DrawerItem.AI -> onOpenAi?.invoke()
                                DrawerItem.SEARCH -> onOpenSearch()
                            }
                        },
                    ) { DrawerGlyph(item) }
                }
                if (visibleItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_drawer_empty),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Pinned footer: settings never scrolls with the list above, so its
            // position cannot change when items are added, hidden or reordered.
            Spacer(Modifier.size(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.size(12.dp))
            DrawerRow(
                label = stringResource(CalendarR.string.calendar_open_settings),
                hint = stringResource(R.string.home_drawer_settings_hint),
                onClick = onOpenSettings,
            ) { SettingsGlyph() }

            Text(
                text = stringResource(R.string.home_drawer_version, versionName),
                modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    hint: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp)) { icon() }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    val label = stringResource(R.string.home_drawer_close)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val unit = size.minDimension / 16f
            val stroke = 1.6f * unit
            drawLine(
                color = tint,
                start = Offset(3f * unit, 3f * unit),
                end = Offset(13f * unit, 13f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(13f * unit, 3f * unit),
                end = Offset(3f * unit, 13f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

// --- 图标：全部用 Canvas 画，避免给 :app 引入 material-icons ----------------

@Composable
private fun DrawerGlyph(item: DrawerItem) {
    when (item) {
        DrawerItem.QUESTIONS -> QuestionGlyph()
        DrawerItem.ALBUM -> AlbumGlyph()
        DrawerItem.PEOPLE -> PeopleGlyph()
        DrawerItem.AI -> SparkGlyph()
        DrawerItem.SEARCH -> SearchGlyph()
    }
}

@Composable
private fun QuestionGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        val bubble = Path().apply {
            moveTo(7f * unit, 3.5f * unit)
            lineTo(17f * unit, 3.5f * unit)
            cubicTo(
                19f * unit, 3.5f * unit,
                20.5f * unit, 5f * unit,
                20.5f * unit, 7f * unit,
            )
            lineTo(20.5f * unit, 13f * unit)
            cubicTo(
                20.5f * unit, 15f * unit,
                19f * unit, 16.5f * unit,
                17f * unit, 16.5f * unit,
            )
            lineTo(11f * unit, 16.5f * unit)
            lineTo(7.5f * unit, 20f * unit)
            lineTo(7.5f * unit, 16.5f * unit)
            lineTo(7f * unit, 16.5f * unit)
            cubicTo(
                5f * unit, 16.5f * unit,
                3.5f * unit, 15f * unit,
                3.5f * unit, 13f * unit,
            )
            lineTo(3.5f * unit, 7f * unit)
            cubicTo(
                3.5f * unit, 5f * unit,
                5f * unit, 3.5f * unit,
                7f * unit, 3.5f * unit,
            )
            close()
        }
        drawPath(
            path = bubble,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val hook = Path().apply {
            moveTo(9.2f * unit, 8f * unit)
            cubicTo(
                9.2f * unit, 6.4f * unit,
                10.4f * unit, 5.6f * unit,
                12f * unit, 5.6f * unit,
            )
            cubicTo(
                13.6f * unit, 5.6f * unit,
                14.8f * unit, 6.5f * unit,
                14.8f * unit, 8f * unit,
            )
            cubicTo(
                14.8f * unit, 10f * unit,
                12f * unit, 10.2f * unit,
                12f * unit, 12.4f * unit,
            )
        }
        drawPath(path = hook, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(color = tint, radius = 0.95f * unit, center = Offset(12f * unit, 14.4f * unit))
    }
}

@Composable
private fun AlbumGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(7.5f * unit, 3f * unit),
            size = Size(13f * unit, 12.5f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.5f * unit, 8f * unit),
            size = Size(13f * unit, 12.5f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = tint,
            radius = 1.3f * unit,
            center = Offset(7.8f * unit, 12.6f * unit),
            style = Stroke(width = stroke),
        )
        val hills = Path().apply {
            moveTo(5f * unit, 18.6f * unit)
            lineTo(8.4f * unit, 15.4f * unit)
            lineTo(10.6f * unit, 17.6f * unit)
            lineTo(12.4f * unit, 15.8f * unit)
            lineTo(15f * unit, 18.6f * unit)
        }
        drawPath(
            path = hills,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun PeopleGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawCircle(
            color = tint,
            radius = 3f * unit,
            center = Offset(9f * unit, 9f * unit),
            style = Stroke(width = stroke),
        )
        val shoulder = Path().apply {
            moveTo(3.5f * unit, 19f * unit)
            cubicTo(
                4.1f * unit, 16f * unit,
                6.5f * unit, 14.5f * unit,
                9f * unit, 14.5f * unit,
            )
            cubicTo(
                11.5f * unit, 14.5f * unit,
                13.9f * unit, 16f * unit,
                14.5f * unit, 19f * unit,
            )
        }
        drawPath(path = shoulder, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(
            color = tint,
            radius = 2.4f * unit,
            center = Offset(17f * unit, 10f * unit),
            style = Stroke(width = stroke),
        )
        val second = Path().apply {
            moveTo(16f * unit, 14.8f * unit)
            cubicTo(
                18.4f * unit, 14.5f * unit,
                20.2f * unit, 15.8f * unit,
                20.8f * unit, 18.5f * unit,
            )
        }
        drawPath(path = second, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun SparkGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val big = size.minDimension / 2f
        val small = big * 0.26f
        val star = Path().apply {
            moveTo(cx, cy - big)
            lineTo(cx + small, cy - small)
            lineTo(cx + big, cy)
            lineTo(cx + small, cy + small)
            lineTo(cx, cy + big)
            lineTo(cx - small, cy + small)
            lineTo(cx - big, cy)
            lineTo(cx - small, cy - small)
            close()
        }
        drawPath(star, color = tint)
    }
}

@Composable
private fun SearchGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawCircle(
            color = tint,
            radius = 6.5f * unit,
            center = Offset(11f * unit, 11f * unit),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(16f * unit, 16f * unit),
            end = Offset(20.5f * unit, 20.5f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SettingsGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        fun line(x1: Float, x2: Float, y: Float) = drawLine(
            color = tint,
            start = Offset(x1 * unit, y * unit),
            end = Offset(x2 * unit, y * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        line(4f, 14f, 7f)
        line(18f, 20f, 7f)
        line(4f, 6f, 17f)
        line(10f, 20f, 17f)
        drawCircle(
            color = tint,
            radius = 2f * unit,
            center = Offset(16f * unit, 7f * unit),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = tint,
            radius = 2f * unit,
            center = Offset(8f * unit, 17f * unit),
            style = Stroke(width = stroke),
        )
    }
}
