package com.meteor.memory.core.notify

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure reminder arithmetic for 每日一问 (ADR-068).
 *
 * No clock and no alarm manager: every entry point takes the "now" it should
 * reason about, so the whole file is unit-testable. The persisted reminder list
 * is owned by `core-storage`; this object only answers "when".
 */
object QuestionReminderPolicy {

    /**
     * Default reminder when the person does not pick a time: **the same clock
     * time tomorrow**.
     *
     * The alternative (a fixed 09:00) was rejected because it decouples the
     * reminder from the moment the thought arrived: someone who writes at 22:40
     * is a night thinker, and a morning ping reads as an unrelated alarm.
     */
    fun defaultReminder(now: ZonedDateTime): Instant =
        now.plusDays(1).toInstant()

    /** "1 小时后再说": the snooze the notification offers. */
    fun snoozeOneHour(now: ZonedDateTime): Instant = now.plusHours(1).toInstant()

    /** "明天再提醒我": the same clock time tomorrow, like the default. */
    fun snoozeTomorrow(now: ZonedDateTime): Instant = now.plusDays(1).toInstant()

    /** The earliest instant in [reminders] that is still ahead of [now]. */
    fun nextPending(reminders: List<Instant>, now: Instant): Instant? =
        reminders.filter { it.isAfter(now) }.minOrNull()

    /** Drops instants that are already in the past. */
    fun pruneExpired(reminders: List<Instant>, now: Instant): List<Instant> =
        reminders.filter { it.isAfter(now) }.distinct().sorted()

    /**
     * Replaces the reminder at [consumed] with [replacement], i.e. "this alarm
     * fired / was snoozed, put the next one there".
     *
     * The consumed instant is dropped so a re-sync can never re-arm an alarm that
     * already delivered its notification.
     */
    fun replace(
        reminders: List<Instant>,
        consumed: Instant,
        replacement: Instant?,
    ): List<Instant> {
        val remaining = reminders.filter { it != consumed }
        val withReplacement = if (replacement == null) remaining else remaining + replacement
        return withReplacement.distinct().sorted()
    }

    /**
     * Combines a picked local date and time into an instant.
     *
     * Returns null for an instant that is not in the future: scheduling an alarm
     * in the past fires it immediately, which would deliver a notification the
     * person just configured for later.
     */
    fun atOrNull(
        date: LocalDate,
        time: LocalTime,
        now: ZonedDateTime,
    ): Instant? {
        val candidate = date.atTime(time).atZone(now.zone).toInstant()
        return candidate.takeIf { it.isAfter(now.toInstant()) }
    }

    /** Local wall-clock rendering of one reminder instant, minute precision. */
    fun localDateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        instant.atZone(zone)
}
