package com.meteor.memory.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the drawer drag-to-reorder maths. The helpers under
 * test are pure Kotlin so a JVM test can pin the tricky cases that used to
 * make the dragged row jump: hidden entries between slots, a disabled AI
 * entry, dragging past the end and back, and fast multi-slot drags.
 */
class DrawerSortTest {

    private val all = listOf("questions", "album", "people", "ai", "search")
    private val allVisible = all.toSet()

    @Test
    fun displayedIds_filterHiddenAndUnavailableAi() {
        assertEquals(all, displayedDrawerIds(all, allVisible, aiAvailable = true))
        assertEquals(
            listOf("questions", "album", "people", "search"),
            displayedDrawerIds(all, allVisible, aiAvailable = false),
        )
        assertEquals(
            listOf("questions", "people", "ai"),
            displayedDrawerIds(all, setOf("questions", "people", "ai"), aiAvailable = true),
        )
    }

    @Test
    fun moveWithinVisibleRows() {
        assertEquals(
            listOf("album", "people", "questions", "ai", "search"),
            moveDisplayedDrawerId(all, allVisible, true, "questions", 2),
        )
        assertEquals(
            listOf("questions", "search", "album", "people", "ai"),
            moveDisplayedDrawerId(all, allVisible, true, "search", 1),
        )
    }

    @Test
    fun moveAcrossHiddenRows_ignoresThemInTargetSlot() {
        // "people" and "ai" are hidden: display slots are [questions, album, search].
        // Dragging questions to display slot 2 must land it after search in the
        // persisted order, without the hidden rows shifting the target.
        val visible = setOf("questions", "album", "search")
        assertEquals(
            listOf("album", "people", "ai", "search", "questions"),
            moveDisplayedDrawerId(all, visible, true, "questions", 2),
        )
        assertEquals(
            listOf("search", "questions", "album", "people", "ai"),
            moveDisplayedDrawerId(all, visible, true, "search", 0),
        )
    }

    @Test
    fun disabledAiIsFilteredFromDisplaySlots() {
        // With AI unavailable, display slots are [questions, album, people, search],
        // so dragging questions to slot 2 lands it after people (not after ai).
        assertEquals(
            listOf("album", "people", "questions", "ai", "search"),
            moveDisplayedDrawerId(all, allVisible, false, "questions", 2),
        )
    }

    @Test
    fun dragToSameSlotOrUnknownIdIsIgnored() {
        assertNull(moveDisplayedDrawerId(all, allVisible, true, "questions", 0))
        assertNull(moveDisplayedDrawerId(all, allVisible, true, "questions", 99))
        assertNull(moveDisplayedDrawerId(all, allVisible, true, "missing", 2))
    }

    @Test
    fun dragPastEndThenBackRestoresOriginalOrder() {
        // The caller derives the target from the fixed grab slot plus finger
        // travel, so returning to the grab position restores the original order.
        val visible = setOf("questions", "album", "people", "search")
        val down = moveDisplayedDrawerId(all, visible, true, "questions", 3)!!
        val back = moveDisplayedDrawerId(down, visible, true, "questions", 0)
        assertEquals(all, back)
    }

    @Test
    fun fastMultiSlotDragMovesOnce() {
        assertEquals(
            listOf("album", "people", "ai", "search", "questions"),
            moveDisplayedDrawerId(all, allVisible, true, "questions", 4),
        )
    }
}
