package com.meteor.memory.core.notify

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure scheduling logic for the once-a-day diary reminder (ADR-039).
 *
 * The reminder is sent once per local day at a random instant inside
 * [WINDOW_START]..[WINDOW_END). "Already sent today" is supplied by the caller
 * because the date is persisted in DataStore, not here.
 *
 * The window is in the evening (20:00–21:00) so the reminder reaches the user
 * when they are about to close the day, not in the morning (ADR-039 修订).
 *
 * Randomness is injected as a function returning a value in `0 until bound`, so
 * every branch is unit-testable without touching the global random source.
 */
object DailyReminderPolicy {
    val WINDOW_START: LocalTime = LocalTime.of(20, 0)
    val WINDOW_END: LocalTime = LocalTime.of(21, 0)

    /** Whole minutes available in the window: 20:00 through 20:59. */
    const val WINDOW_MINUTES = 60

    /**
     * Picks the next reminder instant in [WINDOW_START]..[WINDOW_END).
     *
     * - before the window: any minute of today's window;
     * - inside the window and not sent yet: a whole minute after [now] and
     *   before the window end;
     * - after the window or already sent today: a minute in tomorrow's window.
     *
     * [randomMinute] is called with the exclusive upper bound and must return a
     * value in `0 until bound`. Out-of-contract values are clamped.
     */
    fun nextTrigger(
        now: ZonedDateTime,
        alreadySentToday: Boolean,
        randomMinute: (Int) -> Int,
    ): ZonedDateTime {
        if (!alreadySentToday) {
            val windowStart = at(now.toLocalDate(), WINDOW_START, now)
            val windowEnd = at(now.toLocalDate(), WINDOW_END, now)
            if (now.isBefore(windowStart)) {
                return windowStart.plusMinutes(pick(WINDOW_MINUTES, randomMinute).toLong())
            }
            if (now.isBefore(windowEnd)) {
                // Keep the trigger minute-aligned; a minute that already passed
                // would fire immediately and then be marked as sent for the day.
                val firstWholeMinute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                val availableMinutes =
                    Duration.between(firstWholeMinute, windowEnd).toMinutes().toInt()
                if (availableMinutes <= 0) return windowEnd
                return firstWholeMinute.plusMinutes(pick(availableMinutes, randomMinute).toLong())
            }
        }
        val tomorrow = now.toLocalDate().plusDays(1)
        return at(tomorrow, WINDOW_START, now)
            .plusMinutes(pick(WINDOW_MINUTES, randomMinute).toLong())
    }

    /**
     * Whole hours from [at] to the next local midnight.
     *
     * The notification copy uses this as "hours left today". The value is
     * truncated: 20:30 -> 3.
     */
    fun hoursUntilEndOfDay(at: ZonedDateTime): Int {
        val endOfDay = at.toLocalDate().plusDays(1).atStartOfDay(at.zone)
        return Duration.between(at, endOfDay).toHours().toInt().coerceAtLeast(0)
    }

    private fun pick(bound: Int, randomMinute: (Int) -> Int): Int {
        if (bound <= 0) return 0
        return randomMinute(bound).coerceIn(0, bound - 1)
    }

    private fun at(date: LocalDate, time: LocalTime, now: ZonedDateTime): ZonedDateTime =
        date.atTime(time).atZone(now.zone)
}
