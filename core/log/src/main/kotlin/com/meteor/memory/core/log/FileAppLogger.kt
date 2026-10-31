package com.meteor.memory.core.log

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Writes one file per day under `filesDir/logs/`, keeps [retentionDays] days,
 * and mirrors to Logcat so `adb logcat` still works.
 *
 * Writes are serialized on a private lock: the app logs from several threads and
 * interleaved half-lines would make the exported file useless.
 */
class FileAppLogger(
    context: Context,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val clock: () -> Instant = Instant::now,
) : AppLog {

    private val logDir = File(context.filesDir, DIR_NAME)
    private val lock = Any()

    override fun debug(tag: String, message: String) = write(LogLevel.DEBUG, tag, message, null)
    override fun info(tag: String, message: String) = write(LogLevel.INFO, tag, message, null)
    override fun warn(tag: String, message: String, throwable: Throwable?) =
        write(LogLevel.WARN, tag, message, throwable)

    override fun error(tag: String, message: String, throwable: Throwable?) =
        write(LogLevel.ERROR, tag, message, throwable)

    private fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        Log.println(priorityOf(level), LOGCAT_TAG, "$tag: $message")

        val at = clock()
        val line = LogFormatter.line(level, tag, message, throwable, at)

        synchronized(lock) {
            runCatching {
                if (!logDir.exists()) logDir.mkdirs()
                val today = at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                File(logDir, LogFiles.fileNameFor(today)).appendText(line + "\n", Charsets.UTF_8)
                prune(today)
            }
        }
    }

    private fun prune(today: LocalDate) {
        val files = logDir.listFiles() ?: return
        LogFiles.expired(files.toList(), today, retentionDays).forEach { runCatching { it.delete() } }
    }

    private fun priorityOf(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    companion object {
        private const val DIR_NAME = "logs"
        private const val LOGCAT_TAG = "Meteor"
        private const val DEFAULT_RETENTION_DAYS = 7
    }
}
