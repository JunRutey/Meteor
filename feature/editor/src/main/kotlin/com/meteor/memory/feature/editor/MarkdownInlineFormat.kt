package com.meteor.memory.feature.editor

/**
 * Result of one inline-formatting action: the text to write back plus the
 * selection to restore afterwards.
 *
 * Both offsets are indices into [text] and always lie inside
 * `0..text.length`; they never describe a hidden character, because nothing is
 * hidden (ADR-021).
 */
data class InlineEdit(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Inserts the Markdown markup the preview already understands — `**bold**` and
 * `<span style="color:#RRGGBB">…</span>` — at the caret or around the current
 * selection.
 *
 * This is the path ADR-021 ended with: *add syntax, never hide syntax*. The
 * editing surface stays plain text, so there is no `VisualTransformation`, no
 * offset mapping and therefore none of the caret bugs that took bold out in
 * v0.4. The caller writes [InlineEdit.text] back into the field and applies the
 * returned selection to the text field state.
 *
 * Pure Kotlin (no Android, no Compose) so it is unit-testable on the JVM.
 */
object MarkdownInlineFormat {

    /** Marker pair that the preview renders as bold. */
    private const val BOLD = "**"

    private const val SPAN_CLOSE = "</span>"

    /**
     * Swatches offered by the toolbar, `#RRGGBB` each.
     *
     * The values are the ones `MarkdownStyles.COLOR` accepts (exactly six hex
     * digits); anything else would be written into the body and then shown as
     * literal text in the preview, so nothing outside this shape is produced.
     */
    val COLORS: List<String> = listOf(
        "#E53935",
        "#FB8C00",
        "#43A047",
        "#1E88E5",
        "#8E24AA",
    )

    private val HEX = Regex("#[0-9A-Fa-f]{6}")

    /** Either an opening colour tag (group 1 = colour) or a closing tag. */
    private val TAG = Regex("""<span style="color:(#[0-9A-Fa-f]{6})">|</span>""")

    /**
     * Toggles bold around the selection.
     *
     * - Non-empty selection: wraps it in `**…**`; if the markers already sit
     *   right outside the selection, it removes them instead.
     * - Empty selection: inserts `****` and puts the caret between the two
     *   pairs; pressing again removes the empty pair.
     * - A selection that *is* a complete `**…**` pair is unwrapped.
     *
     * An out-of-range or reversed selection is clamped first, never thrown on.
     */
    fun toggleBold(text: String, selectionStart: Int, selectionEnd: Int): InlineEdit {
        val (start, end) = selection(text, selectionStart, selectionEnd)

        // Markers sit right outside the selection: **|selection|**.
        val wrapped = start >= BOLD.length &&
            end + BOLD.length <= text.length &&
            text.startsWith(BOLD, start - BOLD.length) &&
            text.startsWith(BOLD, end)
        if (wrapped) {
            return InlineEdit(
                text = text.removeRange(end, end + BOLD.length)
                    .removeRange(start - BOLD.length, start),
                selectionStart = start - BOLD.length,
                selectionEnd = end - BOLD.length,
            )
        }

        // The selection itself is a complete pair: **…**
        val pairInside = end - start >= 2 * BOLD.length &&
            text.startsWith(BOLD, start) &&
            text.startsWith(BOLD, end - BOLD.length)
        if (pairInside) {
            // Build the result in one pass: two chained removeRange calls would
            // use indices that the first removal already shifted.
            val innerStart = start + BOLD.length
            val innerEnd = end - BOLD.length
            return InlineEdit(
                text = text.substring(0, start) + text.substring(innerStart, innerEnd) +
                    text.substring(end),
                selectionStart = start,
                selectionEnd = start + (innerEnd - innerStart),
            )
        }

        return InlineEdit(
            text = text.substring(0, start) + BOLD +
                text.substring(start, end) + BOLD +
                text.substring(end),
            selectionStart = start + BOLD.length,
            selectionEnd = end + BOLD.length,
        )
    }

    /**
     * Applies [hex] colour to the selection.
     *
     * - Non-empty selection: wraps it in `<span style="color:#RRGGBB">…</span>`.
     * - Empty selection: inserts an empty span and puts the caret inside it.
     * - The same colour already around the selection is removed (toggle off);
     *   a different colour **replaces** it. A span is never nested inside
     *   another span: `MarkdownStyles` cannot render that, it would print the
     *   inner tag literally.
     * - A selection that already carries span tags is left untouched.
     * - [hex] that is not six hex digits leaves the text untouched.
     */
    fun applyColor(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        hex: String,
    ): InlineEdit {
        val (start, end) = selection(text, selectionStart, selectionEnd)
        val color = normalizeHex(hex) ?: return InlineEdit(text, start, end)
        val open = openTag(color)

        // 1. The selection is exactly the content of a span (what this object inserts).
        val openBefore = openTagEndingAt(text, start)
        if (openBefore != null && text.startsWith(SPAN_CLOSE, end)) {
            val openLength = openBefore.length
            return if (openBefore.color.equals(color, ignoreCase = true)) {
                InlineEdit(
                    text = text.removeRange(end, end + SPAN_CLOSE.length)
                        .removeRange(start - openLength, start),
                    selectionStart = start - openLength,
                    selectionEnd = end - openLength,
                )
            } else {
                InlineEdit(
                    text = text.replaceRange(start - openLength, start, open),
                    selectionStart = start,
                    selectionEnd = end,
                )
            }
        }

        // 2. The selection is one complete span, e.g. selected by hand.
        val literal = text.substring(start, end)
        val wholeTag = openTagStartingAt(literal, 0)
        if (wholeTag != null &&
            literal.endsWith(SPAN_CLOSE) &&
            literal.length >= wholeTag.length + SPAN_CLOSE.length
        ) {
            val openLength = wholeTag.length
            val inner = literal.substring(openLength, literal.length - SPAN_CLOSE.length)
            return if (wholeTag.color.equals(color, ignoreCase = true)) {
                InlineEdit(
                    text = text.substring(0, start) + inner + text.substring(end),
                    selectionStart = start,
                    selectionEnd = start + inner.length,
                )
            } else {
                InlineEdit(
                    text = text.replaceRange(start, start + openLength, open),
                    selectionStart = start,
                    selectionEnd = end,
                )
            }
        }

        // 3. The selection sits somewhere inside a span: recolour that span rather
        //    than wrapping a second one around part of it.
        val enclosing = enclosingSpan(text, start, end)
        if (enclosing != null) {
            return if (enclosing.color.equals(color, ignoreCase = true)) {
                InlineEdit(text, start, end)
            } else {
                InlineEdit(
                    text = text.replaceRange(enclosing.openStart, enclosing.openEnd, open),
                    selectionStart = start,
                    selectionEnd = end,
                )
            }
        }

        // 4. Any span tag left inside the selection would be nested by the wrap
        //    below; leave such a selection alone.
        if (TAG.containsMatchIn(literal)) return InlineEdit(text, start, end)

        // 5. Wrap the selection, or insert an empty pair with the caret inside.
        return InlineEdit(
            text = text.substring(0, start) + open +
                literal + SPAN_CLOSE +
                text.substring(end),
            selectionStart = start + open.length,
            selectionEnd = end + open.length,
        )
    }

    /** Innermost span that opens before [start] and closes after [end]. */
    private fun enclosingSpan(text: String, start: Int, end: Int): OpenSpan? {
        val stack = ArrayList<OpenSpan>()
        for (match in TAG.findAll(text)) {
            val color = match.groupValues[1]
            if (color.isEmpty()) {
                if (stack.isNotEmpty()) {
                    val opened = stack.removeAt(stack.size - 1)
                    if (opened.openEnd <= start && match.range.first >= end) return opened
                }
            } else {
                stack.add(OpenSpan(color, match.range.first, match.range.last + 1))
            }
        }
        return null
    }

    private class OpenSpan(val color: String, val openStart: Int, val openEnd: Int)

    /** An opening colour tag: its colour, its literal text and that text's length. */
    private class OpenTag(val color: String, val length: Int)

    /**
     * Opening colour tag whose last character sits right before [at], if any.
     *
     * Matches are copied into a small value object immediately: a lazily
     * evaluated `MatchResult` must not outlive the iteration that produced it.
     */
    private fun openTagEndingAt(text: String, at: Int): OpenTag? {
        var candidate: OpenTag? = null
        var candidateEnd = -1
        for (match in TAG.findAll(text)) {
            if (match.range.last + 1 > at) break
            if (match.groupValues[1].isNotEmpty()) {
                candidate = OpenTag(match.groupValues[1], match.value.length)
                candidateEnd = match.range.last + 1
            }
        }
        return candidate?.takeIf { candidateEnd == at }
    }

    /** Opening colour tag that starts exactly at [from], if any. */
    private fun openTagStartingAt(text: String, from: Int): OpenTag? {
        val match = TAG.find(text, from) ?: return null
        if (match.range.first != from || match.groupValues[1].isEmpty()) return null
        return OpenTag(match.groupValues[1], match.value.length)
    }

    /** Clamps either end into the text and orders the pair. */
    private fun selection(text: String, selectionStart: Int, selectionEnd: Int): Pair<Int, Int> {
        val a = selectionStart.coerceIn(0, text.length)
        val b = selectionEnd.coerceIn(0, text.length)
        return minOf(a, b) to maxOf(a, b)
    }

    /** `#RRGGBB`, upper case; `null` when [hex] cannot be rendered by the preview. */
    private fun normalizeHex(hex: String): String? {
        val trimmed = hex.trim()
        if (trimmed.isEmpty()) return null
        val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        if (!HEX.matches(withHash)) return null
        return withHash.uppercase()
    }

    private fun openTag(color: String): String = """<span style="color:$color">"""
}
