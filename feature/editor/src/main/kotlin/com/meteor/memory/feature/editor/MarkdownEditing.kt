package com.meteor.memory.feature.editor

import com.meteor.memory.core.storage.ImageStore

/** Result of a formatting action: the new text plus where the caret should land. */
data class EditResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Markdown text manipulation, kept pure so it can be unit-tested.
 *
 * Every formatter is a **toggle**: with a selection it wraps (or unwraps) that
 * selection; with no selection it inserts an empty pair and parks the caret
 * inside, so the user can press the button first and type afterwards.
 */
object MarkdownEditing {

    private const val BOLD_MARKER = "**"
    private const val ITALIC_MARKER = "*"

    fun appendImage(body: String, token: String, alt: String = ""): String =
        appendBlock(body, ImageStore.markdownFor(token, alt))

    fun appendLink(body: String, target: String, label: String?): String {
        val text = label?.takeIf { it.isNotBlank() } ?: target
        return appendInline(body, "[$text]($target)")
    }

    fun toggleBold(body: String, start: Int, end: Int): EditResult =
        toggle(body, start, end, BOLD_MARKER, BOLD_MARKER)

    fun toggleItalic(body: String, start: Int, end: Int): EditResult =
        toggle(body, start, end, ITALIC_MARKER, ITALIC_MARKER)

    fun toggleColor(body: String, start: Int, end: Int, hex: String): EditResult =
        toggle(body, start, end, """<span style="color:$hex">""", "</span>")

    fun wrapsWith(body: String, start: Int, end: Int, marker: String): String {
        if (start !in 0..body.length || end !in start..body.length) return body
        val before = body.substring(0, start)
        val selection = body.substring(start, end)
        val after = body.substring(end)
        return "$before$marker$selection$marker$after"
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
