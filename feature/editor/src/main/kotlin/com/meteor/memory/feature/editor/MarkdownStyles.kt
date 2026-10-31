package com.meteor.memory.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/** Annotation tag carrying the target of a link or image reference. */
internal const val TAG_TARGET = "meteor-target"

/**
 * One inline parser shared by the preview and the editing surface.
 *
 * Supports `**bold**`, `*italic*`, `[label](target)`, `![alt](target)` and
 * `<span style="color:#E53935">text</span>`.
 *
 * **Length invariant**: in editor mode every original character is emitted, in
 * order, so a identity [OffsetMapping] stays valid and the caret does not drift.
 * Dropping markers there would make the transformed text shorter than the input
 * and Compose rejects that with an exception.
 */
object MarkdownStyles {

    private val INLINE = Regex(
        """<span style="color:(#[0-9A-Fa-f]{6})">(.*?)</span>""" +
            """|\*\*([^*]+)\*\*""" +
            """|\*([^*]+)\*""" +
            """|!\[([^\]]*)\]\(([^)]*)\)""" +
            """|\[([^\]]*)\]\(([^)]*)\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * @param imageLabel non-null renders for display (images collapse to the
     *   label, links keep only their text). Null keeps every raw character for
     *   the editor.
     */
    fun annotate(text: String, imageLabel: String?): AnnotatedString = buildAnnotatedString {
        val editorMode = imageLabel == null
        var cursor = 0

        for (match in INLINE.findAll(text)) {
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))

            val groups = match.groupValues
            val colorHex = groups[1]
            val colorText = groups[2]
            val bold = groups[3]
            val italic = groups[4]
            val imageTarget = groups[6]
            val linkLabel = groups[7]
            val linkTarget = groups[8]

            when {
                colorHex.isNotEmpty() -> {
                    val openTag = """<span style="color:$colorHex">"""
                    if (editorMode) append(openTag)
                    pushStyle(SpanStyle(color = parseColor(colorHex)))
                    append(colorText)
                    pop()
                    if (editorMode) append("</span>")
                }

                bold.isNotEmpty() -> {
                    if (editorMode) append("**")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(bold)
                    pop()
                    if (editorMode) append("**")
                }

                italic.isNotEmpty() -> {
                    if (editorMode) append("*")
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(italic)
                    pop()
                    if (editorMode) append("*")
                }

                imageTarget.isNotEmpty() && imageLabel != null -> {
                    val start = length
                    append(imageLabel)
                    addStringAnnotation(TAG_TARGET, imageTarget, start, length)
                }

                imageTarget.isNotEmpty() -> append(match.value)

                linkTarget.isNotEmpty() && imageLabel != null -> {
                    val start = length
                    pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    append(linkLabel)
                    pop()
                    addStringAnnotation(TAG_TARGET, linkTarget, start, length)
                }

                linkTarget.isNotEmpty() -> append(match.value)

                else -> append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

    fun parseColor(hex: String): Color =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Unspecified)
}

/**
 * Styles the editing surface in place while keeping every Markdown character, so
 * typing, selection and the caret all behave like a plain text field.
 */
class MarkdownStyledTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            MarkdownStyles.annotate(text.text, imageLabel = null),
            OffsetMapping.Identity,
        )
}
