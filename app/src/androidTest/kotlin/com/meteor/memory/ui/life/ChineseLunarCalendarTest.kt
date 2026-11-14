package com.meteor.memory.ui.life

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseLunarCalendarTest {

    private val calendar = ChineseLunarCalendar()

    // Method names are plain identifiers on purpose: this test is compiled into
    // a DEX file, and DEX versions below 040 reject spaces in method names.
    @Test
    fun lunarNewYear2024ConvertsToFebruaryTenth() {
        assertEquals(LocalDate.of(2024, 2, 10), calendar.toGregorian(2024, 1, 1, false))
    }

    @Test
    fun leapSecondMonth2023ConvertsCorrectly() {
        assertEquals(LocalDate.of(2023, 3, 22), calendar.toGregorian(2023, 2, 1, true))
        assertEquals(LocalDate.of(2023, 4, 19), calendar.toGregorian(2023, 2, 29, true))
        assertNull(calendar.toGregorian(2023, 2, 30, true))
    }

    @Test
    fun invalidLeapMonthSelectionIsRejected() {
        assertNull(calendar.toGregorian(2024, 2, 1, true))
    }

    @Test
    fun leapBirthdayFallsBackToRegularMonthInYearWithoutLeapMonth() {
        val profile = BirthProfile.Lunar(
            year = 2023,
            month = 2,
            day = 1,
            leapMonth = true,
            gregorianDate = LocalDate.of(2023, 3, 22),
        )

        val birthday = calendar.nextBirthday(
            birth = profile,
            now = java.time.Instant.parse("2024-01-01T00:00:00Z"),
            zone = java.time.ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(LocalDate.of(2024, 3, 10), birthday)
    }

    @Test
    fun lunar2011SecondMonth25ConvertsToMarch29() {
        assertEquals(LocalDate.of(2011, 3, 29), calendar.toGregorian(2011, 2, 25, false))
    }

    @Test
    fun nextLunarBirthdayFromAutumn2026IsAprilFirst2027() {
        // Regression: a fixed lunar date maps to a different Gregorian date in
        // 2011 and 2027, so the next-birthday date must come from the target
        // year, not from the stored birth-year Gregorian date.
        val profile = BirthProfile.Lunar(
            year = 2011,
            month = 2,
            day = 25,
            leapMonth = false,
            gregorianDate = LocalDate.of(2011, 3, 29),
        )

        val birthday = calendar.nextBirthday(
            birth = profile,
            now = java.time.Instant.parse("2026-11-14T01:48:36Z"),
            zone = java.time.ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(LocalDate.of(2027, 4, 1), birthday)
    }

    @Test
    fun monthListIncludesRealLengthAndLeapEntry() {
        val months = calendar.monthsInYear(2023)

        assertNotNull(months.firstOrNull { it.month == 2 && !it.leapMonth })
        assertNotNull(months.firstOrNull { it.month == 2 && it.leapMonth })
    }
}
