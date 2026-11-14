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

    /**
     * Oldest log files to delete so the directory fits [maxTotalBytes].
     *
     * [keep] (the file currently being appended to) is never returned: dropping
     * today's only log line is exactly the moment a bug report would need it.
     * Unparseable names and non-log files count toward the total but are never
     * deleted, matching [expired].
     */
    fun overBudget(
        files: List<File>,
        maxTotalBytes: Long,
        keep: File? = null,
    ): List<File> {
        if (maxTotalBytes <= 0) return emptyList()

        var total = files.filter { it.isFile }.sumOf { it.length() }
        if (total <= maxTotalBytes) return emptyList()

        val candidates = files
            .filter { it.isFile && it != keep && dateOf(it.name) != null }
            .sortedWith(compareBy<File> { dateOf(it.name) }.thenBy { it.name })

        val doomed = mutableListOf<File>()
        for (file in candidates) {
            if (total <= maxTotalBytes) break
            total -= file.length()
            doomed += file
        }
        return doomed
    }
}
