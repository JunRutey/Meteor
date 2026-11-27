package com.meteor.memory.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reminder arithmetic for 每日一问 (ADR-068).
 *
 * Two rules carry the feature and are easy to break silently:
 * the default is *tomorrow at the same clock time*, and a consumed instant is
 * replaced rather than merely dropped — otherwise a snoozed alarm would be
 * scheduled but never re-armed.
 */
class QuestionReminderPolicyTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 27, 22, 40, 0, 0, zone)

    private fun instant(y: Int, m: Int, d: Int, h: Int, min: Int): Instant =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant()

    @Test
    fun defaultReminderIsTomorrowAtTheSameClockTime() {
        assertEquals(instant(2026, 11, 28, 22, 40), QuestionReminderPolicy.defaultReminder(now))
    }

    @Test
    fun snoozeOneHourMovesExactlyOneHour() {
        assertEquals(instant(2026, 11, 27, 23, 40), QuestionReminderPolicy.snoozeOneHour(now))
    }

    @Test
    fun snoozeTomorrowIsTheSameClockTimeNextDay() {
        assertEquals(instant(2026, 11, 28, 22, 40), QuestionReminderPolicy.snoozeTomorrow(now))
    }

    @Test
    fun nextPendingPicksTheEarliestFutureInstant() {
        val past = instant(2026, 11, 27, 20, 0)
        val soon = instant(2026, 11, 28, 6, 0)
        val later = instant(2026, 11, 29, 6, 0)

        assertEquals(soon, QuestionReminderPolicy.nextPending(listOf(later, past, soon), now.toInstant()))
        assertNull(QuestionReminderPolicy.nextPending(listOf(past), now.toInstant()))
    }

    @Test
    fun pruneExpiredDropsPastAndSorts() {
        val past = instant(2026, 11, 27, 20, 0)
        val later = instant(2026, 11, 29, 6, 0)
        val soon = instant(2026, 11, 28, 6, 0)

        assertEquals(
            listOf(soon, later),
            QuestionReminderPolicy.pruneExpired(listOf(later, past, soon), now.toInstant()),
        )
    }

    @Test
    fun replaceSwapsTheConsumedInstantForTheSnoozedOne() {
        val fired = instant(2026, 11, 28, 6, 0)
        val other = instant(2026, 11, 29, 6, 0)
        val replacement = instant(2026, 11, 28, 7, 0)

        assertEquals(
            listOf(replacement, other),
            QuestionReminderPolicy.replace(listOf(fired, other), fired, replacement),
        )
    }

    @Test
    fun replaceWithNullJustConsumes() {
        val fired = instant(2026, 11, 28, 6, 0)

        assertEquals(
            emptyList<Instant>(),
            QuestionReminderPolicy.replace(listOf(fired), fired, null),
        )
    }

    @Test
    fun atOrNullCombinesDateAndTimeButRefusesThePast() {
        assertEquals(
            instant(2026, 11, 28, 9, 0),
            QuestionReminderPolicy.atOrNull(
                date = now.toLocalDate().plusDays(1),
                time = java.time.LocalTime.of(9, 0),
                now = now,
            ),
        )
        assertNull(
            QuestionReminderPolicy.atOrNull(
                date = now.toLocalDate(),
                time = java.time.LocalTime.of(9, 0),
                now = now,
            ),
        )
    }
}
