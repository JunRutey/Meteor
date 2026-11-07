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
import javax.inject.Inject

@AndroidEntryPoint
class DailyReminderReceiver : BroadcastReceiver() {
    @Inject
    lateinit var notificationFeature: NotificationFeature

    @Inject
    lateinit var appLog: AppLog

    override fun onReceive(context: Context, intent: Intent) {
        appLog.info(TAG, "Received broadcast action=${intent.action}")
        val pendingResult = goAsync()
        val exceptionHandler = CoroutineExceptionHandler { _, failure ->
            appLog.error(TAG, "Notification broadcast processing failed", failure)
        }
        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            try {
                if (intent.action == ACTION_DAILY_REMINDER) {
                    notificationFeature.handleReminderAlarm()
                } else {
                    notificationFeature.syncReminder()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DAILY_REMINDER = "com.meteor.memory.action.DAILY_REMINDER"
        private const val TAG = "DailyReminderReceiver"
    }
}
