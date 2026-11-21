package com.meteor.memory.feature.editor

/**
 * A format the toolbar has switched **on**, so text typed from now on carries it
 * (ADR-051).
 *
 * This is a mode, not a one-shot command: tapping `B` turns bold on, typing
 * produces `**…**`, and tapping `B` again turns it off. The alternative — wrap
 * the selection, then forget — needs the selection to be made *before* tapping,
 * which is not how writing works: you decide to emphasise, then you type.
 */
sealed interface InlineFormat {

    /** `**…**` */
    data object Bold : InlineFormat

    /** `<span style="color:#RRGGBB">…</span>` */
    data class Color(val hex: String) : InlineFormat

    companion object {
        /**
         * The mode [tapped] means, given what is already on.
         *
         * Tapping the active format turns it off; tapping a different colour
         * switches to that colour rather than opening a second span.
         */
        fun afterTap(current: InlineFormat?, tapped: InlineFormat): InlineFormat? =
            if (current == tapped) null else tapped
    }
}

/**
 * Keeps a text block's Markdown markers balanced while a format mode is active.
 *
 * The field is a plain Markdown string, so a mode has to express itself as
 * characters in that string, and the user must be able to switch it off from
 * inside the run it opened. The mode therefore owns exactly one unclosed marker
 * at the caret:
 *
 * - switching on writes the opener (`**` / `<span …>`) at the caret;
 * - typing then lands after it, inside the run;
 * - switching off writes the matching close — or, when nothing was typed,
 *   removes the opener again so an empty run never leaves `****` behind.
 *
 * Every function is pure and total: offsets are clamped and no input throws.
 */
object MarkdownInlineEditing {

    /** Opens [format] at [cursor]; the returned caret sits inside the new run. */
    fun open(text: String, cursor: Int, format: InlineFormat): InlineEdit {
        val at = cursor.coerceIn(0, text.length)
        val marker = openingMarker(format)
        return InlineEdit(
            text = text.substring(0, at) + marker + text.substring(at),
            selectionStart = at + marker.length,
            selectionEnd = at + marker.length,
        )
    }

    /**
     * Closes the run [format] opened at the caret.
     *
     * Three cases, in order:
     *
     * 1. nothing was typed since [open] — the opener is removed, so toggling on
     *    and straight back off leaves the text exactly as it was;
     * 2. the close is already right after the caret — nothing to add;
     * 3. otherwise the close is inserted at the caret.
     *
     * A caret that is not inside a run of this format is left alone: appending a
     * stray `**` there would corrupt the paragraph around it.
     */
    fun close(text: String, cursor: Int, format: InlineFormat): InlineEdit {
        val at = cursor.coerceIn(0, text.length)
        val open = openingMarker(format)
        val close = closingMarker(format)

        val openAt = unmatchedOpener(text, at, open, close)
            ?: return InlineEdit(text, at, at)

        // Case 1: empty run — take the opener back out.
        if (openAt + open.length == at) {
            return InlineEdit(
                text = text.removeRange(openAt, at),
                selectionStart = openAt,
                selectionEnd = openAt,
            )
        }

        // Case 2: already closed here.
        if (text.startsWith(close, at)) return InlineEdit(text, at, at)

        // Case 3: close the run.
        return InlineEdit(
            text = text.substring(0, at) + close + text.substring(at),
            selectionStart = at + close.length,
            selectionEnd = at + close.length,
        )
    }

    /** True when a run of [format] is open at [cursor]. */
    fun isOpen(text: String, cursor: Int, format: InlineFormat): Boolean {
        val at = cursor.coerceIn(0, text.length)
        return unmatchedOpener(text, at, openingMarker(format), closingMarker(format)) != null
    }

    fun openingMarker(format: InlineFormat): String = when (format) {
        InlineFormat.Bold -> "**"
        is InlineFormat.Color -> """<span style="color:${format.hex.uppercase()}">"""
    }

    fun closingMarker(format: InlineFormat): String = when (format) {
        InlineFormat.Bold -> "**"
        is InlineFormat.Color -> "</span>"
    }

    /**
     * Nearest opener before [at] that no close tag has balanced yet.
     *
     * Scanned **forward** on purpose. `**` is both an opener and a closer, so a
     * backward search would mistake the closing pair of a finished run for the
     * start of a new one — that is the bug that makes "type, then tap bold off"
     * append a third marker. Pairing left to right is the only reading that
     * matches how the preview groups markers.
     */
    private fun unmatchedOpener(text: String, at: Int, open: String, close: String): Int? {
        if (open == close) return unclosedToggle(text, at, open)

        // Different markers (`<span …>` / `</span>`): the innermost unclosed span.
        val stack = ArrayList<Int>()
        var cursor = 0
        var steps = 0
        while (cursor < at && steps < MAX_SCAN) {
            steps++
            val nextOpen = text.indexOf(open, cursor).takeIf { it in 0 until at }
            val nextClose = text.indexOf(close, cursor).takeIf { it in 0 until at }

            when {
                nextOpen == null && nextClose == null -> break
                nextOpen != null && (nextClose == null || nextOpen <= nextClose) -> {
                    stack.add(nextOpen)
                    cursor = nextOpen + open.length
                }
                else -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    cursor = nextClose!! + close.length
                }
            }
        }
        return stack.lastOrNull()
    }

    /** Open/close are the same token, so occurrences pair up left to right. */
    private fun unclosedToggle(text: String, at: Int, marker: String): Int? {
        var pending: Int? = null
        var cursor = 0
        var steps = 0
        while (steps < MAX_SCAN) {
            steps++
            val index = text.indexOf(marker, cursor)
            if (index < 0 || index >= at) break
            pending = if (pending == null) index else null
            cursor = index + marker.length
        }
        return pending
    }

    /** Guards the backward walk on pathological input. */
    private const val MAX_SCAN = 512
}
