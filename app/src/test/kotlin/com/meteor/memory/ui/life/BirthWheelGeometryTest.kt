package com.meteor.memory.ui.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wheel scroll offset is what [BirthDatePicker] uses both to place the
 * committed row and to ease back after a drag. It must put the row's centre
 * exactly on the wheel's centre line, otherwise "snaps back to the selection"
 * would leave the item visually high or low.
 *
 * Rows are 38dp tall with a 2dp gap inside a 158dp window. If the arithmetic
 * ever drifts by a pixel, the committed row drifts with it and every release
 * leaves the wheel half an item off.
 */
class BirthWheelGeometryTest {

    private val rowHeight = 38
    private val rowGap = 2
    private val itemBlockSize = rowHeight + rowGap
    private val viewportHeight = 158

    @Test
    fun committedRowLandsOnTheCentreLine() {
        for (index in 0..10) {
            val offset = wheelScrollOffsetFor(index, viewportHeight, itemBlockSize)
            val rowTop = offset + index * itemBlockSize
            val rowCenter = rowTop + rowHeight / 2
            assertEquals(viewportHeight / 2, rowCenter)
        }
    }

    @Test
    fun firstRowStartsHalfAWindowAboveTheTop() {
        // 158 - 38 = 120; the resting position is -60, which shows the partial
        // rows above and below the committed one.
        assertEquals(-60, wheelScrollOffsetFor(0, viewportHeight, itemBlockSize))
    }

    @Test
    fun offsetsShareOneItemPitch() {
        val first = wheelScrollOffsetFor(3, viewportHeight, itemBlockSize)
        val second = wheelScrollOffsetFor(4, viewportHeight, itemBlockSize)
        assertEquals(itemBlockSize, second - first)
    }

    @Test
    fun windowHoldsAWholeNumberOfRows() {
        // Four rows and three gaps; anything else would leave the resting row
        // between two items.
        assertEquals(viewportHeight, 4 * rowHeight + 3 * rowGap)
        assertTrue(viewportHeight >= 4 * rowHeight)
    }
}
