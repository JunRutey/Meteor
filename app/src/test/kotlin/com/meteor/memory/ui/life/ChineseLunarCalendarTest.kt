package com.meteor.memory.ui.life

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseLunarCalendarTest {

    private val calendar = ChineseLunarCalendar()

    // ---- golden conversions against the official calendar ----

    @Test
    fun `lunar new year 2024 converts to February 10`() {
        assertEquals(LocalDate.of(2024, 2, 10), calendar.toGregorian(2024, 1, 1, false))
    }

    @Test
    fun `lunar 2027 first month 25 converts to March 2`() {
        // Regression: ICU's ephemeris put the 2027-02-06 23:56 (UTC+8) new moon
        // on Feb 7 and returned 2027-03-03 (ADR-047).
        assertEquals(LocalDate.of(2027, 3, 2), calendar.toGregorian(2027, 1, 25, false))
    }

    @Test
    fun `lunar 2027 month boundaries match the official calendar`() {
        assertEquals(LocalDate.of(2027, 2, 6), calendar.toGregorian(2027, 1, 1, false))
        assertEquals(LocalDate.of(2027, 3, 8), calendar.toGregorian(2027, 2, 1, false))
        assertEquals(LocalDate.of(2027, 4, 1), calendar.toGregorian(2027, 2, 25, false))
    }

    @Test
    fun `lunar 2011 first month 25 converts to February 27`() {
        assertEquals(LocalDate.of(2011, 2, 3), calendar.toGregorian(2011, 1, 1, false))
        assertEquals(LocalDate.of(2011, 2, 27), calendar.toGregorian(2011, 1, 25, false))
    }

    @Test
    fun `lunar 2011 second month 25 converts to March 29`() {
        assertEquals(LocalDate.of(2011, 3, 29), calendar.toGregorian(2011, 2, 25, false))
    }

    @Test
    fun `year 1933 matches the Purple Mountain Observatory almanac`() {
        assertEquals(LocalDate.of(1933, 1, 26), calendar.toGregorian(1933, 1, 1, false))
        assertEquals(LocalDate.of(1933, 5, 28), calendar.toGregorian(1933, 5, 5, false))
        assertEquals(LocalDate.of(1933, 6, 23), calendar.toGregorian(1933, 5, 1, true))
        assertEquals(LocalDate.of(1933, 10, 4), calendar.toGregorian(1933, 8, 15, false))
    }

    @Test
    fun `year 1933 month lengths match the official almanac`() {
        val expected = mapOf(
            1 to 29, 2 to 30, 3 to 30, 4 to 29, 5 to 30,
            6 to 29, 7 to 30, 8 to 29, 9 to 30, 10 to 29, 11 to 29, 12 to 30,
        )
        for ((month, lastDay) in expected) {
            val entry = calendar.monthsInYear(1933).first { it.month == month && !it.leapMonth }
            assertEquals("1933 month $month", lastDay, entry.lastDay)
        }
        assertEquals(30, calendar.monthsInYear(1933).first { it.month == 5 && it.leapMonth }.lastDay)
    }

    // ---- leap months ----

    @Test
    fun `leap second month 2023 converts correctly`() {
        assertEquals(LocalDate.of(2023, 3, 22), calendar.toGregorian(2023, 2, 1, true))
        assertEquals(LocalDate.of(2023, 4, 19), calendar.toGregorian(2023, 2, 29, true))
        assertNull(calendar.toGregorian(2023, 2, 30, true))
    }

    @Test
    fun `invalid leap month selection is rejected`() {
        assertNull(calendar.toGregorian(2024, 2, 1, true))
        assertNull(calendar.toGregorian(2027, 1, 1, true))
    }

    @Test
    fun `known leap months are present in the table`() {
        fun hasLeap(year: Int, month: Int) =
            calendar.monthsInYear(year).any { it.month == month && it.leapMonth }

        assertTrue(hasLeap(2020, 4))
        assertTrue(hasLeap(2023, 2))
        assertTrue(hasLeap(2025, 6))
        assertTrue(hasLeap(2028, 5))
        assertTrue(hasLeap(2031, 3))
        assertTrue(hasLeap(2033, 11))
        assertFalse(hasLeap(2027, 1))
    }

    // ---- next birthday ----

    @Test
    fun `leap birthday falls back to regular month in year without leap month`() {
        val profile = BirthProfile.Lunar(
            year = 2023,
            month = 2,
            day = 1,
            leapMonth = true,
            gregorianDate = LocalDate.of(2023, 3, 22),
        )

        val birthday = calendar.nextBirthday(
            birth = profile,
            now = Instant.parse("2024-01-01T00:00:00Z"),
            zone = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(LocalDate.of(2024, 3, 10), birthday)
    }

    @Test
    fun `next lunar birthday from autumn 2026 is April first 2027`() {
        // A fixed lunar date maps to a different Gregorian date in 2011 and
        // 2027, so the next-birthday date must come from the target year, not
        // from the stored birth-year Gregorian date.
        val profile = BirthProfile.Lunar(
            year = 2011,
            month = 2,
            day = 25,
            leapMonth = false,
            gregorianDate = LocalDate.of(2011, 3, 29),
        )

        val birthday = calendar.nextBirthday(
            birth = profile,
            now = Instant.parse("2026-11-14T01:48:36Z"),
            zone = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(LocalDate.of(2027, 4, 1), birthday)
    }

    @Test
    fun `next lunar birthday for first month 25 2027 is March 2`() {
        val profile = BirthProfile.Lunar(
            year = 2011,
            month = 1,
            day = 25,
            leapMonth = false,
            gregorianDate = LocalDate.of(2011, 2, 27),
        )

        val birthday = calendar.nextBirthday(
            birth = profile,
            now = Instant.parse("2026-11-21T04:00:00Z"),
            zone = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(LocalDate.of(2027, 3, 2), birthday)
    }

    // ---- table integrity ----

    @Test
    fun `month list includes real length and leap entry`() {
        val months = calendar.monthsInYear(2023)

        assertNotNull(months.firstOrNull { it.month == 2 && !it.leapMonth })
        assertNotNull(months.firstOrNull { it.month == 2 && it.leapMonth })
    }

    @Test
    fun `repeated calls across instances return the same conversion`() {
        val first = ChineseLunarCalendar()
        val second = ChineseLunarCalendar()
        val expected = LocalDate.of(2024, 2, 10)

        repeat(3) {
            assertEquals(expected, first.toGregorian(2024, 1, 1, false))
            assertEquals(expected, second.toGregorian(2024, 1, 1, false))
        }
        assertEquals(first.monthsInYear(2023), second.monthsInYear(2023))
    }

    @Test
    fun `to lunar inverts to gregorian over the whole table`() {
        var day = LocalDate.of(1900, 2, 1)
        val end = LocalDate.of(2099, 12, 31)
        while (!day.isAfter(end)) {
            val lunar = checkNotNull(calendar.toLunar(day)) { "no lunar date for $day" }
            assertEquals("round trip at $day", day, calendar.toGregorian(lunar.year, lunar.month, lunar.day, lunar.leapMonth))
            day = day.plusDays(1)
        }
    }

    @Test
    fun `to lunar reads known dates`() {
        assertEquals(
            LunarDate(2027, 1, 25, false),
            calendar.toLunar(LocalDate.of(2027, 3, 2)),
        )
        assertEquals(
            LunarDate(2027, 1, 1, false),
            calendar.toLunar(LocalDate.of(2027, 2, 6)),
        )
        assertEquals(
            LunarDate(1933, 5, 1, true),
            calendar.toLunar(LocalDate.of(1933, 6, 23)),
        )
    }

    @Test
    fun `out of range dates convert to null`() {
        assertNull(calendar.toGregorian(2101, 1, 1, false))
        assertNull(calendar.toGregorian(1899, 1, 1, false))
        assertNull(calendar.toLunar(LocalDate.of(1900, 1, 30)))
        assertNull(calendar.toLunar(LocalDate.of(2101, 6, 1)))
        assertTrue(calendar.monthsInYear(2101).isEmpty())
    }

    // ---- normalize ----

    @Test
    fun `normalize repairs a stored gregorian date that is one day off`() {
        val broken = BirthProfile.Lunar(
            year = 2027,
            month = 1,
            day = 25,
            leapMonth = false,
            gregorianDate = LocalDate.of(2027, 3, 3),
        )

        val repaired = checkNotNull(calendar.normalize(broken) as BirthProfile.Lunar)

        assertEquals(LocalDate.of(2027, 3, 2), repaired.gregorianDate)
        assertEquals(2027, repaired.year)
        assertEquals(1, repaired.month)
        assertEquals(25, repaired.day)
    }

    @Test
    fun `normalize keeps profiles that already agree`() {
        val lunar = BirthProfile.Lunar(
            year = 2011,
            month = 1,
            day = 25,
            leapMonth = false,
            gregorianDate = LocalDate.of(2011, 2, 27),
        )
        val solar = BirthProfile.Solar(LocalDate.of(2000, 6, 15))

        assertEquals(lunar, calendar.normalize(lunar))
        assertEquals(solar, calendar.normalize(solar))
    }
}
