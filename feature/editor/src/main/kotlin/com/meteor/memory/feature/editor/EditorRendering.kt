package com.meteor.memory.feature.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Renders the editing surface without the markup characters — true WYSIWYG for
 * **bold**, which is the only inline style the editor still offers (ADR-019).
 *
 * Hiding characters means the transformed text is shorter than the source, so a
 * real [OffsetMapping] is mandatory: without it the caret jumps every time the
 * user types next to a hidden marker.
 *
 * Image and link syntax stays visible on purpose — those are the parts a user
 * needs to edit or delete. Same for the retired `<span>`/`*italic*` markup:
 * leaving it as literal text keeps it removable, and the preview still renders
 * it for older records.
 */
class EditorRendering internal constructor(
    val text: AnnotatedString,
    val mapping: OffsetMapping,
) {
    companion object {
        fun of(source: String): EditorRendering = Builder(source).build()
    }
}

private class Builder(private val source: String) {

    private val out = StringBuilder(source.length)
    private val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()
    private val originalToTransformed = IntArray(source.length + 1)
    private val transformedToOriginal = ArrayList<Int>(source.length + 1)
    private val length = source.length

    fun build(): EditorRendering {
        walk(0, length)
        originalToTransformed[length] = out.length
        transformedToOriginal.add(length)

        val annotated = buildAnnotatedString {
            append(out.toString())
            spans.forEach { (start, end, style) ->
                if (end > start) addStyle(style, start, end)
            }
        }
        return EditorRendering(annotated, Mapping(originalToTransformed, transformedToOriginal))
    }

    private fun walk(from: Int, to: Int) {
        var cursor = from
        while (cursor < to) {
            val match = MarkdownStyles.earliest(source, cursor, to, STYLED_KINDS)
            if (match == null) {
                plain(cursor, to)
                return
            }
            if (match.range.first > cursor) plain(cursor, match.range.first)

            hide(match.range.first, match.groupStart)
            val start = out.length
            walk(match.groupStart, match.groupEnd)
            spans += Triple(start, out.length, BOLD)
            hide(match.groupEnd, match.range.last + 1)

            cursor = match.range.last + 1
        }
    }

    private fun plain(from: Int, to: Int) {
        for (index in from until to) {
            originalToTransformed[index] = out.length
            out.append(source[index])
            transformedToOriginal.add(index)
        }
    }

    /** Maps hidden characters onto the current output position. */
    private fun hide(from: Int, to: Int) {
        for (index in from until to) {
            originalToTransformed[index] = out.length
        }
    }

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
        /** Only bold is hidden; every other construct is shown as written. */
        val STYLED_KINDS = setOf(MarkdownStyles.Kind.BOLD)
        val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
    }
}

/** Applies [EditorRendering] to a text field. */
class MarkdownStyledTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val rendered = EditorRendering.of(text.text)
        return TransformedText(rendered.text, rendered.mapping)
    }
}
