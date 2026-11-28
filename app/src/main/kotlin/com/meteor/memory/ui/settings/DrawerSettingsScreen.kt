package com.meteor.memory.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.ui.home.DrawerItem
import com.meteor.memory.ui.home.normalizedDrawerOrder
import com.meteor.memory.ui.home.normalizedDrawerVisibleItems

/**
 * 自定义侧边栏 (1.7.4).
 *
 * All features stay in one ordered list, including hidden ones: toggling an
 * item off keeps its position, so turning it back on restores the person's
 * arrangement. The pinned 设置 footer is deliberately not in this list.
 */
@Composable
fun DrawerSettingsScreen(
    settings: AppSettings,
    onConfigChanged: (order: List<String>, visibleItems: Set<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val order = normalizedDrawerOrder(settings.drawerOrder)
    val visibleItems = normalizedDrawerVisibleItems(settings.drawerVisibleItems)

    Column(modifier = modifier.fillMaxSize()) {
        DrawerSettingsHeader(onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.drawer_settings_note),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(20.dp),
                    ),
            ) {
                order.forEachIndexed { index, id ->
                    val item = DrawerItem.fromId(id) ?: return@forEachIndexed
                    if (index > 0) DrawerHairline()
                    DrawerConfigRow(
                        item = item,
                        visible = item.id in visibleItems,
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onVisibleChange = { visible ->
                            val updated = if (visible) {
                                visibleItems + item.id
                            } else {
                                visibleItems - item.id
                            }
                            onConfigChanged(order, updated)
                        },
                        onMoveUp = {
                            if (index > 0) onConfigChanged(order.moved(index, index - 1), visibleItems)
                        },
                        onMoveDown = {
                            if (index < order.lastIndex) {
                                onConfigChanged(order.moved(index, index + 1), visibleItems)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerConfigRow(
    item: DrawerItem,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)) {
            Text(
                text = stringResource(item.labelRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(item.hintRes),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ReorderButton(
            symbol = "↑",
            description = stringResource(R.string.drawer_settings_move_up),
            enabled = canMoveUp,
            onClick = onMoveUp,
        )
        ReorderButton(
            symbol = "↓",
            description = stringResource(R.string.drawer_settings_move_down),
            enabled = canMoveDown,
            onClick = onMoveDown,
        )
        Switch(checked = visible, onCheckedChange = onVisibleChange)
    }
}

@Composable
private fun ReorderButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(38.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text = symbol, fontSize = 18.sp)
    }
}

@Composable
private fun DrawerSettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 1.8.dp.toPx()
                val unit = size.width / 24f
                val path = Path().apply {
                    moveTo(15f * unit, 6f * unit)
                    lineTo(9f * unit, 12f * unit)
                    lineTo(15f * unit, 18f * unit)
                }
                drawPath(
                    path = path,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Text(
            text = stringResource(R.string.drawer_settings_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DrawerHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

private fun List<String>.moved(from: Int, to: Int): List<String> {
    val copy = toMutableList()
    val item = copy.removeAt(from)
    copy.add(to, item)
    return copy
}
