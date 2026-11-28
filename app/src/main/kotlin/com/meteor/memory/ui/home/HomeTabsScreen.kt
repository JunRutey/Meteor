package com.meteor.memory.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.feature.calendar.CalendarHeader
import com.meteor.memory.ui.theme.MotionTokens

@Composable
fun HomeTabsScreen(
    appName: String,
    versionName: String,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQuestions: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenAi: (() -> Unit)?,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    lifeContent: @Composable () -> Unit,
    diaryContent: @Composable () -> Unit,
    animationsEnabled: Boolean = true,
    drawerOrder: List<String> = DEFAULT_DRAWER_ORDER,
    drawerVisibleItems: Set<String> = DEFAULT_DRAWER_VISIBLE_ITEMS,
    drawerFullscreen: Boolean = false,
    onDrawerConfigChanged: (order: List<String>, visibleItems: Set<String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val tabStateHolder = rememberSaveableStateHolder()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    fun closeThen(action: () -> Unit) {
        drawerOpen = false
        action()
    }

    val view = LocalView.current
    val density = LocalDensity.current
    // This composable only gets the area inside the root's safe-area padding, so
    // it cannot size the drawer to the window from `maxHeight`. Measure where
    // this box sits in the window instead: the overlay is then placed at the
    // window origin (offset) and sized like the window (requiredSize). Order
    // matters: `offset` must wrap `requiredSize`, otherwise the content is
    // pushed out of its own layout bounds and gets clipped.
    var windowBounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                if (bounds != windowBounds) windowBounds = bounds
            },
    ) {
        // The panel starts where this box starts (below the status bar when it is
        // visible, at the window top when it is hidden) and must end at the real
        // window bottom. So its height is "window height minus the top gap" with
        // no vertical offset: the node is taller than its parent by exactly the
        // navigation-bar gap, and only that bottom part is drawn outside the
        // parent. Content gets the bottom gap back as padding.
        val overlayWidth = with(density) { view.width.toDp() }
        val overlayHeight = with(density) {
            (view.height - windowBounds.top).coerceAtLeast(0f).toDp()
        }
        val overlayOffsetX = with(density) { (-windowBounds.left).toDp() }
        val contentBottomInset = with(density) {
            (view.height - windowBounds.bottom).coerceAtLeast(0f).toDp()
        }

        Column(Modifier.fillMaxSize()) {
            HomeHeader(
                appName = appName,
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                onOpenDrawer = { drawerOpen = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Crossfade instead of a hard swap: the two tabs are the app's main
                // navigation and used to cut instantly. The state holder stays keyed
                // per tab so each side keeps its scroll position.
                Crossfade(
                    targetState = selectedTab,
                    animationSpec = MotionTokens.enter(),
                    label = "home-tab",
                ) { tab ->
                    tabStateHolder.SaveableStateProvider(tab) {
                        if (tab == 0) lifeContent() else diaryContent()
                    }
                }
            }
            HomeTabBar(
                selectedTab = selectedTab,
                onSelect = onSelectTab,
            )
        }

        // 侧边栏（ADR-071）：遮罩与面板分开动画，遮罩只淡入淡出，面板从左侧滑入。
        AnimatedVisibility(
            visible = drawerOpen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = overlayOffsetX)
                .requiredSize(overlayWidth, overlayHeight),
            enter = if (animationsEnabled) {
                fadeIn(MotionTokens.enter())
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                fadeOut(MotionTokens.exit())
            } else {
                ExitTransition.None
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { drawerOpen = false },
                    ),
            )
        }

        AnimatedVisibility(
            visible = drawerOpen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = overlayOffsetX)
                .requiredSize(overlayWidth, overlayHeight),
            enter = if (animationsEnabled) {
                slideInHorizontally(animationSpec = MotionTokens.enter()) { -it } +
                    fadeIn(MotionTokens.enter())
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                slideOutHorizontally(animationSpec = MotionTokens.exit()) { -it } +
                    fadeOut(MotionTokens.exit())
            } else {
                ExitTransition.None
            },
        ) {
            HomeDrawer(
                versionName = versionName,
                onOpenQuestions = { closeThen(onOpenQuestions) },
                onOpenAlbum = { closeThen(onOpenAlbum) },
                onOpenPeople = { closeThen(onOpenPeople) },
                onOpenSearch = { closeThen(onOpenSearch) },
                onOpenSettings = { closeThen(onOpenSettings) },
                onOpenAi = onOpenAi?.let { action -> { closeThen(action) } },
                onClose = { drawerOpen = false },
                drawerOrder = drawerOrder,
                drawerVisibleItems = drawerVisibleItems,
                onDrawerConfigChanged = onDrawerConfigChanged,
                contentBottomInset = contentBottomInset,
                seamlessTop = drawerFullscreen,
            )
        }
    }
}

@Composable
private fun HomeHeader(
    appName: String,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    CalendarHeader(
        appName = appName,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onOpenDrawer = onOpenDrawer,
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
    // Animated so the pill and the icon tint glide between tabs instead of
    // snapping; both read the same spec, so they stay in step.
    val tint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MotionTokens.state(),
        label = "tab-tint",
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = MotionTokens.state(),
        label = "tab-pill",
    )
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
                    color = pillColor,
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
