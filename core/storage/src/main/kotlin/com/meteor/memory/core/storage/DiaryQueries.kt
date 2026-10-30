package com.meteor.memory.core.storage

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Read/list access for one record family.
 *
 * Deliberately NOT part of the frozen `StorageRouter` contract: routing alone
 * cannot enumerate, so browsing lives in this additive interface. Removing it
 * leaves `StorageRouter` and every stored file untouched (archive R2/R3).
 */
interface DiaryQueries {
    /** All diary dates present on disk, newest first. */
    fun dates(): List<LocalDate>

    /** The decoded diary for [date], or null when nothing was written. */
    fun read(date: LocalDate): Document?

    /** Creates or overwrites the diary for [date], encrypting on the way out. */
    fun save(date: LocalDate, body: String)
}

/** Filename ⇄ date rules, kept pure so they are unit-testable. */
object DiaryFiles {
    const val EXTENSION = ".md"

    private val PATTERN = Regex("""^(\d{4}-\d{2}-\d{2})\.md$""")

    fun parseDate(fileName: String): LocalDate? {
        val match = PATTERN.find(fileName) ?: return null
        return runCatching { LocalDate.parse(match.groupValues[1], DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    fun fileNameFor(date: LocalDate): String =
        date.format(DateTimeFormatter.ISO_LOCAL_DATE) + EXTENSION
}

class PrivateDiaryQueries(
    rootDir: File,
    private val router: StorageRouter,
) : DiaryQueries {

    private val diaryDir = File(
        rootDir,
        "${StorageLayout.TIMELINE}/${StorageLayout.MOOD}/${StorageLayout.DIARY}",
    )

    override fun dates(): List<LocalDate> {
        if (!diaryDir.isDirectory) return emptyList()
        return diaryDir.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { DiaryFiles.parseDate(it.name) }
            .distinct()
            .sortedDescending()
            .toList()
    }

    override fun read(date: LocalDate): Document? {
        val file = router.route(null, RecordType.DIARY, date)
        if (!file.isFile) return null
        return runCatching { router.read(file) }.getOrNull()
    }

    /**
     * Overwrites the day's entry while preserving the original creation
     * timestamp, so re-saving never rewrites the record's identity.
     */
    override fun save(date: LocalDate, body: String) {
        val file = router.route(null, RecordType.DIARY, date)
        val existing = if (file.isFile) runCatching { router.read(file) }.getOrNull() else null

        router.write(
            file,
            Document(
                file = file,
                personId = null,
                type = RecordType.DIARY,
                timestamp = existing?.timestamp ?: Instant.now(),
                source = RecordSource.USER,
                body = body,
            ),
        )
    }
}
