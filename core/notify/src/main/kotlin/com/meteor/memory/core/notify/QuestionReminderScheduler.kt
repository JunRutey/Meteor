package com.meteor.memory.core.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.Instant

/**
 * One-shot alarms for 每日一问, one per (question, reminder instant) (ADR-068).
 *
 * A question may carry several reminders, so this cannot be the single-slot
 * scheduler `DailyReminderScheduler` is. Two properties make the multi-alarm
 * version safe without any extra bookkeeping:
 *
 * 1. **The alarm identity is the data URI**, not the request code. Every
 *    PendingIntent shares one request code and carries
 *    `meteor://question/<id>/<epochMillis>`; `Intent.filterEquals` includes the
 *    data, so two reminders of the same question are two distinct PendingIntents
 *    and can be armed and cancelled independently. A hash-based request code
 *    would have collided eventually and silently replaced a neighbour's alarm.
 * 2. **Cancelling is exact.** Because the identity is reconstructible from the
 *    stored reminder list, [cancel] never needs a registry: the caller cancels
 *    the instants it is about to drop.
 */
class QuestionReminderScheduler(
    context: Context,
    private val receiverClass: Class<out BroadcastReceiver>,
    private val action: String,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * Arms the one-shot alarm for [questionId] at [trigger].
     *
     * Returns the precision actually obtained: on a device without the exact-alarm
     * grant the alarm still exists, it may just arrive later, and the caller logs
     * that instead of pretending it was exact.
     */
    fun schedule(
        questionId: String,
        trigger: Instant,
    ): ReminderSchedulePrecision {
        val pending = pendingIntent(questionId, trigger)
        val triggerAtMillis = trigger.toEpochMilli()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            return ReminderSchedulePrecision.INEXACT
        }

        if (canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending,
                )
                return ReminderSchedulePrecision.EXACT
            } catch (_: SecurityException) {
                // The grant can be revoked between the check and the call.
            }
        }

        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        return ReminderSchedulePrecision.INEXACT
    }

    /** Drops the alarm for exactly this (question, instant) pair. */
    fun cancel(questionId: String, trigger: Instant) {
        alarmManager.cancel(pendingIntent(questionId, trigger))
    }

    private fun pendingIntent(questionId: String, trigger: Instant): PendingIntent {
        val intent = Intent(appContext, receiverClass)
            .setAction(action)
            .setData(questionUri(questionId, trigger))
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Shared by every question alarm; the data URI is what distinguishes them. */
        private const val REQUEST_CODE = 2030

        private const val SCHEME = "meteor"
        private const val HOST = "question-reminder"

        fun questionUri(questionId: String, trigger: Instant): Uri =
            Uri.parse("$SCHEME://$HOST/$questionId/${trigger.toEpochMilli()}")
    }
}
