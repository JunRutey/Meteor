package com.meteor.memory.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.meteor.memory.R
import com.meteor.memory.feature.calendar.R as CalendarR
import com.meteor.memory.ui.theme.MotionTokens
import kotlin.math.roundToInt

/** Every function row is the same height; the drag maths depends on it. */
private val DRAWER_ROW_HEIGHT = 54.dp

/**
 * 首页左侧功能抽屉（ADR-071）：点顶部「Meteor」滑出。
 *
 * 它不替换首页已有的快捷入口，只做一个「功能总入口」：每日一问的总览、
 * 相册、人际关系、AI、搜索、设置都在这里。面板本身不含导航，只把点击
 * 交给宿主的回调；关闭由 [onClose] 与遮罩负责。
 *
 * 侧边栏自己管理可见性与顺序（1.7.6）：长按任意功能可以隐藏它、进入拖动
 * 排序，或打开「管理功能」勾选；设置入口固定在底部，不参与这些操作。
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
    onDrawerConfigChanged: (order: List<String>, visibleItems: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    drawerOrder: List<String> = DEFAULT_DRAWER_ORDER,
    drawerVisibleItems: Set<String> = DEFAULT_DRAWER_VISIBLE_ITEMS,
    /** How far the full-bleed surface escapes the host's safe-area padding. */
    contentTopInset: Dp = 0.dp,
    contentBottomInset: Dp = 0.dp,
    /**
     * True while the status bar is hidden: the panel is already flush with the
     * window top, so a rounded top corner plus a shadow would draw a seam
     * across an otherwise white background. Square it off instead of growing
     * the panel (see the 1.7.6 design note).
     */
    seamlessTop: Boolean = false,
    animationsEnabled: Boolean = true,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val rowHeightPx = with(density) { DRAWER_ROW_HEIGHT.toPx() }

    var order by remember { mutableStateOf(normalizedDrawerOrder(drawerOrder)) }
    var visibleIds by remember { mutableStateOf(normalizedDrawerVisibleItems(drawerVisibleItems)) }
    // DataStore re-emits a fresh AppSettings (and therefore fresh List/Set
    // instances) on every write, including the write this drawer just made.
    // Syncing from the incoming *content* instead of using it as a remember key
    // keeps a mid-drag reorder from being reset by its own save flowing back.
    val incomingOrder = remember(drawerOrder) { normalizedDrawerOrder(drawerOrder) }
    val incomingVisible = remember(drawerVisibleItems) {
        normalizedDrawerVisibleItems(drawerVisibleItems)
    }

    var sortMode by remember { mutableStateOf(false) }
    var menuForId by remember { mutableStateOf<String?>(null) }
    var manageOpen by rememberSaveable { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    // Display slot that was grabbed. The target slot is always derived from
    // this fixed value plus the finger travel, so dragging back and forth
    // cannot accumulate drift. (The gesture helper tracks the finger's grab
    // position internally and reports absolute travel.)
    var grabSlot by remember { mutableStateOf(0) }
    // Order as it was when the drag started: restoring it on cancel keeps a
    // gesture that never completed from silently changing the user's layout.
    var orderBeforeDrag by remember { mutableStateOf(order) }
    val listState = rememberLazyListState()

    LaunchedEffect(incomingOrder, incomingVisible) {
        // While sorting, the drawer is the only editor: ignore settings flowing
        // back so an in-flight write cannot revert a reorder mid-gesture.
        if (!sortMode && draggingId == null) {
            if (incomingOrder != order) order = incomingOrder
            if (incomingVisible != visibleIds) visibleIds = incomingVisible
        }
    }

    // Gesture callbacks outlive the composition that set them up, so they read
    // the newest values through these holders instead of a stale capture.
    val latestOrder by rememberUpdatedState(order)
    val latestVisibleIds by rememberUpdatedState(visibleIds)
    val latestDisplayedIds by rememberUpdatedState(
        displayedDrawerIds(order, visibleIds, onOpenAi != null),
    )

    val aiAvailable = onOpenAi != null
    val allItems = remember(order, aiAvailable) {
        order.mapNotNull(DrawerItem::fromId)
            .filter { item -> item != DrawerItem.AI || aiAvailable }
    }
    val visibleItems = allItems.filter { it.id in visibleIds }

    fun open(item: DrawerItem) {
        when (item) {
            DrawerItem.QUESTIONS -> onOpenQuestions()
            DrawerItem.ALBUM -> onOpenAlbum()
            DrawerItem.PEOPLE -> onOpenPeople()
            DrawerItem.AI -> onOpenAi?.invoke()
            DrawerItem.SEARCH -> onOpenSearch()
        }
    }

    fun applyVisibility(updated: Set<String>) {
        visibleIds = updated
        onDrawerConfigChanged(order, updated)
    }

    /** Leaves sort mode and persists the current order exactly once. */
    fun finishSort() {
        sortMode = false
        draggingId = null
        dragDelta = 0f
        onDrawerConfigChanged(order, visibleIds)
    }

    /**
     * Called when a gesture that never completed is cancelled (system
     * interruption, incoming call, …). Restores the order from before the drag
     * so an unfinished gesture cannot silently re-order the drawer.
     */
    fun cancelDrag() {
        draggingId = null
        dragDelta = 0f
        order = orderBeforeDrag
    }

    /**
     * Maps the finger's absolute travel onto a display slot. Both the target
     * slot and the visual offset are derived from the fixed grab point, so
     * dragging back and forth or skipping multiple slots stays symmetric.
     *
     * Hidden entries are filtered out of the slot maths, so crossing one never
     * makes the row jump; the offset is derived from the absolute target slot
     * rather than an accumulated per-step delta, so reversing direction or
     * re-entering a slot keeps the row glued to the finger.
     */
    fun handleSortDrag(dy: Float) {
        val id = draggingId ?: return
        val orderNow = latestOrder
        val visibleNow = latestVisibleIds
        val displayed = displayedDrawerIds(orderNow, visibleNow, aiAvailable)
        if (displayed.isEmpty()) return

        val target = (grabSlot + (dy / rowHeightPx).roundToInt())
            .coerceIn(displayed.indices)
        val fromSlot = displayed.indexOf(id)
        if (target != fromSlot) {
            order = moveDisplayedDrawerId(
                order = orderNow,
                visibleItems = visibleNow,
                aiAvailable = aiAvailable,
                draggedId = id,
                targetSlot = target,
            ) ?: return
        }
        // The row now lives at [target] (a display slot). Its un-offset screen
        // position is target * rowHeight, but the finger is at
        // grabSlot * rowHeight + dy. The difference keeps the row under the
        // finger whether or not a swap happened this event, no matter how many
        // times the direction changes or how far the finger travelled at once.
        dragDelta = dy - (target - grabSlot) * rowHeightPx
    }

    // Back leaves the reorder mode first, so a gesture can never be lost by
    // closing the whole drawer.
    BackHandler(enabled = sortMode) { finishSort() }

    val shape = RoundedCornerShape(
        topEnd = if (seamlessTop) 0.dp else 24.dp,
        bottomEnd = 24.dp,
    )
    Surface(
        modifier = modifier.width(300.dp).fillMaxHeight(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (seamlessTop) 0.dp else 12.dp,
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
                AnimatedContent(
                    targetState = sortMode,
                    transitionSpec = {
                        if (animationsEnabled) {
                            fadeIn(MotionTokens.enter()) togetherWith fadeOut(MotionTokens.exit())
                        } else {
                            androidx.compose.animation.EnterTransition.None togetherWith
                                androidx.compose.animation.ExitTransition.None
                        }
                    },
                    label = "drawer-header-action",
                ) { sorting ->
                    if (sorting) {
                        DoneButton(onClick = { finishSort() })
                    } else {
                        CloseButton(onClick = onClose)
                    }
                }
            }
            Crossfade(
                targetState = sortMode,
                animationSpec = MotionTokens.enter(),
                label = "drawer-subtitle",
            ) { sorting ->
                Text(
                    text = stringResource(
                        if (sorting) R.string.home_drawer_sort_hint else R.string.home_drawer_tagline,
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 22.dp)
                    // The gesture sits on the list, not on a row: LazyColumn
                    // disposes and re-creates item compositions when their
                    // index changes, which cancelled the old per-row drag and
                    // made the order jump. A container-level gesture survives
                    // every swap.
                    .then(
                        if (sortMode) {
                            Modifier.pointerInput(Unit) {
                                detectDrawerSortGestures(
                                    listState = listState,
                                    itemAtListIndex = { index ->
                                        latestDisplayedIds.getOrNull(index - 1)
                                    },
                                    onDragStarted = { id, startIndex ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggingId = id
                                        grabSlot = startIndex
                                        dragDelta = 0f
                                        orderBeforeDrag = latestOrder
                                    },
                                    onDragMoved = { dy -> handleSortDrag(dy) },
                                    onDragFinished = {
                                        // Commit only on a completed gesture; a
                                        // cancelled one restores orderBeforeDrag.
                                        draggingId = null
                                        dragDelta = 0f
                                        onDrawerConfigChanged(latestOrder, latestVisibleIds)
                                    },
                                    onDragCancelled = { cancelDrag() },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
                // While sorting the finger belongs to the drag gesture, not to
                // the list: a five-row drawer never needs scrolling anyway.
                userScrollEnabled = !sortMode,
            ) {
                item(key = "section") {
                    Text(
                        text = stringResource(R.string.home_drawer_section),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(6.dp))
                }

                itemsIndexed(visibleItems, key = { _, item -> item.id }) { _, item ->
                    val isDragging = draggingId == item.id

                    Box(
                        modifier = Modifier
                            // Freeze the placement animation for the row under the
                            // finger: it is moved by its own offset, and letting
                            // the list animate the slot as well made it hop.
                            .animateItem(
                                placementSpec = if (isDragging) null else MotionTokens.item(),
                            )
                            .zIndex(if (isDragging) 1f else 0f)
                            .then(
                                if (isDragging) {
                                    Modifier
                                        .offset { IntOffset(0, dragDelta.roundToInt()) }
                                        .graphicsLayer { scaleX = 1.02f; scaleY = 1.02f }
                                        .shadow(8.dp, RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        DrawerRow(
                            label = stringResource(item.labelRes),
                            hint = stringResource(item.hintRes),
                            onClick = { if (!sortMode) open(item) },
                            onLongClick = if (sortMode) {
                                null
                            } else {
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuForId = item.id
                                }
                            },
                            elevated = isDragging,
                            trailing = if (sortMode) {
                                { DragHandleGlyph() }
                            } else {
                                null
                            },
                        ) { DrawerGlyph(item) }

                        DrawerItemMenu(
                            expanded = menuForId == item.id,
                            onDismiss = { menuForId = null },
                            onHide = {
                                menuForId = null
                                applyVisibility(visibleIds - item.id)
                            },
                            onSort = {
                                menuForId = null
                                if (!sortMode) sortMode = true
                            },
                            onManage = {
                                menuForId = null
                                manageOpen = true
                            },
                        )
                    }
                }

                if (visibleItems.isEmpty()) {
                    item(key = "empty") {
                        EmptyDrawerState(
                            onManage = { manageOpen = true },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
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

    if (manageOpen) {
        DrawerManageDialog(
            items = allItems,
            selected = visibleIds,
            animationsEnabled = animationsEnabled,
            onCancel = { manageOpen = false },
            onConfirm = { updated ->
                manageOpen = false
                applyVisibility(updated)
            },
        )
    }
}

/**
 * Container-level drag for the reorder mode.
 *
 * The gesture has to live on the list, not on a row: LazyColumn disposes item
 * compositions when their index changes, so a row-owned `pointerInput` was
 * cancelled on the first swap (which is why only one direction ever worked and
 * the order jumped). This reports the finger's absolute travel from the grab
 * point, and the caller turns that into a target slot — symmetric for the up and
 * down direction.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDrawerSortGestures(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemAtListIndex: (Int) -> String?,
    onDragStarted: (id: String, startIndex: Int) -> Unit,
    onDragMoved: (dy: Float) -> Unit,
    onDragFinished: () -> Unit,
    onDragCancelled: () -> Unit,
) {
    var grabY = 0f
    detectDragGestures(
        onDragStart = { start ->
            // Resolve the hit item and its display slot from the same layout
            // snapshot. `itemAtListIndex` reads state that a concurrent
            // recomposition may have changed since the hit test.
            val displayedIds = mutableListOf<String>()
            var index = 0
            while (true) {
                val id = itemAtListIndex(index) ?: break
                displayedIds += id
                index++
            }
            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                start.y >= info.offset && start.y < info.offset + info.size
            } ?: return@detectDragGestures
            val startIndex = hit.index - 1
            val id = displayedIds.getOrNull(startIndex) ?: return@detectDragGestures
            grabY = start.y
            onDragStarted(id, startIndex)
        },
        onDrag = { change, _ ->
            change.consume()
            onDragMoved(change.position.y - grabY)
        },
        onDragEnd = { onDragFinished() },
        onDragCancel = { onDragCancelled() },
    )
}

@Composable
private fun DrawerItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onSort: () -> Unit,
    onManage: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(12.dp, 0.dp),
        modifier = Modifier.widthIn(min = 172.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        DrawerMenuEntry(
            labelRes = R.string.home_drawer_action_hide,
            glyph = { HideGlyph() },
            onClick = onHide,
        )
        DrawerMenuEntry(
            labelRes = R.string.home_drawer_action_sort,
            glyph = { SortGlyph() },
            onClick = onSort,
        )
        DrawerMenuEntry(
            labelRes = R.string.home_drawer_action_manage,
            glyph = { ManageGlyph() },
            onClick = onManage,
        )
    }
}

@Composable
private fun DrawerMenuEntry(
    labelRes: Int,
    glyph: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { glyph() }
        Text(
            text = stringResource(labelRes),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyDrawerState(
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_drawer_empty),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onManage,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.home_drawer_action_manage),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 「管理功能」勾选面板：全部功能都在，勾上的才显示；确定后才落盘，取消保持
 * 原样。用应用自己的圆角与配色，不跟随平台复选框样式之外的东西。
 */
@Composable
private fun DrawerManageDialog(
    items: List<DrawerItem>,
    selected: Set<String>,
    animationsEnabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var draft by remember { mutableStateOf(selected) }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = if (animationsEnabled) {
                    fadeIn(MotionTokens.enter()) +
                        scaleIn(initialScale = 0.94f, animationSpec = MotionTokens.enter())
                } else {
                    androidx.compose.animation.EnterTransition.None
                },
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
                        Text(
                            text = stringResource(R.string.home_drawer_manage_title),
                            modifier = Modifier.padding(horizontal = 20.dp),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.home_drawer_manage_note),
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier
                                .heightIn(max = 336.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 12.dp),
                        ) {
                            items.forEach { item ->
                                val checked = item.id in draft
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = checked,
                                            role = Role.Checkbox,
                                            onValueChange = { nowChecked ->
                                                draft = if (nowChecked) {
                                                    draft + item.id
                                                } else {
                                                    draft - item.id
                                                }
                                            },
                                        )
                                        .heightIn(min = 48.dp)
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ManageCheckMark(checked = checked)
                                    Text(
                                        text = stringResource(item.labelRes),
                                        modifier = Modifier.padding(start = 14.dp),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onCancel) {
                                Text(
                                    text = stringResource(R.string.home_drawer_manage_cancel),
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onConfirm(draft) }) {
                                Text(
                                    text = stringResource(R.string.home_drawer_manage_confirm),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageCheckMark(checked: Boolean) {
    val fill by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        animationSpec = MotionTokens.state(),
        label = "manage-check-fill",
    )
    val outline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(22.dp)) {
        val radius = CornerRadius(6.dp.toPx())
        val mark = androidx.compose.ui.geometry.Size(size.width, size.height)
        drawRoundRect(color = fill, size = mark, cornerRadius = radius)
        drawRoundRect(
            color = if (checked) fill else outline,
            size = mark,
            cornerRadius = radius,
            style = Stroke(width = 1.6.dp.toPx()),
        )
        if (checked) {
            val unit = size.width / 22f
            val tick = Path().apply {
                moveTo(5.5f * unit, 11.5f * unit)
                lineTo(9.3f * unit, 15.3f * unit)
                lineTo(16.5f * unit, 7.5f * unit)
            }
            drawPath(
                path = tick,
                color = Color.White,
                style = Stroke(
                    width = 2.1f * unit,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    hint: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    elevated: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DRAWER_ROW_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .then(clickModifier)
            .background(
                if (elevated) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 8.dp),
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
        trailing?.invoke()
    }
}

@Composable
private fun DragHandleGlyph() {
    val label = stringResource(R.string.home_drawer_sort_handle)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = label },
    ) {
        val stroke = 1.6.dp.toPx()
        val unit = size.width / 24f
        listOf(8f, 12f, 16f).forEach { y ->
            drawLine(
                color = tint,
                start = Offset(6f * unit, y * unit),
                end = Offset(18f * unit, y * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    val label = stringResource(R.string.home_drawer_sort_done)
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2f * (size.minDimension / 18f)
            val path = Path().apply {
                moveTo(size.width * 0.12f, size.height * 0.54f)
                lineTo(size.width * 0.4f, size.height * 0.82f)
                lineTo(size.width * 0.9f, size.height * 0.2f)
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
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

@Composable
private fun HideGlyph() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val unit = size.width / 20f
        val eye = Path().apply {
            moveTo(1.5f * unit, 10f * unit)
            cubicTo(5f * unit, 3.6f * unit, 15f * unit, 3.6f * unit, 18.5f * unit, 10f * unit)
            cubicTo(15f * unit, 16.4f * unit, 5f * unit, 16.4f * unit, 1.5f * unit, 10f * unit)
            close()
        }
        drawPath(eye, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(tint, radius = 2.4f * unit, center = Offset(10f * unit, 10f * unit), style = Stroke(width = stroke))
        drawLine(
            color = tint,
            start = Offset(3.5f * unit, 17f * unit),
            end = Offset(16.5f * unit, 3f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SortGlyph() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val unit = size.width / 20f
        listOf(5f, 10f, 15f).forEachIndexed { index, y ->
            val end = if (index == 1) 16f else 12f
            drawLine(
                color = tint,
                start = Offset(3f * unit, y * unit),
                end = Offset(end * unit, y * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ManageGlyph() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val unit = size.width / 20f
        fun line(x1: Float, x2: Float, y: Float) = drawLine(
            color = tint,
            start = Offset(x1 * unit, y * unit),
            end = Offset(x2 * unit, y * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        line(2f, 12f, 6f)
        line(15f, 18f, 6f)
        line(2f, 5f, 14f)
        line(8f, 18f, 14f)
        drawCircle(tint, radius = 1.9f * unit, center = Offset(13.5f * unit, 6f * unit), style = Stroke(width = stroke))
        drawCircle(tint, radius = 1.9f * unit, center = Offset(6.5f * unit, 14f * unit), style = Stroke(width = stroke))
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
