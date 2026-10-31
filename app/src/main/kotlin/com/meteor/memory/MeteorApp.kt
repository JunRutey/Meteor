package com.meteor.memory

import android.app.Application
import com.meteor.memory.core.log.AppLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MeteorApp : Application() {

    @Inject
    lateinit var appLog: AppLog

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    /**
     * Records uncaught exceptions before the process dies.
     *
     * Without this a crash leaves nothing behind: the log file only ever holds
     * what the app chose to write, and a dying process writes nothing.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                appLog.error(TAG, "uncaught exception on thread ${thread.name}", error)
            }.onFailure { loggingFailure ->
                // Never mask the original crash with a logging failure.
                loggingFailure.printStackTrace()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val TAG = "Crash"
    }
}
