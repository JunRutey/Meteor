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
    val known = DrawerItem.entries.map { it.id }
    val knownIds = known.toSet()
    val kept = order.map { it.trim() }.filter { it in knownIds }.distinct()
    return kept + known.filter { it !in kept }
}

/** Drops ids this build does not know; an empty set is a valid choice. */
fun normalizedDrawerVisibleItems(visibleItems: Set<String>): Set<String> {
    val known = DrawerItem.entries.mapTo(HashSet()) { it.id }
    return visibleItems.filterTo(LinkedHashSet()) { it in known }
}

/**
 * The rows the drawer actually renders, in order: hidden entries are dropped,
 * and so is AI when this build has no AI entry point.
 *
 * Drag targets are counted in **these** slots, never in the persisted order.
 * Keeping the two apart is what stops a hidden row from adding an invisible
 * extra step to a drag.
 */
fun displayedDrawerIds(
    order: List<String>,
    visibleItems: Set<String>,
    aiAvailable: Boolean,
): List<String> = order.filter { id ->
    id in visibleItems && (aiAvailable || id != DrawerItem.AI.id)
}

/**
 * Moves [id] to display slot [targetSlot] and returns the new persisted order.
 *
 * Only the dragged entry moves. It lands on the far side of the row it crossed
 * (dragging down puts it after that row, dragging up puts it before it), which
 * is what makes a drag reversible: moving down and back up returns to the same
 * visible sequence. Returns null when the request is stale — unknown id, slot
 * out of range, or already there — so a late pointer event is ignored instead
 * of corrupting the order.
 *
 * Hidden entries keep their relative order; which side of them the dragged
 * entry ends up on follows from how far it travelled.
 */
fun movedDrawerIdToSlot(
    order: List<String>,
    visibleItems: Set<String>,
    aiAvailable: Boolean,
    id: String,
    targetSlot: Int,
): List<String>? {
    val shown = displayedDrawerIds(order, visibleItems, aiAvailable)
    if (targetSlot !in shown.indices) return null
    val fromSlot = shown.indexOf(id)
    if (fromSlot < 0 || fromSlot == targetSlot) return null
    val fromFull = order.indexOf(id)
    val toFull = order.indexOf(shown[targetSlot])
    if (fromFull < 0 || toFull < 0) return null

    // Remove first, then insert at the target's pre-removal index: when the
    // target sat after [id] the indices shift down and this lands the entry
    // after the target, and when it sat before, before it.
    val next = order.toMutableList()
    val value = next.removeAt(fromFull)
    next.add(toFull.coerceIn(0, next.size), value)
    return next
}
