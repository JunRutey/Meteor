package com.meteor.memory.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.feature.calendar.CalendarHeader

@Composable
fun HomeTabsScreen(
    appName: String,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    lifeContent: @Composable () -> Unit,
    diaryContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabStateHolder = rememberSaveableStateHolder()
    Column(modifier.fillMaxSize()) {
        HomeHeader(
            appName = appName,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            tabStateHolder.SaveableStateProvider(selectedTab) {
                if (selectedTab == 0) lifeContent() else diaryContent()
            }
        }
        HomeTabBar(
            selectedTab = selectedTab,
            onSelect = { selectedTab = it },
        )
    }
}

@Composable
private fun HomeHeader(
    appName: String,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    CalendarHeader(
        appName = appName,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        modifier = Modifier.padding(start = 20.dp, end = 8.dp),
    )
}

/**
 * Bottom navigation, 72dp tall: a 56×28 pill carries the icon, the label sits
 * below it. The selected tab tints both with the slate-blue accent and a soft
 * blue pill, matching the design sheet.
 */
@Composable
private fun HomeTabBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val lifeLabel = stringResource(R.string.home_tab_life)
            val diaryLabel = stringResource(R.string.home_tab_diary)
            HomeTabItem(
                label = lifeLabel,
                selected = selectedTab == 0,
                star = true,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            HomeTabItem(
                label = diaryLabel,
                selected = selectedTab == 1,
                star = false,
                onClick = { onSelect(1) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun HomeTabItem(
    label: String,
    selected: Boolean,
    star: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .padding(bottom = 4.dp)
                .size(width = 56.dp, height = 28.dp)
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val path = if (star) {
                    starPath(size.width, size.height)
                } else {
                    notebookPath(size.width, size.height)
                }
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun starPath(width: Float, height: Float): Path {
    val centerX = width / 2
    val centerY = height / 2
    val outer = width * 0.47f
    val inner = outer * 0.42f
    return Path().apply {
        for (index in 0 until 10) {
            val radius = if (index % 2 == 0) outer else inner
            val angle = Math.toRadians(-90.0 + index * 36.0)
            val x = centerX + kotlin.math.cos(angle).toFloat() * radius
            val y = centerY + kotlin.math.sin(angle).toFloat() * radius
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

private fun notebookPath(width: Float, height: Float): Path {
    val top = height * 0.16f
    val bottom = height * 0.84f
    return Path().apply {
        moveTo(width * 0.5f, top + height * 0.08f)
        cubicTo(
            width * 0.34f, top,
            width * 0.16f, top - height * 0.02f,
            width * 0.08f, top,
        )
        lineTo(width * 0.08f, bottom)
        cubicTo(
            width * 0.16f, bottom - height * 0.02f,
            width * 0.34f, bottom,
            width * 0.5f, bottom + height * 0.08f,
        )
        cubicTo(
            width * 0.66f, bottom,
            width * 0.84f, bottom - height * 0.02f,
            width * 0.92f, bottom,
        )
        lineTo(width * 0.92f, top)
        cubicTo(
            width * 0.84f, top - height * 0.02f,
            width * 0.66f, top,
            width * 0.5f, top + height * 0.08f,
        )
        close()
        moveTo(width * 0.5f, top + height * 0.08f)
        lineTo(width * 0.5f, bottom + height * 0.08f)
    }
}
