package com.meteor.memory.system

interface NotificationFeature {
    val available: Boolean

    fun initialize()
    fun canPostNotifications(): Boolean
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
