package com.meteor.memory.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * Notification channels.
 *
 * 1.7.3 moves the two person-facing reminders to `*.v2` channels created with
 * [NotificationManager.IMPORTANCE_HIGH] and the system default notification
 * sound: on Android 8+ a channel's importance cannot be raised after the app
 * created it, so an existing install would otherwise keep the old "no heads-up
 * and no sound" channel forever. The old channel is migrated once (its muted
 * state is carried over) and then deleted so the system settings list does not
 * fill up with duplicates.
 */
object NotificationChannels {
    const val DAILY_REMINDER = "meteor.daily_reminder.v2"
    const val AI_MESSAGES = "meteor.ai_messages"
    /** Low importance: download progress must never ring or vibrate. */
    const val UPDATES = "meteor.updates"
    /**
     * 每日一问 (ADR-068): a person-configured reminder, so it is its own channel.
     * Keeping it apart from [DAILY_REMINDER] means muting the once-a-day diary
     * nudge never silences the question they explicitly scheduled.
     */
    const val QUESTIONS = "meteor.questions.v2"

    private const val LEGACY_DAILY_REMINDER = "meteor.daily_reminder"
    private const val LEGACY_QUESTIONS = "meteor.questions"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        createReminderChannel(
            manager = manager,
            channelId = DAILY_REMINDER,
            legacyChannelId = LEGACY_DAILY_REMINDER,
            name = context.getString(R.string.notification_channel_daily_reminder_name),
            description = context.getString(R.string.notification_channel_daily_reminder_description),
        )
        createReminderChannel(
            manager = manager,
            channelId = QUESTIONS,
            legacyChannelId = LEGACY_QUESTIONS,
            name = context.getString(R.string.notification_channel_questions_name),
            description = context.getString(R.string.notification_channel_questions_description),
        )

        manager.createNotificationChannels(
            listOf(
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
            ),
        )
    }

    /**
     * Creates the high-importance reminder channel the first time, then drops
     * the legacy channel. A person who had muted the old channel keeps it muted
     * on the new one; everyone else gets the heads-up + default ringtone that
     * 1.7.3 promises.
     */
    private fun createReminderChannel(
        manager: NotificationManager,
        channelId: String,
        legacyChannelId: String,
        name: String,
        description: String,
    ) {
        if (manager.getNotificationChannel(channelId) == null) {
            val legacyMuted = manager.getNotificationChannel(legacyChannelId)?.importance ==
                NotificationManager.IMPORTANCE_NONE
            val importance = if (legacyMuted) {
                NotificationManager.IMPORTANCE_NONE
            } else {
                NotificationManager.IMPORTANCE_HIGH
            }
            val channel = NotificationChannel(channelId, name, importance).apply {
                this.description = description
                if (!legacyMuted) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { sound ->
                        setSound(
                            sound,
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        )
                    }
                    enableVibration(true)
                }
            }
            manager.createNotificationChannel(channel)
        }
        // Safe on a fresh install (nothing to delete) and idempotent otherwise.
        manager.deleteNotificationChannel(legacyChannelId)
    }
}
