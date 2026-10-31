package com.meteor.memory.feature.editor

import com.meteor.memory.core.storage.ImageStore

/** Result of a formatting action: the new text plus where the caret should land. */
data class EditResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/** One image reference found in a record body. */
data class AttachedImage(
    /** Path token usable as `meteor://image/<token>`. */
    val token: String,
    /** Alt text written by the author, empty when the reference has none. */
    val label: String,
)

/**
 * Markdown text manipulation, kept pure so it can be unit-tested.
 *
 * v0.4 ships exactly three inline capabilities (ADR-019): bold, images and
 * links. Colour and italic were dropped from the toolbar, so no new markup can
 * be produced for them — the preview still renders old ones (ADR-019 §3).
 *
 * Bold is a **toggle**: with a selection it wraps (or unwraps) that selection;
 * with no selection it inserts an empty pair and parks the caret inside, so the
 * user can press the button first and type afterwards.
 */
object MarkdownEditing {

    private const val BOLD_MARKER = "**"

    private val REFERENCE = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    private val MULTIPLE_SPACES = Regex(" {2,}")

    fun appendImage(body: String, token: String, alt: String = ""): String =
        appendBlock(body, ImageStore.markdownFor(token, alt))

    fun appendLink(body: String, target: String, label: String?): String {
        val text = label?.takeIf { it.isNotBlank() } ?: target
        return appendInline(body, "[$text]($target)")
    }

    fun toggleBold(body: String, start: Int, end: Int): EditResult =
        toggle(body, start, end, BOLD_MARKER, BOLD_MARKER)

    fun wrapsWith(body: String, start: Int, end: Int, marker: String): String {
        if (start !in 0..body.length || end !in start..body.length) return body
        val before = body.substring(0, start)
        val selection = body.substring(start, end)
        val after = body.substring(end)
        return "$before$marker$selection$marker$after"
    }

    /** Every image referenced by [body], in document order and without duplicates. */
    fun imagesOf(body: String): List<AttachedImage> =
        REFERENCE.findAll(body)
            .filter { it.groupValues[2].startsWith(ImageStore.IMAGE_SCHEME) }
            .map { AttachedImage(token = it.groupValues[2], label = it.groupValues[1]) }
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

    /**
     * Wraps the selection, or unwraps it when it is already wrapped, or inserts
     * an empty pair with the caret between the markers.
     */
    private fun toggle(
        body: String,
        start: Int,
        end: Int,
        open: String,
        close: String,
    ): EditResult {
        if (start !in 0..body.length || end !in start..body.length) {
            return EditResult(body, start, end)
        }

        val alreadyWrapped = start >= open.length &&
            end + close.length <= body.length &&
            body.startsWith(open, start - open.length) &&
            body.startsWith(close, end)

        if (alreadyWrapped) {
            val unwrapped = body
                .removeRange(end, end + close.length)
                .removeRange(start - open.length, start)
            val shift = open.length
            return EditResult(unwrapped, start - shift, end - shift)
        }

        if (start == end) {
            val text = body.substring(0, start) + open + close + body.substring(start)
            val caret = start + open.length
            return EditResult(text, caret, caret)
        }

        val selected = body.substring(start, end)
        val text = body.substring(0, start) + open + selected + close + body.substring(end)
        return EditResult(text, start + open.length, end + open.length)
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
