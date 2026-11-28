package com.meteor.memory.system

interface NotificationFeature {
    fun initialize()
    fun canPostNotifications(): Boolean
    fun canPostDailyReminder(): Boolean
    /**
     * Whether a 每日一问 reminder can actually become visible: notification
     * permission plus the 每日一问 system channel not being muted. Delivery
     * checks this before consuming a pending instant, so a muted channel does
     * not silently eat the reminder.
     */
    fun canPostQuestionReminder(): Boolean
    fun canScheduleExactAlarms(): Boolean
    fun openNotificationSettings()
    fun openExactAlarmSettings()
    suspend fun syncReminder()
    suspend fun handleReminderAlarm()

    /**
     * A silent background download must still be visible: the notification
     * shows live progress and tells the user an update is on its way. All three
     * update methods are no-ops when notification support is unavailable.
     */
    fun showUpdateDownload(progress: UpdateNotificationProgress)
    fun showUpdateReady(versionName: String)
    fun hideUpdateNotification()

    /**
     * 每日一问 reminder (ADR-068): one notification per (question, reminder
     * instant), carrying the two snooze actions.
     *
     * [trigger] is passed through purely as identity: it must match one of the
     * question's pending reminders so the controller can consume exactly that
     * instant, and it is also encoded in the action buttons' data URIs.
     */
    fun showQuestionReminder(questionId: String, questionText: String, trigger: java.time.Instant)

    /**
     * Dismisses the reminder for [questionId].
     *
     * Only needs the question id because a question has at most one visible
     * reminder at a time (the earliest pending instant), and the notification id
     * is derived from it.
     */
    fun hideQuestionReminder(questionId: String)
}

/**
 * Byte counters for the update progress notification.
 *
 * [totalBytes] is 0 when the server did not send a content length; the
 * notification then stays indeterminate instead of inventing a percentage.
 */
data class UpdateNotificationProgress(
    val versionName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes > 0) {
            ((downloadedBytes.coerceAtLeast(0) * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}
