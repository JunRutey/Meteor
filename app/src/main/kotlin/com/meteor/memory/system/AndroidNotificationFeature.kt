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
import com.meteor.memory.core.notify.QuestionReminderScheduler
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
import java.time.Instant
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
    /**
     * `dagger.Lazy`, not a plain parameter: `AndroidQuestionReminderController`
     * injects [NotificationFeature] to post and dismiss the reminder itself, so a
     * direct dependency here would close a constructor cycle
     * (`NotificationFeature → Controller → NotificationFeature`) that Hilt cannot
     * satisfy. It is resolved only inside [syncReminder] — never in the fields or
     * `init` — so construction order stays acyclic and the work only happens on
     * the IO coroutine that already owns it.
     */
    private val questionReminderController: dagger.Lazy<QuestionReminderController>,
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
            // 每日一问 alarms are armed separately from the daily reminder, so the
            // master switch must clear those too — a muted app must not keep
            // waking the device for questions.
            questionReminderController.get().cancelAll()
            appLog.info(TAG, "Notifications are disabled; pending alarm cancelled")
            return
        }

        // 每日一问 alarms live outside `scheduler`, so they are re-armed here as
        // well: this method is the one path every "reminders may need rebuilding"
        // signal goes through (master switch flips in either direction, boot,
        // TIME_SET, timezone change, package replace), and re-arming is
        // idempotent — the scheduler identifies an alarm by its data URI, so
        // scheduling a still-valid instant replaces itself in place.
        //
        // Deliberately before the daily-reminder channel gate below: questions have
        // their own channel, so muting the once-a-day diary nudge must not stop
        // question alarms from being armed.
        questionReminderController.get().syncAll()

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

    /**
     * 每日一问 reminder (ADR-068).
     *
     * Unlike the update notifications this posts on its own channel
     * ([NotificationChannels.QUESTIONS]) so muting the once-a-day diary nudge
     * never silences a reminder the person explicitly asked for.
     */
    @SuppressLint("MissingPermission")
    override fun showQuestionReminder(questionId: String, questionText: String, trigger: Instant) {
        // [notificationsEnabled] is the cached master switch: this is called from
        // the controller's IO coroutine and must not read DataStore synchronously.
        if (!notificationsEnabled) return
        if (!canPostNotifications()) return

        val flattened = flattenQuestionText(questionText)
        val body = context.getString(R.string.question_notification_body, shorten(flattened))
        val notification = NotificationCompat.Builder(context, NotificationChannels.QUESTIONS)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle(context.getString(R.string.question_notification_title))
            .setContentText(body)
            // The expanded view shows the whole question: the collapsed line above
            // is a preview, and hiding the rest behind "…" in the only place that
            // has room for it would defeat the point of expanding.
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.question_notification_body, flattened)),
            )
            .setContentIntent(openQuestionPendingIntent(questionId))
            .addAction(
                // Icon 0: the actions are text-only so they stay legible in the
                // collapsed notification and need no per-density drawables.
                0,
                context.getString(R.string.question_notification_snooze_hour),
                questionSnoozePendingIntent(
                    questionId,
                    trigger,
                    QuestionReminderReceiver.ACTION_QUESTION_SNOOZE_HOUR,
                    offset = QUESTION_INTENT_OFFSET_SNOOZE_HOUR,
                ),
            )
            .addAction(
                0,
                context.getString(R.string.question_notification_snooze_tomorrow),
                questionSnoozePendingIntent(
                    questionId,
                    trigger,
                    QuestionReminderReceiver.ACTION_QUESTION_SNOOZE_TOMORROW,
                    offset = QUESTION_INTENT_OFFSET_SNOOZE_TOMORROW,
                ),
            )
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(questionNotificationId(questionId), notification)
    }

    override fun hideQuestionReminder(questionId: String) {
        NotificationManagerCompat.from(context).cancel(questionNotificationId(questionId))
    }

    /**
     * One notification id per question, derived from its id.
     *
     * Two questions reminded at the same moment must stay two notifications;
     * a fixed shared id would let the second replace the first before it was even
     * read, and would make [hideQuestionReminder] dismiss the wrong one. The hash
     * is masked to 12 bits so the id stays inside the 4xxx band; a collision then
     * only means two questions share a slot, which is a rare cosmetic overlap, not
     * a lost reminder.
     */
    private fun questionNotificationId(questionId: String): Int =
        QUESTION_NOTIFICATION_BASE + (questionId.hashCode() and NOTIFICATION_ID_MASK)

    /**
     * The tap target: `MainActivity` reads [EXTRA_QUESTION_ID] and hands it to
     * `QuestionOpenRequest`.
     *
     * The request code **must** be derived from the question id. A PendingIntent
     * is identified by `Intent.filterEquals`, which ignores extras but compares
     * action, data and component — all three are identical for every question
     * here, so with one shared request code the second question's notification
     * would silently overwrite the first one's pending intent and tapping it
     * would open the wrong question.
     */
    private fun openQuestionPendingIntent(questionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_QUESTION)
            .putExtra(EXTRA_QUESTION_ID, questionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            questionRequestCode(questionId, QUESTION_INTENT_OFFSET_OPEN),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * One snooze button. The data URI is the (question, instant) identity the
     * receiver parses back out; the action says which button it was.
     */
    private fun questionSnoozePendingIntent(
        questionId: String,
        trigger: Instant,
        action: String,
        offset: Int,
    ): PendingIntent {
        val intent = Intent(context, QuestionReminderReceiver::class.java)
            .setAction(action)
            .setData(QuestionReminderScheduler.questionUri(questionId, trigger))
        return PendingIntent.getBroadcast(
            context,
            questionRequestCode(questionId, offset),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Request code for one of a question's three pending intents.
     *
     * `* INTENT_STRIDE + offset` rather than `+ offset`: with a plain sum, question
     * A's "open" (hash 5, offset 0) and question B's "snooze hour" (hash 4,
     * offset 1) would land on the same number and `FLAG_UPDATE_CURRENT` would let
     * one overwrite the other.
     */
    private fun questionRequestCode(questionId: String, offset: Int): Int =
        QUESTION_INTENT_BASE + (questionId.hashCode() and NOTIFICATION_ID_MASK) * INTENT_STRIDE +
            offset

    /**
     * Newlines cannot be rendered in a collapsed notification, so they become
     * spaces before any length decision is made — otherwise a question made of
     * short lines would look short yet still be clipped by the platform.
     */
    private fun flattenQuestionText(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ')

    /**
     * Collapsed-notification budget: a question can be 500 characters, and the
     * platform would cut that mid-word.
     */
    private fun shorten(text: String): String =
        if (text.length <= NOTIFICATION_TEXT_MAX_CHARS) {
            text
        } else {
            text.take(NOTIFICATION_TEXT_MAX_CHARS) + ELLIPSIS
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

    companion object {
        /**
         * Notification tap → open one question (ADR-068).
         *
         * Public because `MainActivity` reads it out of `Activity#getIntent()`:
         * the activity is the only place that can hand the question id to the
         * navigation graph, and it must do so without depending on this class's
         * instance.
         */
        const val ACTION_OPEN_QUESTION = "com.meteor.memory.action.OPEN_QUESTION"

        /** `questionId` extra carried by [ACTION_OPEN_QUESTION]. */
        const val EXTRA_QUESTION_ID = "question_id"

        private const val TAG = "AndroidNotification"
        private const val DAILY_REMINDER_NOTIFICATION_ID = 2020
        private const val DAILY_REMINDER_REQUEST_CODE = 2020
        private const val UPDATE_NOTIFICATION_ID = 2021
        private const val UPDATE_REQUEST_CODE = 2021

        /**
         * 每日一问 (ADR-068) uses the 4xxx band so it can never collide with the
         * daily reminder (2020) or the update notification (2021).
         */
        private const val QUESTION_NOTIFICATION_BASE = 4000

        /**
         * Request-code band for the question notification's pending intents,
         * kept far from the alarm scheduler's single 2030 slot.
         */
        private const val QUESTION_INTENT_BASE = 4100

        /** Offsets within [QUESTION_INTENT_BASE]; each pending intent needs its own. */
        private const val QUESTION_INTENT_OFFSET_OPEN = 0
        private const val QUESTION_INTENT_OFFSET_SNOOZE_HOUR = 1
        private const val QUESTION_INTENT_OFFSET_SNOOZE_TOMORROW = 2

        /**
         * Gap between two questions' request codes: must be larger than the
         * number of offsets, so `hash * STRIDE + offset` never overlaps.
         */
        private const val INTENT_STRIDE = 4

        /** 12 bits of the question id hash: keeps the id inside the 4xxx band. */
        private const val NOTIFICATION_ID_MASK = 0x0FFF

        /** Collapsed-notification budget; longer questions are expanded instead. */
        private const val NOTIFICATION_TEXT_MAX_CHARS = 120

        private const val ELLIPSIS = "…"
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

/**
 * 每日一问 reminder wiring (ADR-068).
 *
 * The scheduler is constructed here rather than injected as an `@Inject` type
 * because it needs the concrete receiver class and alarm action — the two things
 * a `core-notify` type must not know about `:app`.
 */
@Module
@InstallIn(SingletonComponent::class)
object QuestionReminderModule {
    @Provides
    @Singleton
    fun provideQuestionReminderScheduler(
        @ApplicationContext context: Context,
    ): QuestionReminderScheduler =
        QuestionReminderScheduler(
            context,
            QuestionReminderReceiver::class.java,
            QuestionReminderReceiver.ACTION_QUESTION_REMINDER,
        )
}

/**
 * Binding for [QuestionReminderController].
 *
 * Kept as its own `abstract class` module: [QuestionReminderModule] is an
 * `object` for the `@Provides` factory, and an `object` cannot hold `@Binds`
 * methods because those must be abstract.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QuestionReminderBinding {
    @Binds
    @Singleton
    abstract fun bindQuestionReminderController(
        impl: AndroidQuestionReminderController,
    ): QuestionReminderController
}
