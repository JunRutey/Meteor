package com.meteor.memory.feature.editor

import com.meteor.memory.core.storage.ImageStore

/**
 * Markdown text manipulation, kept pure so it can be unit-tested.
 *
 * The editor never rewrites the user's text; it only appends or wraps at the
 * current selection.
 */
object MarkdownEditing {

    fun appendImage(body: String, token: String, alt: String = ""): String =
        appendBlock(body, ImageStore.markdownFor(token, alt))

    fun appendLink(body: String, target: String, label: String?): String {
        val text = label?.takeIf { it.isNotBlank() } ?: target
        return appendInline(body, "[$text]($target)")
    }

    fun wrapsWith(body: String, start: Int, end: Int, marker: String): String {
        if (start !in 0..body.length || end !in start..body.length) return body
        val before = body.substring(0, start)
        val selection = body.substring(start, end)
        val after = body.substring(end)
        return "$before$marker$selection$marker$after"
    }

    fun blockQuote(body: String, start: Int, end: Int): String =
        wrapsWith(body, start, end, "**")

    fun italicize(body: String, start: Int, end: Int): String =
        wrapsWith(body, start, end, "*")

    /** Wraps the selection in an inline colour span; colours are part of the body. */
    fun colorize(body: String, start: Int, end: Int, hex: String): String {
        if (start !in 0..body.length || end !in start..body.length) return body
        val before = body.substring(0, start)
        val selection = body.substring(start, end)
        val after = body.substring(end)
        val tag = if (selection.isEmpty()) "文字" else selection
        return "$before<span style=\"color:$hex\">$tag</span>$after"
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
