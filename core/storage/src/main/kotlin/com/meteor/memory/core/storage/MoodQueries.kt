package com.meteor.memory.core.storage

import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * The six moods a day can carry.
 *
 * Stored as a stable code rather than an emoji so the rendered glyph and its
 * label stay a presentation concern (archive R7: no hardcoded text in code).
 */
enum class Mood(val code: String) {
    HAPPY("happy"),
    CALM("calm"),
    EXCITED("excited"),
    TIRED("tired"),
    SAD("sad"),
    ANGRY("angry"),
    ;

    companion object {
        fun fromCode(code: String): Mood? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Mood access, separate from [DiaryQueries] because a mood day is its own
 * `type: mood` record at `时光轴/心情/<date>.md`, not a frontmatter field.
 *
 * Archive §5.1 freezes frontmatter at exactly five keys, so mood could not be
 * added there; ADR-017 records the reasoning.
 */
interface MoodQueries {
    /** Mood per day for `[from, to]` inclusive. */
    fun range(from: LocalDate, to: LocalDate): Map<LocalDate, Mood>

    fun read(date: LocalDate): Mood?

    /** Writes or replaces the mood for [date]. */
    fun save(date: LocalDate, mood: Mood)
}

class PrivateMoodQueries(
    rootDir: File,
    private val router: StorageRouter,
) : MoodQueries {

    private val moodDir = File(rootDir, "${StorageLayout.TIMELINE}/${StorageLayout.MOOD}")

    override fun range(from: LocalDate, to: LocalDate): Map<LocalDate, Mood> {
        if (!moodDir.isDirectory) return emptyMap()

        val result = LinkedHashMap<LocalDate, Mood>()
        // Only the mood files themselves: `日记/` is a subdirectory and its
        // records are diaries, not moods.
        moodDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            ?.forEach { file ->
                val date = DiaryFiles.parseDate(file.name) ?: return@forEach
                if (date < from || date > to) return@forEach
                val code = runCatching { router.read(file).body.trim() }.getOrNull()
                Mood.fromCode(code ?: return@forEach)?.let { result[date] = it }
            }
        return result
    }

    override fun read(date: LocalDate): Mood? {
        val file = router.route(null, RecordType.MOOD, date)
        if (!file.isFile) return null
        val code = runCatching { router.read(file).body.trim() }.getOrNull() ?: return null
        return Mood.fromCode(code)
    }

    override fun save(date: LocalDate, mood: Mood) {
        val file = router.route(null, RecordType.MOOD, date)
        val existing = if (file.isFile) runCatching { router.read(file) }.getOrNull() else null

        router.write(
            file,
            Document(
                file = file,
                personId = null,
                type = RecordType.MOOD,
                timestamp = existing?.timestamp ?: Instant.now(),
                source = RecordSource.USER,
                body = mood.code,
            ),
        )
    }
}
