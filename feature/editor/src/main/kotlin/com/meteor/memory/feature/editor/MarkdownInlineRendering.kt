package com.meteor.memory.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The editing surface's markup is **hidden**: `**` and `<span …>` disappear and
 * the text they wrap is drawn styled instead (ADR-051).
 *
 * Hiding characters makes the rendered text shorter than the source, so a real
 * [OffsetMapping] is mandatory — without one the caret jumps on every keystroke
 * next to a marker. That bug is exactly what took bold out in v0.4, which is why
 * the mapping below is written the same conservative way the old
 * `EditorRendering` was (one array entry per source offset, one per rendered
 * character, both clamped at the edges) and is unit-tested at the boundaries.
 *
 * **Unclosed markers count as open.** While a sticky format is active the field
 * is mid-typing: `**你` has no closing pair yet, so the marker styles everything
 * after it to the end of the text block. That keeps typing live instead of
 * snapping back to plain text until the user turns the format off.
 */
class MarkdownInlineRendering internal constructor(
    val text: AnnotatedString,
    val mapping: OffsetMapping,
) {
    companion object {
        fun of(source: String): MarkdownInlineRendering = Builder(source).build()
    }
}

/** Applies [MarkdownInlineRendering] to a text field. */
class MarkdownStyledTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val rendered = MarkdownInlineRendering.of(text.text)
        return TransformedText(rendered.text, rendered.mapping)
    }
}

private class Builder(private val source: String) {

    private val out = StringBuilder(source.length)
    private val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()

    /** Source offset → rendered offset. One extra slot for the end. */
    private val originalToTransformed = IntArray(source.length + 1)

    /** Rendered offset → source offset. One extra slot for the end. */
    private val transformedToOriginal = ArrayList<Int>(source.length + 1)

    private val length = source.length

    fun build(): MarkdownInlineRendering {
        walk(0, length)
        // Both mappings must describe the final position even when the text ends
        // inside a hidden marker.
        originalToTransformed[length] = out.length
        transformedToOriginal.add(length)

        val annotated = buildAnnotatedString {
            append(out.toString())
            spans.forEach { (start, end, style) ->
                if (end > start) addStyle(style, start, end)
            }
        }
        return MarkdownInlineRendering(
            annotated,
            Mapping(originalToTransformed, transformedToOriginal),
        )
    }

    private fun walk(from: Int, to: Int) {
        var cursor = from
        while (cursor < to) {
            val marker = nextMarker(cursor, to)
            if (marker == null) break

            if (marker.start > cursor) plain(cursor, marker.start)

            hide(marker.start, marker.contentStart)

            val close = marker.closeWithin(source, marker.contentStart, to)
            val contentEnd = close?.from ?: to

            val styledFrom = out.length
            walk(marker.contentStart, contentEnd)
            spans += Triple(styledFrom, out.length, marker.style)

            if (close != null) {
                hide(close.from, close.to)
                cursor = close.to
            } else {
                // Unclosed: the marker styles the rest of the range.
                cursor = to
            }
        }
        if (cursor < to) plain(cursor, to)
    }

    private fun plain(from: Int, to: Int) {
        for (index in from until to) {
            originalToTransformed[index] = out.length
            out.append(source[index])
            transformedToOriginal.add(index)
        }
    }

    /**
     * Hidden characters map onto the output position they were removed from, so
     * a caret placed "on" a marker lands right at its edge instead of jumping.
     */
    private fun hide(from: Int, to: Int) {
        for (index in from until to) originalToTransformed[index] = out.length
    }

    /** Earliest opening marker starting at or after [from]. */
    private fun nextMarker(from: Int, to: Int): Marker? {
        val bold = source.indexOf(BOLD, from).takeIf { it >= 0 && it < to }
        val span = SPAN_OPEN.find(source, from)?.takeIf { it.range.first < to }

        return when {
            bold == null && span == null -> null

            span == null -> Marker(
                start = bold!!,
                contentStart = bold + BOLD.length,
                style = BOLD_STYLE,
                closeTag = BOLD,
            )

            bold == null -> Marker(
                start = span!!.range.first,
                contentStart = span.range.last + 1,
                style = SpanStyle(color = parseColor(span.groupValues[1])),
                closeTag = SPAN_CLOSE,
                closesNested = true,
            )

            bold!! <= span.range.first -> Marker(
                start = bold,
                contentStart = bold + BOLD.length,
                style = BOLD_STYLE,
                closeTag = BOLD,
            )

            else -> Marker(
                start = span.range.first,
                contentStart = span.range.last + 1,
                style = SpanStyle(color = parseColor(span.groupValues[1])),
                closeTag = SPAN_CLOSE,
                closesNested = true,
            )
        }
    }

    /** A closing tag's extent in the source: `[from, to)`. */
    private class ClosingTag(val from: Int, val to: Int)

    private inner class Marker(
        val start: Int,
        val contentStart: Int,
        val style: SpanStyle,
        val closeTag: String,
        val closesNested: Boolean = false,
    ) {
        /**
         * The closing tag after [contentStart], if the author has typed one.
         *
         * `<span>` nests, so the closing tag is only accepted when every inner
         * `<span` has been closed first; otherwise the outer span would end at
         * the inner one and the remaining text would lose its colour.
         */
        fun closeWithin(text: String, from: Int, to: Int): ClosingTag? {
            if (!closesNested) {
                val index = text.indexOf(closeTag, from)
                return if (index >= 0 && index + closeTag.length <= to) {
                    ClosingTag(index, index + closeTag.length)
                } else {
                    null
                }
            }
            var depth = 0
            var cursor = from
            while (cursor < to) {
                val open = SPAN_OPEN.find(text, cursor)
                val close = text.indexOf(SPAN_CLOSE, cursor)
                val hasOpen = open != null && open.range.first < to
                val hasClose = close >= 0 && close + SPAN_CLOSE.length <= to

                if (!hasOpen && !hasClose) return null

                if (hasOpen && (!hasClose || open!!.range.first < close)) {
                    depth++
                    cursor = open!!.range.last + 1
                } else {
                    if (depth == 0) return ClosingTag(close, close + SPAN_CLOSE.length)
                    depth--
                    cursor = close + SPAN_CLOSE.length
                }
            }
            return null
        }
    }

    /**
     * Both directions are clamped into range before indexing: Compose (and the
     * IME) occasionally asks for an offset one past the text while a composition
     * is being replaced, and throwing there would crash the screen.
     */
    private class Mapping(
        private val originalToTransformed: IntArray,
        private val transformedToOriginal: List<Int>,
    ) : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int =
            originalToTransformed[offset.coerceIn(0, originalToTransformed.lastIndex)]

        override fun transformedToOriginal(offset: Int): Int =
            transformedToOriginal[offset.coerceIn(0, transformedToOriginal.lastIndex)]
    }

    private companion object {
        const val BOLD = "**"

        const val SPAN_CLOSE = "</span>"

        val SPAN_OPEN = Regex("""<span style="color:(#[0-9A-Fa-f]{6})">""")

        val BOLD_STYLE = SpanStyle(fontWeight = FontWeight.Bold)

        /** Same parsing as the preview, so both agree on what a colour is. */
        fun parseColor(hex: String): Color =
            runCatching { Color(android.graphics.Color.parseColor(hex)) }
                .getOrDefault(Color.Unspecified)
    }
}
