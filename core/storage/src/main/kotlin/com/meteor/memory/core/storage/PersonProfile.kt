package com.meteor.memory.core.storage

/** Relationship bucket from archive §4: 家人 / 朋友 / 绝交的朋友. */
enum class Relation(val code: String) {
    FAMILY("family"),
    FRIEND("friend"),
    FORMER_FRIEND("former_friend"),
    ;

    companion object {
        fun fromCode(code: String): Relation? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A person's profile, stored **inside the ciphertext** of `<person>/profile.md`.
 *
 * Name and relationship never reach the filesystem (ADR-014): directories are
 * keyed by `person_id`, so nothing here leaks through a path.
 */
data class PersonProfile(
    val displayName: String,
    val relation: Relation?,
    val notes: String = "",
)

/**
 * Body format for `profile.md`:
 *
 * ```
 * name: 小明
 * relation: friend
 * ---
 * 自由正文
 * ```
 *
 * A small header block keeps the file parseable *and* readable in an exported
 * backup, which is what §5.1's "Markdown is the source of truth" asks for.
 */
object PersonProfileCodec {

    private const val SEPARATOR = "---"
    const val KEY_NAME = "name"
    const val KEY_RELATION = "relation"

    fun encode(profile: PersonProfile): String = buildString {
        append(KEY_NAME).append(": ").append(profile.displayName).append('\n')
        profile.relation?.let { append(KEY_RELATION).append(": ").append(it.code).append('\n') }
        append(SEPARATOR).append('\n')
        if (profile.notes.isNotBlank()) append(profile.notes.trimEnd()).append('\n')
    }

    /**
     * @return null when the body has no recognisable header — such a file is
     * treated as a legacy profile rather than silently rewritten.
     */
    fun decode(body: String): PersonProfile? {
        val normalized = body.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        val headerEnd = lines.indexOfFirst { it.trim() == SEPARATOR }
        if (headerEnd <= 0) return null

        val header = LinkedHashMap<String, String>()
        for (line in lines.subList(0, headerEnd)) {
            if (line.isBlank()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            header[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }

        val name = header[KEY_NAME]?.takeIf { it.isNotBlank() } ?: return null
        val notes = lines.drop(headerEnd + 1).joinToString("\n").trim()
        return PersonProfile(
            displayName = name,
            relation = header[KEY_RELATION]?.let(Relation::fromCode),
            notes = notes,
        )
    }
}
