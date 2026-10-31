package com.meteor.memory.core.log

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel(val label: Char) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/**
 * Append-only diagnostic log.
 *
 * Deliberately tiny and dependency-free so any module could adopt it later.
 * Callers must never pass decrypted record bodies or credentials: this log is
 * meant to be exported and handed to someone else for debugging.
 */
interface AppLog {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Verbose mode records every level; otherwise only WARN/ERROR reach the file.
     * Logcat is always written regardless, so `adb logcat` stays complete.
     */
    fun setVerbose(enabled: Boolean)

    fun isVerbose(): Boolean

    /** Bytes currently occupied by the on-disk log files. */
    fun sizeBytes(): Long
}

/** Pure line formatting, kept out of the logger so it can be unit-tested. */
object LogFormatter {

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    fun line(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        at: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val head = "${STAMP.format(at.atZone(zone))} [${level.label}] [$tag] $message"
        if (throwable == null) return head

        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val trimmed = stack.lineSequence()
            .filter { it.isNotBlank() }
            .take(MAX_STACK_LINES)
            .joinToString("\n")
        return "$head\n$trimmed"
    }

    private const val MAX_STACK_LINES = 12
}
