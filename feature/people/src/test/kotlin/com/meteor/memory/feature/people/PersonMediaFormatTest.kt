package com.meteor.memory.feature.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The media strip's only non-Compose logic: the duration badge (ADR-056).
 *
 * Duration text is digits and colons only — no string resource, no locale
 * surprise — so the formatting rule is what needs pinning down.
 */
class PersonMediaFormatTest {

    @Test
    fun `unknown or non-positive durations produce no badge`() {
        assertNull(PersonMediaFormat.duration(null))
        assertNull(PersonMediaFormat.duration(0L))
        assertNull(PersonMediaFormat.duration(-1L))
    }

    @Test
    fun `sub-minute durations keep a zero minute field`() {
        assertEquals("0:05", PersonMediaFormat.duration(5_000L))
        // Truncation, not rounding: a 59.9s clip is not a one-minute clip.
        assertEquals("0:59", PersonMediaFormat.duration(59_900L))
    }

    @Test
    fun `minutes and seconds are zero padded`() {
        assertEquals("1:00", PersonMediaFormat.duration(60_000L))
        assertEquals("1:05", PersonMediaFormat.duration(65_000L))
        assertEquals("12:34", PersonMediaFormat.duration(754_000L))
        assertEquals("59:59", PersonMediaFormat.duration(3_599_000L))
    }

    @Test
    fun `past an hour the hour field appears`() {
        assertEquals("1:00:00", PersonMediaFormat.duration(3_600_000L))
        assertEquals("1:02:03", PersonMediaFormat.duration(3_723_000L))
        assertEquals("2:00:00", PersonMediaFormat.duration(7_200_000L))
    }

    @Test
    fun `a one-millisecond clip still renders as zero seconds`() {
        assertEquals("0:00", PersonMediaFormat.duration(1L))
    }
}
