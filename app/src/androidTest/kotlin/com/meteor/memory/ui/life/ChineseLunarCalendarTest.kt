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

    @Test
    fun `2024 lunar new year converts to February tenth`() {
        assertEquals(LocalDate.of(2024, 2, 10), calendar.toGregorian(2024, 1, 1, false))
    }

    @Test
    fun `2023 leap second month converts correctly`() {
        assertEquals(LocalDate.of(2023, 3, 22), calendar.toGregorian(2023, 2, 1, true))
        assertEquals(LocalDate.of(2023, 4, 19), calendar.toGregorian(2023, 2, 29, true))
        assertNull(calendar.toGregorian(2023, 2, 30, true))
    }

    @Test
    fun `invalid leap month selection is rejected`() {
        assertNull(calendar.toGregorian(2024, 2, 1, true))
    }

    @Test
    fun `leap birthday falls back to same regular month in a year without that leap month`() {
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
    fun `month list includes its real length and leap entry`() {
        val months = calendar.monthsInYear(2023)

        assertNotNull(months.firstOrNull { it.month == 2 && !it.leapMonth })
        assertNotNull(months.firstOrNull { it.month == 2 && it.leapMonth })
    }
}
