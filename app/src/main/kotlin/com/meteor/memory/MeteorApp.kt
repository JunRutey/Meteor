package com.meteor.memory

import android.app.Application
import android.os.Process
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.system.NotificationFeature
import com.meteor.memory.ui.life.LifeStartupPrewarm
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MeteorApp : Application() {

    @Inject
    lateinit var appLog: AppLog

    @Inject
    lateinit var notificationFeature: NotificationFeature

    @Inject
    lateinit var lifePrewarm: LifeStartupPrewarm

    override fun onCreate() {
        super.onCreate()
        appLog.info(START_TAG, "process started pid=${Process.myPid()}")
        // Kick the birth-profile/lunar prewarm off as early as possible: it runs
        // while the system splash is still on screen (ADR-044). Only in-memory
        // data is prepared; the app lock still decides what may be rendered.
        lifePrewarm.start()
        notificationFeature.initialize()
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
        const val START_TAG = "AppStart"
    }
}
