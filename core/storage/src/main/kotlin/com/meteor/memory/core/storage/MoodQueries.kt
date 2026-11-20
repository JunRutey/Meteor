package com.meteor.memory.core.storage

import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * The seven moods a day can carry.
 *
 * Stored as a stable code rather than an emoji so the rendered glyph and its
 * label stay a presentation concern (archive R7: no hardcoded text in code).
 *
 * Adding a constant is backward compatible in both directions: [fromCode]
 * returns null for a code this build does not know, so an older install shows
 * "no mood" for a day written by a newer one instead of failing to parse.
 */
enum class Mood(val code: String) {
    HAPPY("happy"),
    CALM("calm"),
    EXCITED("excited"),
    TIRED("tired"),
    SAD("sad"),
    ANGRY("angry"),

    /** Wordless "hmm…" mood; the label stays untranslated on purpose. */
    EMM("emm"),
    ;

    companion object {
        /** A day holds at most this many moods (ADR-035). */
        const val MAX_PER_DAY = 3

        fun fromCode(code: String): Mood? = entries.firstOrNull { it.code == code }

        /**
         * Moods encoded in a record body.
         *
         * One code per line. A legacy record with a single code
         * (`happy`) is still one mood; unknown codes are ignored so a future
         * build's extra value cannot hide the moods this build understands.
         * Order is preserved and duplicates are collapsed.
         */
        fun decode(body: String): List<Mood> =
            body.split('\n', ',')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull(::fromCode)
                .distinct()
                .take(MAX_PER_DAY)
                .toList()

        /** [decode]'s inverse; an empty list means "no mood record". */
        fun encode(moods: List<Mood>): String =
            moods.distinct().take(MAX_PER_DAY).joinToString("\n") { it.code }

        /**
         * Selection after tapping [mood]: tap an already selected mood to drop
         * it, tap a new one to append it. Adding beyond [MAX_PER_DAY] is a no-op.
         */
        fun toggled(current: List<Mood>, mood: Mood): List<Mood> =
            if (mood in current) {
                current.filterNot { it == mood }
            } else {
                (current + mood).distinct().take(MAX_PER_DAY)
            }
    }
}

/**
 * Mood access, separate from [DiaryQueries] because a mood day is its own
 * `type: mood` record at `时光轴/心情/<date>.md`, not a frontmatter field.
 *
 * A day carries up to [Mood.MAX_PER_DAY] moods in selection order. The body is
 * one mood code per line (ADR-035); a file holding a single code — every record
 * written before multi-select — reads back as a one-element list.
 *
 * Archive §5.1 freezes frontmatter at exactly five keys, so mood could not be
 * added there; ADR-017 records the reasoning.
 */
interface MoodQueries {
    /** All moods per day for `[from, to]` inclusive, in selection order. */
    fun range(from: LocalDate, to: LocalDate): Map<LocalDate, List<Mood>>

    /** The day's moods in selection order; empty when nothing was recorded. */
    fun read(date: LocalDate): List<Mood>

    /**
     * Replaces the day's moods. Duplicates are dropped and only the first
     * [Mood.MAX_PER_DAY] are kept; an empty list deletes the record.
     */
    fun save(date: LocalDate, moods: List<Mood>)
}

class PrivateMoodQueries(
    rootDir: File,
    private val router: StorageRouter,
) : MoodQueries {

    private val moodDir = File(rootDir, "${StorageLayout.TIMELINE}/${StorageLayout.MOOD}")

    override fun range(from: LocalDate, to: LocalDate): Map<LocalDate, List<Mood>> {
        if (!moodDir.isDirectory) return emptyMap()

        val result = LinkedHashMap<LocalDate, List<Mood>>()
        // Only the mood files themselves: `日记/` is a subdirectory and its
        // records are diaries, not moods.
        moodDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            ?.forEach { file ->
                val date = DiaryFiles.parseDate(file.name) ?: return@forEach
                if (date < from || date > to) return@forEach
                val body = runCatching { router.read(file).body }.getOrNull() ?: return@forEach
                val moods = Mood.decode(body)
                if (moods.isNotEmpty()) result[date] = moods
            }
        return result
    }

    override fun read(date: LocalDate): List<Mood> {
        val file = router.route(null, RecordType.MOOD, date)
        if (!file.isFile) return emptyList()
        val body = runCatching { router.read(file).body }.getOrNull() ?: return emptyList()
        return Mood.decode(body)
    }

    override fun save(date: LocalDate, moods: List<Mood>) {
        val file = router.route(null, RecordType.MOOD, date)
        val encoded = Mood.encode(moods)
        if (encoded.isEmpty()) {
            if (file.isFile) file.delete()
            return
        }

        val existing = if (file.isFile) runCatching { router.read(file) }.getOrNull() else null
        router.write(
            file,
            Document(
                file = file,
                personId = null,
                type = RecordType.MOOD,
                timestamp = existing?.timestamp ?: Instant.now(),
                source = RecordSource.USER,
                body = encoded,
            ),
        )
    }
}
