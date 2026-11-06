package com.meteor.memory.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyReminderPolicyTest {
    @Test
    fun `reminds only when today's diary is absent`() {
        assertTrue(DailyReminderPolicy.shouldRemind(hasDiaryToday = false))
        assertFalse(DailyReminderPolicy.shouldRemind(hasDiaryToday = true))
    }

    @Test
    fun `does not notify before the configured local time`() {
        assertFalse(
            DailyReminderPolicy.shouldRemind(
                hasDiaryToday = false,
                currentTime = LocalTime.of(19, 59),
                reminderTime = LocalTime.of(20, 0),
            ),
        )
    }

    @Test
    fun `notifies at or after the configured local time when diary is absent`() {
        assertTrue(
            DailyReminderPolicy.shouldRemind(
                hasDiaryToday = false,
                currentTime = LocalTime.of(20, 0),
                reminderTime = LocalTime.of(20, 0),
            ),
        )
    }

    @Test
    fun `uses today's reminder time when it is still ahead`() {
        val now = ZonedDateTime.of(2026, 11, 7, 19, 59, 0, 0, ZoneId.of("Asia/Shanghai"))

        val next = DailyReminderPolicy.nextTrigger(now, LocalTime.of(20, 0))

        assertEquals(ZonedDateTime.of(2026, 11, 7, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai")), next)
    }

    @Test
    fun `moves to tomorrow when the reminder time has passed`() {
        val now = ZonedDateTime.of(2026, 11, 7, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

        val next = DailyReminderPolicy.nextTrigger(now, LocalTime.of(20, 0))

        assertEquals(ZonedDateTime.of(2026, 11, 8, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai")), next)
    }
}
