package com.meteor.memory.core.notify

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

object DailyReminderPolicy {
    fun shouldRemind(hasDiaryToday: Boolean): Boolean = !hasDiaryToday

    fun shouldRemind(
        hasDiaryToday: Boolean,
        currentTime: LocalTime,
        reminderTime: LocalTime,
    ): Boolean = shouldRemind(hasDiaryToday) && !currentTime.isBefore(reminderTime)

    fun nextTrigger(now: ZonedDateTime, reminderTime: LocalTime): ZonedDateTime {
        val todayAtReminder = LocalDateTime.of(now.toLocalDate(), reminderTime).atZone(now.zone)
        return if (todayAtReminder.isAfter(now)) {
            todayAtReminder
        } else {
            LocalDateTime.of(now.toLocalDate().plusDays(1), reminderTime).atZone(now.zone)
        }
    }
}
