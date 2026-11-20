package com.meteor.memory.ui.about

/** One released version from `changelog.md`: label plus its raw Markdown lines. */
data class ChangelogVersion(
    val number: String,
    val lines: List<String>,
)

/**
 * Parses the bundled changelog into versions without pulling in a Markdown
 * library. The file is a sequence of `## vX.Y.Z` headings, newest first; every
 * line until the next version heading belongs to the current version.
 */
object ChangelogParser {

    private const val VERSION_PREFIX = "## "

    fun parse(markdown: String): List<ChangelogVersion> {
        if (markdown.isBlank()) return emptyList()

        val versions = mutableListOf<ChangelogVersion>()
        var currentNumber: String? = null
        var currentLines = mutableListOf<String>()

        fun flush() {
            val number = currentNumber ?: return
            versions += ChangelogVersion(
                number = number,
                lines = currentLines
                    .dropWhile { it.isBlank() }
                    .dropLastWhile { it.isBlank() },
            )
            currentLines = mutableListOf()
        }

        for (rawLine in markdown.removePrefix("\uFEFF").lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.startsWith(VERSION_PREFIX)) {
                val candidate = line.removePrefix(VERSION_PREFIX).trim()
                if (candidate.isNotEmpty()) {
                    flush()
                    currentNumber = candidate
                    continue
                }
            }
            if (currentNumber != null) currentLines += line
        }
        flush()
        return versions
    }
}
