package com.meteor.memory.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meteor.memory.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Alarm and notification-action entry point for 每日一问 (ADR-068).
 *
 * One receiver serves three different purposes because all three carry the same
 * `meteor://question-reminder/<id>/<epochMillis>` data URI
 * (`QuestionReminderScheduler.questionUri`): a fired alarm, the
 * 「延迟 1 小时」 button and the 「明天再提醒」 button. The data URI is what tells
 * them apart — the question id and the instant being acted on must survive the
 * trip through the system, and an extra in extras would be lost on some OEM
 * builds that rewrite the intent.
 *
 * Everything else (boot, time/timezone change, package replace, exact-alarm
 * permission change) is a "the world moved" signal: those only need a full
 * re-sync, which also re-registers the alarms a reboot destroyed.
 */
@AndroidEntryPoint
class QuestionReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: QuestionReminderController

    @Inject
    lateinit var appLog: AppLog

    override fun onReceive(context: Context, intent: Intent) {
        appLog.info(TAG, "Received broadcast action=${intent.action}")
        val pendingResult = goAsync()
        val exceptionHandler = CoroutineExceptionHandler { _, failure ->
            appLog.error(TAG, "Question reminder broadcast processing failed", failure)
        }
        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            try {
                dispatch(intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatch(intent: Intent) {
        when (intent.action) {
            ACTION_QUESTION_REMINDER -> {
                val target = parseTarget(intent) ?: return
                controller.handleAlarm(target.first, target.second)
            }
            ACTION_QUESTION_SNOOZE_HOUR -> {
                val target = parseTarget(intent) ?: return
                controller.snooze(
                    target.first,
                    target.second,
                    QuestionReminderController.SnoozeKind.ONE_HOUR,
                )
            }
            ACTION_QUESTION_SNOOZE_TOMORROW -> {
                val target = parseTarget(intent) ?: return
                controller.snooze(
                    target.first,
                    target.second,
                    QuestionReminderController.SnoozeKind.TOMORROW,
                )
            }
            // Boot, time set, timezone change, package replace, exact-alarm
            // permission change. Every alarm may be gone or wrong by now, so
            // rebuild them from the stored reminder lists.
            else -> controller.syncAll()
        }
    }

    /**
     * Splits `meteor://question-reminder/<questionId>/<epochMillis>`.
     *
     * A malformed or missing URI means the intent was not built by this app (or
     * was mangled in flight). There is nothing safe to act on, so it is logged
     * and dropped rather than guessed at.
     */
    private fun parseTarget(intent: Intent): Pair<String, Instant>? {
        val segments = intent.data?.pathSegments
        val questionId = segments?.getOrNull(0)?.takeIf { it.isNotBlank() }
        val epochMillis = segments?.getOrNull(1)?.toLongOrNull()
        if (questionId == null || epochMillis == null) {
            appLog.warn(TAG, "Malformed question reminder URI: ${intent.data}")
            return null
        }
        return questionId to Instant.ofEpochMilli(epochMillis)
    }

    companion object {
        const val ACTION_QUESTION_REMINDER = "com.meteor.memory.action.QUESTION_REMINDER"
        const val ACTION_QUESTION_SNOOZE_HOUR = "com.meteor.memory.action.QUESTION_SNOOZE_HOUR"
        const val ACTION_QUESTION_SNOOZE_TOMORROW =
            "com.meteor.memory.action.QUESTION_SNOOZE_TOMORROW"
        private const val TAG = "QuestionReminderReceiver"
    }
}
