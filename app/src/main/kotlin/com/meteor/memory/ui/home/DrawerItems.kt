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
