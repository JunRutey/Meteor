package com.meteor.memory.system

import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.notify.QuestionReminderPolicy
import com.meteor.memory.core.notify.QuestionReminderScheduler
import com.meteor.memory.core.notify.ReminderSchedulePrecision
import com.meteor.memory.core.storage.QuestionStore
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.ui.question.QuestionOpenRequest
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminder delivery for 每日一问 (ADR-068).
 *
 * The stored reminder list is the single source of truth: this controller never
 * keeps its own queue, it only translates "these instants exist in the archive"
 * into system alarms, and "this alarm fired" back into a notification plus an
 * updated instant list.
 *
 * Everything is `suspend` and safe to call from a broadcast receiver: the
 * receiver has a few seconds, so each method does its IO and returns.
 */
interface QuestionReminderController {

    /**
     * Re-arms every question's **earliest** pending reminder.
     *
     * Called on cold start, boot, package replace, time/timezone change and
     * whenever the notification master switch is turned back on. Alarms for
     * later reminders of the same question are not armed yet: each delivery
     * re-syncs and arms the next one, which keeps the alarm table tiny and
     * survives a device reboot without extra bookkeeping.
     */
    suspend fun syncAll()

    /** Re-arms exactly one question (after creating it, answering it, or editing its reminders). */
    suspend fun sync(questionId: String)

    /**
     * Handles a delivered alarm: posts the notification for
     * ([questionId], [trigger]) and drops [trigger] from the pending set.
     *
     * A stale alarm — the question was deleted, or the reminder was removed while
     * the alarm was in flight — is ignored without posting anything.
     */
    suspend fun handleAlarm(questionId: String, trigger: Instant)

    /**
     * Handles one notification action button: [trigger] is consumed and replaced
     * by the snoozed instant, then the notification is dismissed.
     */
    suspend fun snooze(questionId: String, trigger: Instant, kind: SnoozeKind)

    /** Drops every question alarm; used when the master notification switch goes off. */
    suspend fun cancelAll()

    /** Which action button of the reminder notification was tapped. */
    enum class SnoozeKind {
        /** 「延迟 1 小时」. */
        ONE_HOUR,

        /** 「明天再提醒」 — the same clock time tomorrow. */
        TOMORROW,
    }
}

/**
 * The [QuestionReminderController] implementation (ADR-068).
 *
 * The stored reminder list is the only state: [syncAll] / [sync] read it and arm
 * alarms, [handleAlarm] turns a fired alarm back into one delivered instant, and
 * [cancelAll] drops what the file says should no longer be armed. Nothing is
 * cached in memory, so a process death between "alarm fired" and "file rewritten"
 * costs the notification, never the reminder.
 *
 * `QuestionStore` is blocking by design; every caller (the broadcast receiver's
 * IO coroutine, the notification feature's IO scope) is already off the main
 * thread, so these methods never dispatch again.
 */
@Singleton
class AndroidQuestionReminderController @Inject constructor(
    private val questionStore: QuestionStore,
    private val scheduler: QuestionReminderScheduler,
    private val notificationFeature: NotificationFeature,
    private val settingsRepository: SettingsRepository,
    private val appLog: AppLog,
    /**
     * Injected on purpose, though delivery does not read it today: a notification
     * tap is routed by `MainActivity` via `ACTION_OPEN_QUESTION`, so posting needs
     * no navigation state.
     *
     * Keeping it in the constructor preserves the contract's dependency list and
     * leaves the one degradation path this class is expected to grow — the system
     * refusing to post (channel muted on some OEM builds), where the reminder is
     * instead recorded as a pending open request — without a Hilt graph change
     * and a diff across every call site.
     */
    @Suppress("unused")
    private val questionOpenRequest: QuestionOpenRequest,
) : QuestionReminderController {

    override suspend fun syncAll() {
        runCatching {
            val now = Instant.now()
            questionStore.list().forEach { question ->
                val next = question.nextReminder(now) ?: return@forEach
                arm(question.id, next)
            }
        }.onFailure { failure ->
            appLog.error(TAG, "Failed to sync question reminders", failure)
        }
    }

    override suspend fun sync(questionId: String) {
        runCatching {
            val question = questionStore.find(questionId) ?: return@runCatching
            val next = question.nextReminder(Instant.now()) ?: return@runCatching
            arm(questionId, next)
        }.onFailure { failure ->
            appLog.error(TAG, "Failed to sync question reminder for $questionId", failure)
        }
    }

    override suspend fun handleAlarm(questionId: String, trigger: Instant) {
        runCatching {
            // The reminder is *not* consumed while the switch is off: it has to
            // still be in the file when the switch comes back on, otherwise a
            // temporarily muted app would silently lose the reminder.
            if (!settingsRepository.settings.first().notificationsEnabled) {
                appLog.info(TAG, "Question reminder skipped: notifications are disabled")
                return@runCatching
            }
            if (!notificationFeature.canPostNotifications()) {
                appLog.warn(TAG, "Question reminder skipped: notifications are not allowed")
                return@runCatching
            }

            val question = questionStore.find(questionId)
            if (question == null) {
                appLog.warn(TAG, "Question reminder skipped: question $questionId no longer exists")
                return@runCatching
            }
            // Stale alarm: the question exists but this instant was removed or
            // already delivered. Both sides travel through epoch millis (the file
            // and the intent URI), so comparing the instants directly is exact.
            if (trigger !in question.reminders) {
                appLog.info(TAG, "Stale question alarm ignored: $trigger is not pending")
                return@runCatching
            }

            notificationFeature.showQuestionReminder(questionId, question.text, trigger)
            questionStore.updateReminders(
                questionId,
                QuestionReminderPolicy.replace(question.reminders, trigger, null),
            )
            // Only the earliest reminder is armed, so consuming this one leaves
            // the question with no alarm at all until we arm the next one.
            sync(questionId)
        }.onFailure { failure ->
            appLog.error(TAG, "Question reminder delivery failed for $questionId", failure)
        }
    }

    override suspend fun snooze(
        questionId: String,
        trigger: Instant,
        kind: QuestionReminderController.SnoozeKind,
    ) {
        runCatching {
            val now = ZonedDateTime.now()
            val replacement = when (kind) {
                QuestionReminderController.SnoozeKind.ONE_HOUR ->
                    QuestionReminderPolicy.snoozeOneHour(now)
                QuestionReminderController.SnoozeKind.TOMORROW ->
                    QuestionReminderPolicy.snoozeTomorrow(now)
            }

            val question = questionStore.find(questionId)
            if (question == null) {
                // Deleted (or unreadable) question: the only thing left to do is
                // take the notification away so it stops nagging.
                notificationFeature.hideQuestionReminder(questionId)
                return@runCatching
            }
            questionStore.updateReminders(
                questionId,
                QuestionReminderPolicy.replace(question.reminders, trigger, replacement),
            )
            sync(questionId)
            notificationFeature.hideQuestionReminder(questionId)
        }.onFailure { failure ->
            appLog.error(TAG, "Question reminder snooze failed for $questionId", failure)
        }
    }

    override suspend fun cancelAll() {
        runCatching {
            questionStore.list().forEach { question ->
                // Every instant, not just the earliest: the master switch being
                // off must leave no alarm behind, otherwise a later reminder of
                // the same question still fires at its old time.
                question.reminders.forEach { trigger ->
                    scheduler.cancel(question.id, trigger)
                }
            }
        }.onFailure { failure ->
            appLog.error(TAG, "Failed to cancel question reminders", failure)
        }
    }

    /** Arms one alarm and records the precision actually obtained. */
    private fun arm(questionId: String, trigger: Instant) {
        val precision = scheduler.schedule(questionId, trigger)
        val message = "Question reminder armed for $trigger with $precision precision"
        if (precision == ReminderSchedulePrecision.INEXACT) {
            appLog.warn(TAG, message)
        } else {
            appLog.info(TAG, message)
        }
    }

    private companion object {
        const val TAG = "QuestionReminder"
    }
}
