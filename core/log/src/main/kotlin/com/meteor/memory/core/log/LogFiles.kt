package com.meteor.memory.core.log

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Log file naming and retention, pure so the rules are unit-testable. */
object LogFiles {

    const val PREFIX = "meteor-"
    const val EXTENSION = ".log"

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val PATTERN = Regex("""^meteor-(\d{4}-\d{2}-\d{2})\.log$""")

    fun fileNameFor(date: LocalDate): String = "$PREFIX${DATE.format(date)}$EXTENSION"

    fun dateOf(fileName: String): LocalDate? {
        val match = PATTERN.find(fileName) ?: return null
        return runCatching { LocalDate.parse(match.groupValues[1], DATE) }.getOrNull()
    }

    /**
     * Files older than [retentionDays] before [today]. Unparseable names are
     * left alone: deleting something we do not understand is never the safe move.
     */
    fun expired(files: List<File>, today: LocalDate, retentionDays: Int): List<File> {
        val cutoff = today.minusDays(retentionDays.toLong().coerceAtLeast(0))
        return files.filter { file ->
            val date = dateOf(file.name) ?: return@filter false
            date.isBefore(cutoff)
        }
    }
}
