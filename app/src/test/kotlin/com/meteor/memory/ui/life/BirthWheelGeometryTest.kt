package com.meteor.memory.ui.life

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wheel scroll offset is what [BirthDatePicker] uses both to place the
 * committed row and to ease back after a drag. It must put the row's centre
 * exactly on the wheel's centre line, otherwise "snaps back to the selection"
 * would leave the item visually high or low.
 */
class BirthWheelGeometryTest {

    private val itemHeight = 36
    private val itemSpacing = 2
    private val viewportHeight = 144

    @Test
    fun committedRowLandsOnTheCentreLine() {
        for (index in 0..10) {
            val offset = wheelScrollOffsetFor(index, viewportHeight, itemHeight, itemSpacing)
            val rowTop = offset + index * (itemHeight + itemSpacing)
            val rowCenter = rowTop + itemHeight / 2
            assertEquals(viewportHeight / 2, rowCenter)
        }
    }

    @Test
    fun firstRowStartsAfterTheCentrePadding() {
        assertEquals(-54, wheelScrollOffsetFor(0, viewportHeight, itemHeight, itemSpacing))
    }

    @Test
    fun offsetsShareOnePitch() {
        val first = wheelScrollOffsetFor(3, viewportHeight, itemHeight, itemSpacing)
        val second = wheelScrollOffsetFor(4, viewportHeight, itemHeight, itemSpacing)
        assertEquals(itemHeight + itemSpacing, second - first)
    }
}
