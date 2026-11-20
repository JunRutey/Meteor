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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class AndroidNotificationFeature @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLog: AppLog,
    private val settingsRepository: SettingsRepository,
    private val scheduler: DailyReminderScheduler,
) : NotificationFeature {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            appLog.error(TAG, "Notification background operation failed", failure)
        },
    )

    /**
     * Cached master switch (ADR-039). [showUpdateDownload] and [showUpdateReady]
     * are non-suspend and may run on the download coroutine, so they must not
     * read DataStore synchronously; the settings flow keeps this current.
     */
    @Volatile
    private var notificationsEnabled: Boolean = true

    override fun initialize() {
        NotificationChannels.create(context)
        scope.launch {
            settingsRepository.settings
                .map { it.notificationsEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    notificationsEnabled = enabled
                    if (!enabled) hideUpdateNotification()
                    syncReminder()
                }
        }
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
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (failure: ActivityNotFoundException) {
            appLog.warn(TAG, "Notification settings are unavailable; opening app settings", failure)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
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
        notificationsEnabled = settings.notificationsEnabled
        if (!settings.notificationsEnabled) {
            scheduler.cancel()
            hideUpdateNotification()
            appLog.info(TAG, "Notifications are disabled; pending alarm cancelled")
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

        val now = ZonedDateTime.now()
        val alreadySentToday = settingsRepository.reminderLastSentDate() == now.toLocalDate()
        val nextTrigger = DailyReminderPolicy.nextTrigger(now, alreadySentToday) { bound ->
            randomMinute(bound)
        }
        val precision = scheduler.scheduleAt(nextTrigger)
        logReminderScheduled(nextTrigger, precision)
    }

    override suspend fun handleReminderAlarm() {
        val settings = settingsRepository.settings.first()
        notificationsEnabled = settings.notificationsEnabled
        if (!settings.notificationsEnabled) {
            scheduler.cancel()
            hideUpdateNotification()
            appLog.info(TAG, "Stale reminder alarm ignored because notifications are disabled")
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
        val today = now.toLocalDate()
        val alreadySentToday = settingsRepository.reminderLastSentDate() == today
        val insideWindow = !now.toLocalTime().isBefore(DailyReminderPolicy.WINDOW_START)

        val posted = when {
            alreadySentToday -> {
                appLog.info(TAG, "Reminder skipped because today's notification was already sent")
                false
            }
            !insideWindow -> {
                // A time or timezone change can deliver the alarm earlier than
                // intended; keep the day free and wait for the real window.
                appLog.warn(TAG, "Reminder alarm arrived before the window: now=$now")
                false
            }
            else -> runCatching {
                postDailyReminder(DailyReminderPolicy.hoursUntilEndOfDay(now))
            }.onSuccess {
                appLog.info(TAG, "Daily reminder notification posted")
            }.onFailure { failure ->
                appLog.error(TAG, "Daily reminder notification failed", failure)
            }.isSuccess
        }
        if (posted) settingsRepository.setReminderLastSentDate(today)

        // A delivered reminder moves the next one to tomorrow; an early alarm
        // keeps today's window as the target.
        val nextTrigger = DailyReminderPolicy.nextTrigger(now, alreadySentToday || posted) { bound ->
            randomMinute(bound)
        }
        val precision = scheduler.scheduleAt(nextTrigger)
        logReminderScheduled(nextTrigger, precision)
    }

    private fun isDailyReminderChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannels.DAILY_REMINDER)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun randomMinute(bound: Int): Int = if (bound <= 0) 0 else Random.nextInt(bound)

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
    private fun postDailyReminder(hoursLeftToday: Int) {
        val title = context.getString(R.string.diary_reminder_notification_title)
        val body = context.getString(R.string.diary_reminder_notification_body, hoursLeftToday)
        val notification = NotificationCompat.Builder(context, NotificationChannels.DAILY_REMINDER)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppPendingIntent(DAILY_REMINDER_REQUEST_CODE))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(DAILY_REMINDER_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    override fun showUpdateDownload(progress: UpdateNotificationProgress) {
        if (!notificationsEnabled) {
            hideUpdateNotification()
            return
        }
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
        if (!notificationsEnabled) {
            hideUpdateNotification()
            return
        }
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
