package com.meteor.memory.ui.home

import com.meteor.memory.R
import com.meteor.memory.feature.calendar.R as CalendarR
import com.meteor.memory.feature.question.R as QuestionR

/**
 * The features that can live in the home drawer (1.7.4).
 *
 * The settings entry itself is deliberately absent: it is pinned to the bottom
 * of the drawer and never takes part in custom visibility or ordering.
 *
 * [id] is the stable value persisted in DataStore and backup snapshots. Never
 * rename an id; add a new entry instead so old preferences keep working.
 */
enum class DrawerItem(
    val id: String,
    val labelRes: Int,
    val hintRes: Int,
) {
    QUESTIONS(
        id = "questions",
        labelRes = QuestionR.string.question_title,
        hintRes = R.string.home_drawer_questions_hint,
    ),
    ALBUM(
        id = "album",
        labelRes = CalendarR.string.calendar_open_gallery,
        hintRes = CalendarR.string.calendar_quick_album_hint,
    ),
    PEOPLE(
        id = "people",
        labelRes = CalendarR.string.calendar_open_people,
        hintRes = CalendarR.string.calendar_quick_people_hint,
    ),
    AI(
        id = "ai",
        labelRes = CalendarR.string.calendar_open_ai,
        hintRes = R.string.home_drawer_ai_hint,
    ),
    SEARCH(
        id = "search",
        labelRes = CalendarR.string.calendar_open_search,
        hintRes = R.string.home_drawer_search_hint,
    ),
    ;

    companion object {
        fun fromId(id: String): DrawerItem? = entries.firstOrNull { it.id == id }
    }
}

/** Default order matches the pre-1.7.4 drawer. */
val DEFAULT_DRAWER_ORDER: List<String> = DrawerItem.entries.map { it.id }

/** Everything is visible by default. */
val DEFAULT_DRAWER_VISIBLE_ITEMS: Set<String> = DEFAULT_DRAWER_ORDER.toSet()

/**
 * Puts a persisted order into the canonical shape: unknown ids are dropped,
 * duplicates collapsed, and items added by a newer release are appended instead
 * of disappearing because an old preference did not know them.
 */
fun normalizedDrawerOrder(order: List<String>): List<String> {
    val knownIds = DrawerItem.entries.map { it.id }
    val known = knownIds.toSet()
    val cleaned = order.map { it.trim() }.filter { it in known }.distinct()
    return cleaned + knownIds.filter { it !in cleaned }
}

/** Drops ids this build does not know; an empty set is a valid choice. */
fun normalizedDrawerVisibleItems(visibleItems: Set<String>): Set<String> {
    val known = DrawerItem.entries.mapTo(HashSet()) { it.id }
    return visibleItems.filterTo(LinkedHashSet()) { it in known }
}

/**
 * The ids actually shown in the drawer, in their current order. Hidden entries
 * (and AI when this build has no AI entry point) are filtered out of the full
 * persisted order. This is the single source of truth for both the UI list and
 * the drag-to-reorder maths — mixing a stale copy with a fresh [order] is what
 * made a downward drag flip back.
 */
fun displayedDrawerIds(
    order: List<String>,
    visibleItems: Set<String>,
    aiAvailable: Boolean,
): List<String> = order.filter { id ->
    id in visibleItems && (aiAvailable || id != DrawerItem.AI.id)
}

/**
 * Moves [draggedId] from its current displayed slot to [targetSlot] and returns
 * the new full persisted order. Hidden and unavailable entries stay in place;
 * only the dragged entry is relocated.
 *
 * The caller works in *display* slots (visible rows only) when deciding the
 * target, and this helper translates that into a move on the full order, so a
 * hidden entry between two visible rows cannot shift the result. Returns null
 * (leaving the order untouched) when the ids do not resolve, so a stale drag
 * event is ignored rather than corrupting the order.
 */
fun moveDisplayedDrawerId(
    order: List<String>,
    visibleItems: Set<String>,
    aiAvailable: Boolean,
    draggedId: String,
    targetSlot: Int,
): List<String>? {
    val displayed = displayedDrawerIds(order, visibleItems, aiAvailable)
    val fromSlot = displayed.indexOf(draggedId)
    if (fromSlot < 0 || targetSlot !in displayed.indices || fromSlot == targetSlot) return null

    val fullFrom = order.indexOf(draggedId)
    val fullTo = order.indexOf(displayed[targetSlot])
    if (fullFrom < 0 || fullTo < 0) return null
    return order.moved(fullFrom, fullTo)
}

private fun List<String>.moved(from: Int, to: Int): List<String> {
    if (from == to || from !in indices || to !in indices) return this
    val copy = toMutableList()
    val value = copy.removeAt(from)
    copy.add(to, value)
    return copy
}
