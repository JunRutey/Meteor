package com.meteor.memory.core.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalTime
import java.time.ZonedDateTime

enum class ReminderSchedulePrecision {
    EXACT,
    INEXACT,
}

class DailyReminderScheduler(
    context: Context,
    receiverClass: Class<out BroadcastReceiver>,
    action: String,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val alarmIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, receiverClass).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleDaily(
        hour: Int,
        minute: Int,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ReminderSchedulePrecision {
        val next = DailyReminderPolicy.nextTrigger(now, LocalTime.of(hour, minute))
        val triggerAtMillis = next.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmIntent)
            return ReminderSchedulePrecision.INEXACT
        }

        if (canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    alarmIntent,
                )
                return ReminderSchedulePrecision.EXACT
            } catch (_: SecurityException) {
                // Permission can be revoked between checking and scheduling.
            }
        }

        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmIntent)
        return ReminderSchedulePrecision.INEXACT
    }

    fun cancel() {
        alarmManager.cancel(alarmIntent)
    }

    private companion object {
        const val REQUEST_CODE = 2020
    }
}
