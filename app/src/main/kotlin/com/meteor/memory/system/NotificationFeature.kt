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
}
