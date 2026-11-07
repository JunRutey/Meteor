package com.meteor.memory.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.feature.calendar.CalendarHeader
import com.meteor.memory.ui.theme.LocalAnimationsEnabled

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
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
    )
}

@Composable
private fun HomeTabBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
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
    val animationsEnabled = LocalAnimationsEnabled.current
    val iconOffset by animateDpAsState(
        targetValue = if (selected) (-4).dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "tabIconOffset",
    )
    val appliedOffset = if (animationsEnabled) iconOffset else if (selected) (-4).dp else 0.dp
    val tint = if (selected) MaterialTheme.colorScheme.onSurface else
        MaterialTheme.colorScheme.onSurfaceVariant
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
        Canvas(
            Modifier
                .padding(bottom = 2.dp)
                .size(27.dp),
        ) {
            val offset = appliedOffset.toPx()
            val path = if (star) starPath(size.width, size.height, offset) else notebookPath(
                size.width,
                size.height,
                offset,
            )
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun starPath(width: Float, height: Float, offsetY: Float): Path {
    val centerX = width / 2
    val centerY = height / 2 + offsetY
    val outer = width * 0.43f
    val inner = outer * 0.43f
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

private fun notebookPath(width: Float, height: Float, offsetY: Float): Path {
    val top = height * 0.13f + offsetY
    val bottom = height * 0.87f + offsetY
    return Path().apply {
        moveTo(width * 0.14f, top)
        lineTo(width * 0.5f, top + height * 0.1f)
        lineTo(width * 0.86f, top)
        lineTo(width * 0.86f, bottom)
        lineTo(width * 0.5f, bottom - height * 0.1f)
        lineTo(width * 0.14f, bottom)
        close()
        moveTo(width * 0.5f, top + height * 0.1f)
        lineTo(width * 0.5f, bottom - height * 0.1f)
        moveTo(width * 0.23f, height * 0.4f + offsetY)
        lineTo(width * 0.4f, height * 0.44f + offsetY)
        moveTo(width * 0.6f, height * 0.44f + offsetY)
        lineTo(width * 0.77f, height * 0.4f + offsetY)
    }
}
