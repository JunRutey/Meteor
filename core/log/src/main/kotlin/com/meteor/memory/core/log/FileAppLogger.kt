package com.meteor.memory.core.log

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Writes one file per day under `filesDir/logs/`, keeps [retentionDays] days and
 * a total-size ceiling, and mirrors to Logcat so `adb logcat` still works.
 *
 * INFO is persisted even outside verbose mode: a normal session still needs to
 * show which operation ran before the warning/error that followed. DEBUG is the
 * only verbose-only level. If the file write itself fails, the failure is sent
 * to Logcat instead of disappearing inside `runCatching`.
 *
 * Writes are serialized on a private lock: the app logs from several threads and
 * interleaved half-lines would make the exported file useless.
 */
class FileAppLogger(
    context: Context,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val clock: () -> Instant = Instant::now,
) : AppLog {

    private val logDir = File(context.filesDir, DIR_NAME)
    private val lock = Any()

    @Volatile
    private var verbose: Boolean = false

    override fun setVerbose(enabled: Boolean) {
        verbose = enabled
    }

    override fun isVerbose(): Boolean = verbose

    override fun sizeBytes(): Long =
        (logDir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sumOf { it.length() }

    override fun debug(tag: String, message: String) = write(LogLevel.DEBUG, tag, message, null)
    override fun info(tag: String, message: String) = write(LogLevel.INFO, tag, message, null)
    override fun warn(tag: String, message: String, throwable: Throwable?) =
        write(LogLevel.WARN, tag, message, throwable)

    override fun error(tag: String, message: String, throwable: Throwable?) =
        write(LogLevel.ERROR, tag, message, throwable)

    private fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        Log.println(priorityOf(level), LOGCAT_TAG, "$tag: $message")

        if (!LogPolicy.persists(level, verbose)) return

        val at = clock()
        val line = LogFormatter.line(level, tag, message, throwable, at)

        synchronized(lock) {
            runCatching {
                if (!logDir.exists() && !logDir.mkdirs()) {
                    error("could not create $logDir")
                }
                val today = at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val current = File(logDir, LogFiles.fileNameFor(today))
                current.appendText(line + "\n", Charsets.UTF_8)
                prune(today, current)
            }.onFailure { failure ->
                // Never let a disk problem erase the only evidence of a crash.
                Log.e(LOGCAT_TAG, "could not persist diagnostic log", failure)
            }
        }
    }

    private fun prune(today: LocalDate, current: File) {
        val files = logDir.listFiles() ?: return
        LogFiles.expired(files.toList(), today, retentionDays)
            .forEach { runCatching { it.delete() } }

        // Size pruning runs after date pruning; keep the file still being written.
        val remaining = logDir.listFiles()?.toList() ?: return
        LogFiles.overBudget(remaining, maxTotalBytes, keep = current)
            .forEach { runCatching { it.delete() } }
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
        private const val DEFAULT_MAX_TOTAL_BYTES = 32L * 1024L * 1024L
    }
}
