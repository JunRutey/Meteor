package com.meteor.memory.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meteor.memory.MainActivity
import com.meteor.memory.R
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.notify.DailyReminderPolicy
import com.meteor.memory.core.notify.DailyReminderScheduler
import com.meteor.memory.core.notify.NotificationChannels
import com.meteor.memory.core.notify.ReminderSchedulePrecision
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNotificationFeature @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLog: AppLog,
    private val settingsRepository: SettingsRepository,
    private val diaryQueries: DiaryQueries,
    private val scheduler: DailyReminderScheduler,
) : NotificationFeature {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            appLog.error(TAG, "Notification background operation failed", failure)
        },
    )

    override val available: Boolean = true

    override fun initialize() {
        NotificationChannels.create(context)
        scope.launch { syncReminder() }
    }

    override fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED)

    override fun canPostDailyReminder(): Boolean {
        return canPostNotifications() && isDailyReminderChannelEnabled()
    }

    override fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExactAlarms()

    override fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannels.DAILY_REMINDER)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: ActivityNotFoundException) {
            appLog.warn(TAG, "Channel notification settings are unavailable", failure)
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (failure: ActivityNotFoundException) {
            appLog.warn(TAG, "Exact-alarm settings are unavailable; opening app settings", failure)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override suspend fun syncReminder() {
        val settings = settingsRepository.settings.first()
        if (!settings.diaryReminderEnabled) {
            scheduler.cancel()
            appLog.info(TAG, "Reminder is disabled; pending alarm cancelled")
            return
        }

        val notificationsAllowed = canPostNotifications()
        val reminderChannelAllowed = isDailyReminderChannelEnabled()
        if (!notificationsAllowed || !reminderChannelAllowed) {
            scheduler.cancel()
            appLog.warn(
                TAG,
                "Reminder scheduling skipped: notificationsAllowed=$notificationsAllowed, " +
                    "channelAllowed=$reminderChannelAllowed",
            )
            return
        }
        val hour = settings.diaryReminderTimeMinutes / 60
        val minute = settings.diaryReminderTimeMinutes % 60
        val now = ZonedDateTime.now()
        val reminderTime = LocalTime.of(hour, minute)
        val nextTrigger = DailyReminderPolicy.nextTrigger(now, reminderTime)
        val precision = scheduler.scheduleDaily(hour, minute, now)
        logReminderScheduled(nextTrigger, precision)
    }

    override suspend fun handleReminderAlarm() {
        val settings = settingsRepository.settings.first()
        if (!settings.diaryReminderEnabled) {
            scheduler.cancel()
            appLog.info(TAG, "Stale reminder alarm ignored because reminders are disabled")
            return
        }

        val notificationsAllowed = canPostNotifications()
        val reminderChannelAllowed = isDailyReminderChannelEnabled()
        if (!notificationsAllowed || !reminderChannelAllowed) {
            scheduler.cancel()
            appLog.warn(
                TAG,
                "Reminder alarm ignored: notificationsAllowed=$notificationsAllowed, " +
                    "channelAllowed=$reminderChannelAllowed",
            )
            return
        }

        val now = ZonedDateTime.now()
        val reminderTime = LocalTime.of(
            settings.diaryReminderTimeMinutes / 60,
            settings.diaryReminderTimeMinutes % 60,
        )
        val nextTrigger = DailyReminderPolicy.nextTrigger(now, reminderTime)
        val precision = scheduler.scheduleDaily(reminderTime.hour, reminderTime.minute, now)
        logReminderScheduled(nextTrigger, precision)

        val hasDiaryToday = now.toLocalDate() in diaryQueries.dates()
        val shouldRemind = DailyReminderPolicy.shouldRemind(
            hasDiaryToday,
            now.toLocalTime(),
            reminderTime,
        )
        if (!shouldRemind && hasDiaryToday) {
            appLog.info(TAG, "Reminder skipped because today's diary already exists")
            return
        }
        if (!shouldRemind) {
            appLog.warn(TAG, "Reminder alarm arrived before configured time: now=$now, reminderTime=$reminderTime")
            return
        }
        postDailyReminder()
    }

    private fun isDailyReminderChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannels.DAILY_REMINDER)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun logReminderScheduled(
        nextTrigger: ZonedDateTime,
        precision: ReminderSchedulePrecision,
    ) {
        val message = "Reminder scheduled for $nextTrigger with $precision precision"
        if (precision == ReminderSchedulePrecision.INEXACT) {
            appLog.warn(TAG, message)
        } else {
            appLog.info(TAG, message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun postDailyReminder() {
        val notification = NotificationCompat.Builder(context, NotificationChannels.DAILY_REMINDER)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(R.string.diary_reminder_notification_title))
            .setContentText(context.getString(R.string.diary_reminder_notification_body))
            .setContentIntent(openAppPendingIntent(DAILY_REMINDER_REQUEST_CODE))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(DAILY_REMINDER_NOTIFICATION_ID, notification)
        appLog.info(TAG, "Daily reminder notification posted")
    }

    @SuppressLint("MissingPermission")
    override fun showUpdateDownload(progress: UpdateNotificationProgress) {
        if (!canPostNotifications()) return

        val notification = NotificationCompat.Builder(context, NotificationChannels.UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                context.getString(
                    R.string.update_notification_downloading_title,
                    progress.versionName,
                ),
            )
            .setContentText(
                if (progress.totalBytes > 0) {
                    context.getString(R.string.update_notification_progress, progress.percent)
                } else {
                    context.getString(R.string.update_notification_downloading_body)
                },
            )
            .setContentIntent(openAppPendingIntent(UPDATE_REQUEST_CODE))
            .setProgress(100, progress.percent, progress.totalBytes <= 0)
            .setOnlyAlertOnce(true)
            // Not ongoing: if the process dies mid-download, a stuck
            // "downloading" notification must still be swipeable.
            .setSilent(true)
            .build()

        NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    override fun showUpdateReady(versionName: String) {
        if (!canPostNotifications()) return

        val notification = NotificationCompat.Builder(context, NotificationChannels.UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_ready_title, versionName))
            .setContentText(context.getString(R.string.update_notification_ready_body))
            .setContentIntent(openAppPendingIntent(UPDATE_REQUEST_CODE))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
    }

    override fun hideUpdateNotification() {
        NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID)
    }

    private fun openAppPendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val TAG = "AndroidNotification"
        const val DAILY_REMINDER_NOTIFICATION_ID = 2020
        const val DAILY_REMINDER_REQUEST_CODE = 2020
        const val UPDATE_NOTIFICATION_ID = 2021
        const val UPDATE_REQUEST_CODE = 2021
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationFeatureBinding {
    @Binds
    @Singleton
    abstract fun bindNotificationFeature(feature: AndroidNotificationFeature): NotificationFeature
}

@Module
@InstallIn(SingletonComponent::class)
object NotificationSchedulerModule {
    @Provides
    @Singleton
    fun provideDailyReminderScheduler(
        @ApplicationContext context: Context,
    ): DailyReminderScheduler =
        DailyReminderScheduler(
            context,
            DailyReminderReceiver::class.java,
            DailyReminderReceiver.ACTION_DAILY_REMINDER,
        )
}
