package com.meteor.memory.feature.question

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The two edges of 每日一问's reminder arithmetic that are easy to get wrong
 * (ADR-068): the default must be *tomorrow at the same clock time*, and a picked
 * instant that is not in the future must be refused rather than accepted (an
 * alarm in the past fires immediately, delivering a notification the person just
 * configured for later).
 */
class QuestionScheduleTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** A fixed "now" so every assertion is independent of when the suite runs. */
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 27, 22, 40, 0, 0, zone)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): ZonedDateTime =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    @Test
    fun defaultReminderIsTomorrowAtTheSameClockTime() {
        assertEquals(at(2026, 11, 28, 22, 40).toInstant(), QuestionSchedule.defaultReminder(now))
    }

    @Test
    fun aNewQuestionStartsWithExactlyTheDefault() {
        assertEquals(
            listOf(QuestionSchedule.defaultReminder(now)),
            QuestionSchedule.initialReminders(now),
        )
    }

    @Test
    fun addDefaultIsIdempotentPerInstantAndSorted() {
        val reminders = QuestionSchedule.addDefault(emptyList(), now)
        assertEquals(1, reminders.size)

        // Adding the same default again collapses: two alarms at one instant would
        // produce one notification, so the duplicate has no meaning.
        assertEquals(reminders, QuestionSchedule.addDefault(reminders, now))

        val later = QuestionSchedule.addAt(
            reminders = reminders,
            date = LocalDate.of(2026, 11, 30),
            time = LocalTime.of(9, 0),
            now = now,
        )
        assertTrue(later!!.isNotEmpty())
        assertEquals(later.sorted(), later)
    }

    @Test
    fun addDefaultStopsAtTheLimit() {
        val full = List(QuestionSchedule.MAX_REMINDERS) { index ->
            now.plusHours(index + 1L).toInstant()
        }
        assertEquals(full, QuestionSchedule.addDefault(full, now))
    }

    @Test
    fun pickInTheFutureIsAccepted() {
        val accepted = QuestionSchedule.addAt(
            reminders = emptyList(),
            date = LocalDate.of(2026, 11, 28),
            time = LocalTime.of(9, 0),
            now = now,
        )
        assertEquals(listOf(at(2026, 11, 28, 9, 0).toInstant()), accepted)
    }

    @Test
    fun pickInThePastIsRefused() {
        assertNull(
            QuestionSchedule.addAt(
                reminders = emptyList(),
                date = LocalDate.of(2026, 11, 27),
                time = LocalTime.of(9, 0),
                now = now,
            ),
        )
    }

    @Test
    fun pickAtExactlyNowIsRefused() {
        // "Not after now" is expired: an alarm armed for the current minute can
        // only fire immediately, which is never what the picker promised.
        assertNull(
            QuestionSchedule.addAt(
                reminders = emptyList(),
                date = LocalDate.of(2026, 11, 27),
                time = LocalTime.of(22, 40),
                now = now,
            ),
        )
    }

    @Test
    fun pickIsRefusedWhenTheSetIsFull() {
        val full = List(QuestionSchedule.MAX_REMINDERS) { index ->
            now.plusHours(index + 1L).toInstant()
        }
        assertNull(
            QuestionSchedule.addAt(
                reminders = full,
                date = LocalDate.of(2027, 1, 1),
                time = LocalTime.of(9, 0),
                now = now,
            ),
        )
    }

    @Test
    fun pendingDropsExpiredInstants() {
        val expired = now.minusHours(1).toInstant()
        val future = now.plusHours(1).toInstant()

        assertEquals(listOf(future), QuestionSchedule.pending(listOf(expired, future), now))
    }

    @Test
    fun removeDropsOnlyTheTarget() {
        val first = now.plusHours(1).toInstant()
        val second = now.plusHours(2).toInstant()
        assertEquals(listOf(second), QuestionSchedule.remove(listOf(first, second), first))
    }
}
