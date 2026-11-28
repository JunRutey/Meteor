package com.meteor.memory.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Reorder maths for the home drawer's sort mode.
 *
 * Everything here is pure Kotlin on purpose: the drag used to be glued to
 * Compose state, so the only way to notice a broken slot calculation was to
 * drag by hand on a device. The two halves that decide the result — "which
 * slot does this move land in" ([movedDrawerIdToSlot]) and "where is the row
 * drawn while the finger travels" ([DrawerDragGeometry]) — are pinned
 * separately, and then checked together against a simulated drag.
 */
class DrawerReorderTest {

    private val all = listOf("questions", "album", "people", "ai", "search")
    private val allVisible = all.toSet()

    // --- 显示列表 -----------------------------------------------------------

    @Test
    fun displayedIds_dropHiddenEntriesAndUnavailableAi() {
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
    fun normalizedOrder_keepsStoredOrderAndAppendsItemsItHasNeverSeen() {
        val stored = listOf("search", "questions", "album", "people", "ai")
        assertEquals(stored, normalizedDrawerOrder(stored))
        // Unknown ids and duplicates are dropped, and anything the stored order
        // never heard of is appended instead of disappearing.
        assertEquals(
            listOf("search", "questions", "album", "people", "ai"),
            normalizedDrawerOrder(listOf("search", "ghost", "search", "questions")),
        )
        assertEquals(all, normalizedDrawerOrder(emptyList()))
    }

    @Test
    fun normalizedVisibleItems_dropUnknownIdsAndAllowAnEmptyChoice() {
        assertEquals(
            setOf("album", "search"),
            normalizedDrawerVisibleItems(setOf("album", "ghost", "search")),
        )
        assertTrue(normalizedDrawerVisibleItems(emptySet()).isEmpty())
    }

    // --- 移动 -----------------------------------------------------------------

    @Test
    fun moveToSlot_placesTheRowOnTheFarSideOfTheRowItPasses() {
        // Downwards: the dragged row ends up AFTER the row at the target slot.
        assertEquals(
            listOf("album", "people", "questions", "ai", "search"),
            movedDrawerIdToSlot(all, allVisible, true, "questions", 2),
        )
        // Upwards: it ends up BEFORE the row at the target slot.
        assertEquals(
            listOf("questions", "search", "album", "people", "ai"),
            movedDrawerIdToSlot(all, allVisible, true, "search", 1),
        )
    }

    @Test
    fun moveToSlot_countsSlotsInDisplayedRowsOnly() {
        val visible = setOf("questions", "album", "search")
        // Display slots are [questions, album, search]; "people" and "ai" are
        // hidden and must not add invisible steps to the move.
        assertEquals(
            listOf("album", "people", "ai", "search", "questions"),
            movedDrawerIdToSlot(all, visible, true, "questions", 2),
        )
        assertEquals(
            listOf("search", "questions", "album", "people", "ai"),
            movedDrawerIdToSlot(all, visible, true, "search", 0),
        )
    }

    @Test
    fun moveToSlot_ignoresAiWhenThisBuildHasNoAiEntry() {
        assertEquals(
            listOf("album", "people", "questions", "ai", "search"),
            movedDrawerIdToSlot(all, allVisible, false, "questions", 2),
        )
    }

    @Test
    fun moveToSlot_rejectsStaleRequests() {
        assertNull(movedDrawerIdToSlot(all, allVisible, true, "questions", 0))
        assertNull(movedDrawerIdToSlot(all, allVisible, true, "questions", 99))
        assertNull(movedDrawerIdToSlot(all, allVisible, true, "questions", -1))
        assertNull(movedDrawerIdToSlot(all, allVisible, true, "missing", 2))
        assertNull(movedDrawerIdToSlot(all, setOf("album"), true, "questions", 0))
    }

    @Test
    fun moveToSlot_doesNotTouchTheInputList() {
        val original = all.toMutableList()
        movedDrawerIdToSlot(original, allVisible, true, "questions", 3)
        assertEquals(all, original)
    }

    @Test
    fun movingDownAndBackRestoresTheVisibleSequence() {
        val visible = setOf("questions", "album", "people", "search")
        // Drag "questions" down past the hidden "ai" to the last slot, then
        // back to the first: the visible sequence must be what it was.
        val down = movedDrawerIdToSlot(all, visible, true, "questions", 3)!!
        val back = movedDrawerIdToSlot(down, visible, true, "questions", 0)!!
        assertEquals(
            displayedDrawerIds(all, visible, true),
            displayedDrawerIds(back, visible, true),
        )
    }

    // --- 拖动几何 ------------------------------------------------------------

    /** One row: 54dp at 3x density. */
    private val rowHeight = 162f

    private fun geometry(anchorSlot: Int = 1, anchorY: Float = 100f, slots: Int = 5) =
        DrawerDragGeometry(
            anchorSlot = anchorSlot,
            anchorPointerY = anchorY,
            rowHeightPx = rowHeight,
            slotCount = slots,
        )

    @Test
    fun slotAt_mapsPointerTravelToWholeSlots() {
        val drag = geometry()
        assertEquals(1, drag.slotAt(100f))
        assertEquals(1, drag.slotAt(100f + rowHeight * 0.4f))
        assertEquals(2, drag.slotAt(100f + rowHeight * 0.6f))
        assertEquals(3, drag.slotAt(100f + rowHeight * 2.1f))
        assertEquals(0, drag.slotAt(100f - rowHeight * 0.6f))
    }

    @Test
    fun slotAt_neverLeavesTheList() {
        val drag = geometry()
        assertEquals(0, drag.slotAt(-5_000f))
        assertEquals(4, drag.slotAt(5_000f))
        // A list that cannot move still reports a valid slot.
        assertEquals(0, geometry(slots = 1).slotAt(9_999f))
        assertEquals(0, geometry(slots = 0).slotAt(9_999f))
    }

    @Test
    fun offset_followsTheFingerOneToOneInsideTheGrabSlot() {
        val drag = geometry()
        assertEquals(0.0, drag.offsetAt(100f, 1).toDouble(), 1e-3)
        assertEquals(30.0, drag.offsetAt(130f, 1).toDouble(), 1e-3)
        assertEquals(-30.0, drag.offsetAt(70f, 1).toDouble(), 1e-3)
    }

    /**
     * The row's screen position is `slot * rowHeight + offset`. It must move
     * with the finger and only with the finger: a slot change re-labels which
     * slot carries the offset, it must not move the row.
     */
    @Test
    fun offset_keepsTheRowContinuousAcrossSlotChanges() {
        val drag = geometry()
        // 0.13 of a row per step: deliberately never lands exactly on a slot
        // boundary, where either neighbouring slot would be equally correct.
        val stepPx = rowHeight * 0.13f
        val startScreenY = 1 * rowHeight
        // 20 steps ≈ 2.6 rows of travel from slot 1 stays inside the 5-slot list.
        repeat(20) { step ->
            val y = 100f + step * stepPx
            val slot = drag.slotAt(y)
            // The row's screen position, in doubles so the assertion itself does
            // not accumulate float error over the loop.
            val screenY = (slot * rowHeight + drag.offsetAt(y, slot)).toDouble()
            val expected = startScreenY.toDouble() + step * stepPx.toDouble()
            assertEquals("row jumped at step $step (slot $slot)", expected, screenY, 1e-3)
        }
    }

    @Test
    fun offset_stopsHalfARowOutsideTheList() {
        val drag = geometry()
        // Dragged far above the first slot: the row parks half a row above it.
        assertEquals((-rowHeight / 2f).toDouble(), drag.offsetAt(-5_000f, 0).toDouble(), 1e-3)
        // And half a row below the last one.
        assertEquals((rowHeight / 2f).toDouble(), drag.offsetAt(5_000f, 4).toDouble(), 1e-3)
    }

    // --- 两者合起来 -----------------------------------------------------------

    @Test
    fun dragWithAHiddenRow_landsOnTheSlotUnderTheFinger() {
        // "album" is hidden, so the drawn list is [questions, people, ai, search].
        // The finger travels just past the middle of the third slot: the row
        // must end up there, without the hidden entry adding a phantom step.
        val visible = allVisible - "album"
        val drag = geometry(anchorSlot = 0, anchorY = 0f, slots = 4)
        val y = rowHeight * 2f + rowHeight * 0.75f
        val target = drag.slotAt(y)
        assertEquals(3, target)

        val moved = movedDrawerIdToSlot(all, visible, true, "questions", target)!!
        assertEquals(
            listOf("questions", "people", "ai", "search"),
            displayedDrawerIds(all, visible, true),
        )
        assertEquals(
            listOf("people", "ai", "search", "questions"),
            displayedDrawerIds(moved, visible, true),
        )
    }

    @Test
    fun simulatedDrag_keepsTheDraggedRowInTheSlotTheGeometryClaims() {
        // "album" hidden again; drag the first row all the way down and hold.
        val visible = allVisible - "album"
        var order = all
        val drag = geometry(anchorSlot = 0, anchorY = 50f, slots = 4)
        var slot = 0

        repeat(40) { step ->
            val y = 50f + (step + 1) * (rowHeight / 4f)
            val target = drag.slotAt(y)
            if (target != slot) {
                order = movedDrawerIdToSlot(order, visible, true, "questions", target)!!
                slot = target
            }
            assertEquals(
                "dragged row must sit in the slot the geometry reports",
                "questions",
                displayedDrawerIds(order, visible, true)[target],
            )
            assertTrue(abs(drag.offsetAt(y, target)) <= rowHeight / 2f + 0.01f)
        }

        assertEquals(
            listOf("people", "ai", "search", "questions"),
            displayedDrawerIds(order, visible, true),
        )
        // Only the visible rows moved; the hidden one kept its position.
        assertEquals(listOf("album", "people", "ai", "search", "questions"), order)
    }
}
