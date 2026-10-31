package com.meteor.memory.feature.editor

import com.meteor.memory.core.storage.ImageStore

/** One image reference found in a record body. */
data class AttachedImage(
    /** Bare path, usable with `ImageStore.load` / `delete`. */
    val token: String,
    /** Alt text written by the author, empty when the reference has none. */
    val label: String,
)

/**
 * Markdown body manipulation, kept pure so it can be unit-tested.
 *
 * The editing surface itself is now plain text (ADR-021): no markup is hidden,
 * so there is no caret mapping to get wrong. What this object still owns is the
 * two things the toolbar can insert — images and links — plus removing an image
 * again.
 */
object MarkdownEditing {

    private val REFERENCE = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    private val MULTIPLE_SPACES = Regex(" {2,}")

    fun appendImage(body: String, token: String, alt: String = ""): String =
        appendBlock(body, ImageStore.markdownFor(token, alt))

    fun appendLink(body: String, target: String, label: String?): String {
        val text = label?.takeIf { it.isNotBlank() } ?: target
        return appendInline(body, "[$text]($target)")
    }

    /**
     * Every image referenced by [body], in document order and without duplicates.
     *
     * [AttachedImage.token] is the bare path, because that is what
     * `ImageStore.load` / `delete` take; the `meteor://image/` prefix only exists
     * inside a body.
     */
    fun imagesOf(body: String): List<AttachedImage> =
        REFERENCE.findAll(body)
            .mapNotNull { match ->
                val target = match.groupValues[2]
                if (!target.startsWith(ImageStore.IMAGE_SCHEME)) return@mapNotNull null
                AttachedImage(
                    token = target.removePrefix(ImageStore.IMAGE_SCHEME),
                    label = match.groupValues[1],
                )
            }
            .distinctBy { it.token }
            .toList()

    /**
     * Drops one image reference from [body].
     *
     * A line that held nothing but the reference disappears completely —
     * otherwise every deletion would leave a growing pile of blank lines — while
     * an inline reference only loses itself and the space next to it.
     *
     * @return [body] unchanged when the reference is not there.
     */
    fun removeImage(body: String, token: String): String {
        if (token.isBlank()) return body
        val reference = Regex("""!\[[^\]]*]\(${Regex.escape(ImageStore.IMAGE_SCHEME + token)}\)""")
        if (!reference.containsMatchIn(body)) return body

        val kept = body.split("\n").mapNotNull { line ->
            val stripped = reference.replace(line, "")
            when {
                stripped == line -> line
                // Line carried only the image: drop it instead of leaving a gap.
                stripped.isBlank() -> null
                else -> stripped.replace(MULTIPLE_SPACES, " ").trim()
            }
        }
        // Removing the first or last line must not leave the body with an empty
        // line in front of (or behind) everything else.
        return kept.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }

    private fun appendBlock(body: String, block: String): String {
        val trimmed = body.trimEnd()
        return when {
            trimmed.isEmpty() -> "$block\n"
            trimmed.endsWith("\n") -> "$trimmed$block\n"
            else -> "$trimmed\n\n$block\n"
        }
    }

    private fun appendInline(body: String, inline: String): String =
        if (body.isEmpty() || body.endsWith(" ") || body.endsWith("\n")) body + inline else "$body $inline"
}
