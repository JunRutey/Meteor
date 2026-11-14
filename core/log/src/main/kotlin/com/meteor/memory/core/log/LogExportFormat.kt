package com.meteor.memory.core.log

/** One on-disk log file as it appears in the exported document. */
data class LogFileContent(
    val name: String,
    val text: String,
)

/**
 * Assembles the single text document users attach to a bug report.
 *
 * Kept out of [LogExporter] (which needs Android APIs) so ordering and
 * truncation rules are unit-testable. The tail is always kept: the newest lines
 * are the ones that explain a failure.
 */
object LogExportFormat {

    /** Per-file tail kept in the export. Large enough for a verbose day. */
    const val MAX_CHARS_PER_FILE = 1_000_000

    /** Overall tail kept across all files; older sections fall off first. */
    const val MAX_TOTAL_CHARS = 4_000_000

    fun document(header: String, files: List<LogFileContent>): String {
        if (files.isEmpty()) return header + NO_FILES + "\n"

        val body = buildString {
            files.sortedBy { it.name }.forEach { file ->
                appendLine("## ${file.name}")
                appendLine(tailOf(file.text, MAX_CHARS_PER_FILE))
            }
        }

        return if (body.length <= MAX_TOTAL_CHARS) {
            header + body
        } else {
            // Drop the partial first line so a markdown header is not cut in half.
            val tail = tailOf(body, MAX_TOTAL_CHARS)
                .substringAfter('\n')
                .ifBlank { tailOf(body, MAX_TOTAL_CHARS) }
            header + TRUNCATED + "\n" + tail
        }
    }

    private fun tailOf(text: String, limit: Int): String =
        if (text.length <= limit) text else text.takeLast(limit)

    private const val NO_FILES = "no log files yet"
    private const val TRUNCATED = "earlier lines omitted"
}
