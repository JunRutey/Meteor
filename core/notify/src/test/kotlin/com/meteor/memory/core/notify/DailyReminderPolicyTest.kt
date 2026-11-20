package com.meteor.memory.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyReminderPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, zone)

    @Test
    fun `before the window picks a minute inside today's window`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 7, 30), false) { 0 }

        assertEquals(at(7, 8, 0), next)
    }

    @Test
    fun `before the window can pick the last minute of the window`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 7, 30), false) { 59 }

        assertEquals(at(7, 8, 59), next)
    }

    @Test
    fun `inside the window picks a whole minute after now`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 8, 30, 15), false) { 0 }

        assertEquals(at(7, 8, 31), next)
    }

    @Test
    fun `inside the window stays before the window end`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 8, 30, 15), false) { 28 }

        assertEquals(at(7, 8, 59), next)
    }

    @Test
    fun `inside the final minute falls back to the window end`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 8, 59, 30), false) { 0 }

        assertEquals(at(7, 9, 0), next)
    }

    @Test
    fun `after the window schedules tomorrow`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 9, 0), false) { 0 }

        assertEquals(at(8, 8, 0), next)
    }

    @Test
    fun `already sent today schedules tomorrow even before the window`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 7, 30), true) { 10 }

        assertEquals(at(8, 8, 10), next)
    }

    @Test
    fun `already sent today schedules tomorrow after the window`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 12, 0), true) { 59 }

        assertEquals(at(8, 8, 59), next)
    }

    @Test
    fun `late evening schedules tomorrow`() {
        val next = DailyReminderPolicy.nextTrigger(at(7, 23, 30), false) { 30 }

        assertEquals(at(8, 8, 30), next)
    }

    @Test
    fun `random source is asked for the matching bound`() {
        val bounds = mutableListOf<Int>()

        DailyReminderPolicy.nextTrigger(at(7, 7, 30), false) { bound ->
            bounds += bound
            bound - 1
        }
        DailyReminderPolicy.nextTrigger(at(7, 8, 30), false) { bound ->
            bounds += bound
            bound - 1
        }
        DailyReminderPolicy.nextTrigger(at(7, 10, 0), false) { bound ->
            bounds += bound
            bound - 1
        }

        assertEquals(listOf(60, 29, 60), bounds)
    }

    @Test
    fun `hours left today is truncated to whole hours`() {
        assertEquals(16, DailyReminderPolicy.hoursUntilEndOfDay(at(7, 8, 0)))
        assertEquals(15, DailyReminderPolicy.hoursUntilEndOfDay(at(7, 8, 30)))
        assertEquals(15, DailyReminderPolicy.hoursUntilEndOfDay(at(7, 8, 59)))
        assertEquals(15, DailyReminderPolicy.hoursUntilEndOfDay(at(7, 9, 0)))
        assertEquals(0, DailyReminderPolicy.hoursUntilEndOfDay(at(7, 23, 30)))
    }

    @Test
    fun `window definition matches the requested hour`() {
        assertEquals(8, DailyReminderPolicy.WINDOW_START.hour)
        assertEquals(9, DailyReminderPolicy.WINDOW_END.hour)
        assertTrue(DailyReminderPolicy.WINDOW_MINUTES == 60)
    }
}
