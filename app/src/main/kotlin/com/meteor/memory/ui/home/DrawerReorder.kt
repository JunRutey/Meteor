package com.meteor.memory.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import com.meteor.memory.ui.theme.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// --- 一次拖动的几何 ----------------------------------------------------------

/**
 * 一次拖动手势的固定坐标系：在哪个槽位、哪个像素位置起手，一行多高。
 *
 * 之后每个问题——指针现在压在第几槽、被拖行要偏移多少——都只用这几个数来回答。
 * 因为答案只取决于指针离起手点多远、与已经换过几次位无关，手势不会累积漂移：
 * 快速跨多行、来回反向、跨过隐藏项之后都依然精确。
 */
class DrawerDragGeometry(
    val anchorSlot: Int,
    val anchorPointerY: Float,
    val rowHeightPx: Float,
    val slotCount: Int,
) {
    /** 指针当前压住的显示槽位，夹在真实存在的行里。 */
    fun slotAt(pointerY: Float): Int {
        val last = slotCount - 1
        if (last <= 0) return 0
        val steps = ((pointerY - anchorPointerY) / rowHeightPx).roundToInt()
        return (anchorSlot + steps).coerceIn(0, last)
    }

    /**
     * 让被拖行留在手指下的垂直偏移。
     *
     * 列表内部时，被拖行的屏幕位置恰好是 `anchorSlot * 行高 + 手指位移`：一条
     * 关于手指位移的直线。交换只改变「哪个槽位承载这个偏移」，不改变屏幕位置，
     * 所以越过行与行的中点时位置是连续的。偏移被限制在半行以内，拖到列表两端
     * 之外时行停在最后一槽外半行，而不是跟着手指飞出去。
     */
    fun offsetAt(pointerY: Float, slot: Int): Float {
        val half = rowHeightPx / 2f
        val wantedTop = anchorSlot * rowHeightPx + (pointerY - anchorPointerY)
        return (wantedTop - slot * rowHeightPx).coerceIn(-half, half)
    }
}

/** 一次进行中的拖动；[orderAtStart] 是手势被取消时要回滚到的顺序。 */
data class DrawerDragSession(
    val id: String,
    val geometry: DrawerDragGeometry,
    /** 被拖行当前所在的显示槽位。 */
    val slot: Int,
    val orderAtStart: List<String>,
    val visibleAtStart: Set<String>,
    /** 手指已抬起、只剩回弹动画在跑。 */
    val released: Boolean = false,
)

// --- 抽屉排序状态 ------------------------------------------------------------

/**
 * 抽屉排序模式的全部状态：持久顺序、可见项、是否处于排序模式，以及最多一个拖动。
 *
 * 它是面板的**编辑态**（要活过重组，不必活过进程死亡），所以直接用 Compose 状态
 * 而不是 ViewModel。每次真实变化通过 [onPersist] 回写一次，重复的写入被吃掉。
 */
@Stable
internal class DrawerReorderState(
    initialOrder: List<String>,
    initialVisibleItems: Set<String>,
    private val aiAvailable: Boolean,
    private val scope: CoroutineScope,
    private val onPersist: (order: List<String>, visibleItems: Set<String>) -> Unit,
) {
    var order by mutableStateOf(normalizedDrawerOrder(initialOrder))
        private set

    var visibleItems by mutableStateOf(normalizedDrawerVisibleItems(initialVisibleItems))
        private set

    /** 是否处于排序模式（头部显示对勾、每行出现把手）。 */
    var sorting by mutableStateOf(false)
        private set

    /** 正在拖动（或正在回弹）的那一行，没有则为 null。 */
    var drag by mutableStateOf<DrawerDragSession?>(null)
        private set

    /**
     * 被拖行当前的像素偏移。只有被拖的那一行在布局阶段读它，所以手指移动不会
     * 触发重组，只让那一行重新摆放。
     */
    var dragOffsetPx by mutableFloatStateOf(0f)
        private set

    private var persistedOrder = this.order
    private var persistedVisible = this.visibleItems

    // Set when this drawer has written a value it has not seen come back yet.
    // Until then, any incoming value predates the write and adopting it would
    // throw the rows back to where they were before the last drop.
    private var dirty = false
    private var settleJob: Job? = null

    /** 手势或排序模式进行中：此时外部回写是旧数据，不能覆盖编辑结果。 */
    val busy: Boolean get() = sorting || drag != null

    /** 当前渲染成行的 id，按显示顺序。 */
    fun displayedIds(): List<String> = displayedDrawerIds(order, visibleItems, aiAvailable)

    fun startSorting() {
        if (sorting) return
        dropDrag()
        sorting = true
    }

    /** 离开排序模式；顺序在每次拖动松手时已经落盘，这里只补一次兜底。 */
    fun finishSorting() {
        if (!sorting) return
        // A drag that is still in flight has already been applied to [order] (the
        // swap happens live), so leaving the mode keeps it rather than rolling
        // back; dropping the session only removes the transient translation.
        drag = null
        dragOffsetPx = 0f
        settleJob?.cancel()
        settleJob = null
        sorting = false
        persistIfChanged()
    }

    fun hide(id: String) = setVisible(visibleItems - id)

    fun setVisible(next: Set<String>) {
        val normalized = normalizedDrawerVisibleItems(next)
        if (normalized == visibleItems) return
        visibleItems = normalized
        persistIfChanged()
    }

    /**
     * Adopt a value that arrived from the settings flow — another editor, a
     * restore, or the echo of this drawer's own write.
     *
     * Comparison is by content, never by instance: DataStore builds a fresh
     * `AppSettings` (and fresh List/Set) on every write, including the one this
     * drawer just made, so keying off identity would reset a drag with its own
     * echo. Values that arrive while an edit is running, or while a write of
     * this drawer is still in flight ([dirty]), are older than what is on
     * screen and are dropped.
     */
    fun applyPersisted(rawOrder: List<String>, rawVisibleItems: Set<String>) {
        val nextOrder = normalizedDrawerOrder(rawOrder)
        val nextVisible = normalizedDrawerVisibleItems(rawVisibleItems)
        if (nextOrder == order && nextVisible == visibleItems) {
            // The echo of this drawer's own write (or nothing new): settled.
            dirty = false
            return
        }
        if (busy || dirty) return
        order = nextOrder
        visibleItems = nextVisible
        persistedOrder = nextOrder
        persistedVisible = nextVisible
    }

    /**
     * 起手。命中位置来自列表自己的布局信息，[slot] 必须确实停着 [id]，否则不
     * 开始拖动——命中与状态对不上时宁可不拖，也不抓错行。返回是否真的开始了
     * 一次拖动。
     */
    fun beginDrag(id: String, slot: Int, pointerY: Float, rowHeightPx: Float): Boolean {
        if (!sorting) return false
        val current = drag
        if (current != null && !current.released) return false
        dropDrag()
        val shown = displayedIds()
        if (slot !in shown.indices || shown[slot] != id) return false
        drag = DrawerDragSession(
            id = id,
            geometry = DrawerDragGeometry(slot, pointerY, rowHeightPx, shown.size),
            slot = slot,
            orderAtStart = order,
            visibleAtStart = visibleItems,
        )
        return true
    }

    fun dragTo(pointerY: Float) {
        val session = drag ?: return
        if (session.released) return
        val target = session.geometry.slotAt(pointerY)
        var slot = session.slot
        if (target != slot) {
            val moved = movedDrawerIdToSlot(order, visibleItems, aiAvailable, session.id, target)
            // A null result means the request is already stale (the row is not
            // where the slot maths thinks it is). Re-anchor the session on the
            // real slot instead of leaving the drag pinned to a phantom one.
            slot = if (moved != null) {
                order = moved
                target
            } else {
                displayedIds().indexOf(session.id).coerceAtLeast(0)
            }
            drag = session.copy(slot = slot)
        }
        // Derived after the swap and from the same slot, so the frame that swaps
        // keeps the row exactly under the finger. Only the geometry decides this,
        // never how many swaps have happened, so the drag cannot drift.
        dragOffsetPx = session.geometry.offsetAt(pointerY, slot)
    }

    /**
     * 手指抬起。顺序立刻落盘，剩下的零头偏移动画回零，让行滑进槽位而不是
     * 直接跳过去——这是旧实现最后一处肉眼可见的跳动。
     */
    fun releaseDrag(animationsEnabled: Boolean) {
        val session = drag ?: return
        if (session.released) return
        val residual = dragOffsetPx
        drag = session.copy(released = true)
        persistIfChanged()
        settleJob?.cancel()
        if (!animationsEnabled || residual == 0f) {
            dropDrag()
            return
        }
        settleJob = scope.launch {
            animate(residual, 0f, animationSpec = MotionTokens.state()) { value, _ ->
                dragOffsetPx = value
            }
            dragOffsetPx = 0f
            drag = null
            settleJob = null
        }
    }

    /** 手势没走完就被系统收走：顺序回滚到起手前，一次都不落盘。 */
    fun cancelDrag() {
        val session = drag ?: return
        settleJob?.cancel()
        settleJob = null
        order = session.orderAtStart
        visibleItems = session.visibleAtStart
        drag = null
        dragOffsetPx = 0f
    }

    /** 立即丢弃拖动状态（含正在跑的回弹动画）。 */
    fun dropDrag() {
        settleJob?.cancel()
        settleJob = null
        drag = null
        dragOffsetPx = 0f
    }

    private fun persistIfChanged() {
        if (order == persistedOrder && visibleItems == persistedVisible) return
        persistedOrder = order
        persistedVisible = visibleItems
        dirty = true
        onPersist(order, visibleItems)
    }
}

// --- 接线 --------------------------------------------------------------------

/**
 * 记住一份 [DrawerReorderState]，并在外部持久值变化时把它同步进来。
 *
 * 同步用内容比较而不是实例比较：DataStore 每次写都会造出新的 List/Set，
 * 包括本面板自己刚写的那次，按实例同步会让拖动被自己的回写打断。
 */
@Composable
internal fun rememberDrawerReorderState(
    persistedOrder: List<String>,
    persistedVisibleItems: Set<String>,
    aiAvailable: Boolean,
    onPersist: (order: List<String>, visibleItems: Set<String>) -> Unit,
): DrawerReorderState {
    val scope = rememberCoroutineScope()
    val persist by rememberUpdatedState(onPersist)
    val state = remember(aiAvailable) {
        DrawerReorderState(
            initialOrder = persistedOrder,
            initialVisibleItems = persistedVisibleItems,
            aiAvailable = aiAvailable,
            scope = scope,
            onPersist = { order, visibleItems -> persist(order, visibleItems) },
        )
    }
    LaunchedEffect(persistedOrder, persistedVisibleItems, state.busy) {
        state.applyPersisted(persistedOrder, persistedVisibleItems)
    }
    return state
}

/**
 * 排序模式的容器级拖动手势。
 *
 * 手势挂在列表上而不是行上：行的下标一变，它的组合就会被销毁，挂在行上的
 * pointerInput 会被自己的第一次换位取消。命中测试走 LazyColumn 的 item key
 * （也就是功能 id），所以分组标题、空状态这些项不可能被误当成可拖的行，
 * 也就不需要「减 1」这类容易写错的换算。
 */
internal fun Modifier.drawerReorderDrag(
    state: DrawerReorderState,
    listState: LazyListState,
    rowHeightPx: Float,
    animationsEnabled: Boolean,
    onGrab: () -> Unit,
): Modifier = pointerInput(state, listState, rowHeightPx, animationsEnabled) {
    var active = false
    detectDragGestures(
        onDragStart = { start ->
            val hit = rowUnder(state, listState, start.y)
            active = hit != null &&
                state.beginDrag(hit.id, hit.slot, start.y, rowHeightPx)
            if (active) onGrab()
        },
        onDrag = { change, _ ->
            if (!active) return@detectDragGestures
            change.consume()
            state.dragTo(change.position.y)
        },
        onDragEnd = {
            if (active) {
                active = false
                state.releaseDrag(animationsEnabled)
            }
        },
        onDragCancel = {
            if (active) {
                active = false
                state.cancelDrag()
            }
        },
    )
}

private class DrawerRowHit(val id: String, val slot: Int)

/** 用列表自己的布局信息与 item key 解析 [y] 落在哪一行。 */
private fun rowUnder(
    state: DrawerReorderState,
    listState: LazyListState,
    y: Float,
): DrawerRowHit? {
    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        y >= info.offset && y < info.offset + info.size
    } ?: return null
    val id = item.key as? String ?: return null
    val slot = state.displayedIds().indexOf(id)
    return if (slot >= 0) DrawerRowHit(id, slot) else null
}

// --- 排序模式的部件 ----------------------------------------------------------

/** 排序模式下每行右侧的拖拽把手；进场淡入，不打断布局。 */
@Composable
internal fun DrawerDragHandle(animationsEnabled: Boolean) {
    val label = stringResource(R.string.home_drawer_sort_handle)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val entrance = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animationsEnabled) entrance.animateTo(1f, MotionTokens.enter())
    }
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer { alpha = entrance.value }
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

/** 排序模式头部右上角的「完成」：确认并退出排序。 */
@Composable
internal fun DrawerSortDoneButton(onClick: () -> Unit) {
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
