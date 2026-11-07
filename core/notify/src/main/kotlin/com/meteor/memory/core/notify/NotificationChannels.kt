package com.meteor.memory.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val DAILY_REMINDER = "meteor.daily_reminder"
    const val AI_MESSAGES = "meteor.ai_messages"
    /** Low importance: download progress must never ring or vibrate. */
    const val UPDATES = "meteor.updates"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channels = listOf(
            NotificationChannel(
                DAILY_REMINDER,
                context.getString(R.string.notification_channel_daily_reminder_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_daily_reminder_description)
            },
            NotificationChannel(
                AI_MESSAGES,
                context.getString(R.string.notification_channel_ai_messages_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_ai_messages_description)
            },
            NotificationChannel(
                UPDATES,
                context.getString(R.string.notification_channel_update_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_update_description)
                setShowBadge(false)
            },
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(channels)
    }
}
