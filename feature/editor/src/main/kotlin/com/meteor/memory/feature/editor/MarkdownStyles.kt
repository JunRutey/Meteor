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
 * One inline parser shared by the preview and the editing surface, so what the
 * user sees while typing matches what the preview renders.
 *
 * Supported syntax:
 * ```
 * **bold**  *italic*  [label](target)  ![alt](target)
 * <span style="color:#E53935">red text</span>
 * ```
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
     * @param imageLabel when non-null the styled text is for display: images
     *   collapse to this label and links keep only their label. When null the
     *   text is for the editor and every raw character is preserved.
     */
    fun annotate(text: String, imageLabel: String?): AnnotatedString = buildAnnotatedString {
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
                    pushStyle(SpanStyle(color = parseColor(colorHex)))
                    append(colorText)
                    pop()
                }

                bold.isNotEmpty() -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(bold)
                    pop()
                }

                italic.isNotEmpty() -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(italic)
                    pop()
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

                linkTarget.isNotEmpty() -> {
                    // Editor keeps the raw syntax so offsets stay identical.
                    val start = length
                    append(match.value)
                    addStyle(
                        SpanStyle(textDecoration = TextDecoration.Underline),
                        start + 1,
                        length - (linkTarget.length + 2),
                    )
                }

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
 * Styled editing surface: bold/italic/colour render in place while the raw
 * Markdown characters stay in the model.
 *
 * Markers remain visible on purpose — hiding them needs offset remapping, which
 * is the remaining step toward true WYSIWYG (see ADR-019).
 */
class MarkdownStyledTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            MarkdownStyles.annotate(text.text, imageLabel = null),
            OffsetMapping.Identity,
        )
}
