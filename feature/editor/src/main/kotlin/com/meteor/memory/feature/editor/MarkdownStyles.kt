package com.meteor.memory.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Annotation tag carrying the target of a link or image reference. */
internal const val TAG_TARGET = "meteor-target"

/**
 * Inline parser shared by the preview and the editing surface.
 *
 * Markup nests, so `**<span style="color:#E53935">红粗</span>**` renders bold *and*
 * red: a match recurses into its own content instead of guessing with one flat
 * regex.
 *
 * **Length invariant**: in editor mode every original character is emitted in
 * order, so [OffsetMapping.Identity] stays valid and the caret cannot drift.
 */
object MarkdownStyles {

    private val COLOR = Regex("""<span style="color:(#[0-9A-Fa-f]{6})">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE = Regex("""!\[([^\]]*)\]\(([^)]*)\)""")
    private val LINK = Regex("""\[([^\]]*)\]\(([^)]*)\)""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val ITALIC = Regex("""\*([^*]+?)\*""")

    /**
     * @param imageLabel non-null renders for display (images collapse to this
     *   label, links keep only their text). Null keeps raw characters for the
     *   editor.
     */
    fun annotate(text: String, imageLabel: String?): AnnotatedString = buildAnnotatedString {
        appendRange(text, 0, text.length, imageLabel, editorMode = imageLabel == null)
    }

    private fun AnnotatedString.Builder.appendRange(
        text: String,
        from: Int,
        to: Int,
        imageLabel: String?,
        editorMode: Boolean,
    ) {
        var cursor = from
        while (cursor < to) {
            val match = earliest(text, cursor, to) ?: run {
                append(text.substring(cursor, to))
                return
            }

            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))

            when (match.kind) {
                Kind.COLOR -> {
                    val innerStart = match.groupStart
                    val innerEnd = match.groupEnd
                    pushStyle(SpanStyle(color = parseColor(match.extra)))
                    if (editorMode) append(text.substring(match.range.first, innerStart))
                    appendRange(text, innerStart, innerEnd, imageLabel, editorMode)
                    if (editorMode) append(text.substring(innerEnd, match.range.last + 1))
                    pop()
                }

                Kind.BOLD -> {
                    val innerStart = match.groupStart
                    val innerEnd = match.groupEnd
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    if (editorMode) append(text.substring(match.range.first, innerStart))
                    appendRange(text, innerStart, innerEnd, imageLabel, editorMode)
                    if (editorMode) append(text.substring(innerEnd, match.range.last + 1))
                    pop()
                }

                Kind.ITALIC -> {
                    val innerStart = match.groupStart
                    val innerEnd = match.groupEnd
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    if (editorMode) append(text.substring(match.range.first, innerStart))
                    appendRange(text, innerStart, innerEnd, imageLabel, editorMode)
                    if (editorMode) append(text.substring(innerEnd, match.range.last + 1))
                    pop()
                }

                Kind.IMAGE -> {
                    if (imageLabel != null) {
                        val start = length
                        append(if (match.extra.isBlank()) imageLabel else imageLabel)
                        addStringAnnotation(TAG_TARGET, match.target, start, length)
                    } else {
                        append(text.substring(match.range.first, match.range.last + 1))
                    }
                }

                Kind.LINK -> {
                    if (imageLabel != null) {
                        val start = length
                        pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                        append(match.label)
                        pop()
                        addStringAnnotation(TAG_TARGET, match.target, start, length)
                    } else {
                        append(text.substring(match.range.first, match.range.last + 1))
                    }
                }
            }
            cursor = match.range.last + 1
        }
    }

    internal enum class Kind { COLOR, BOLD, ITALIC, IMAGE, LINK }

    internal class InlineMatch(
        val kind: Kind,
        val range: IntRange,
        val groupStart: Int = 0,
        val groupEnd: Int = 0,
        val extra: String = "",
        val label: String = "",
        val target: String = "",
    )

    /** Earliest match starting at or after [from] and ending before [to]. */
    internal fun earliest(text: String, from: Int, to: Int): InlineMatch? {
        val candidates = listOfNotNull(
            COLOR.find(text, from)?.takeIf { it.range.last < to }?.let {
                val inner = it.groups[2]!!
                InlineMatch(
                    kind = Kind.COLOR,
                    range = it.range,
                    groupStart = inner.range.first,
                    groupEnd = inner.range.last + 1,
                    extra = it.groupValues[1],
                )
            },
            IMAGE.find(text, from)?.takeIf { it.range.last < to }?.let {
                InlineMatch(
                    kind = Kind.IMAGE,
                    range = it.range,
                    label = it.groupValues[1],
                    target = it.groupValues[2],
                )
            },
            LINK.find(text, from)?.takeIf { it.range.last < to }?.let {
                InlineMatch(
                    kind = Kind.LINK,
                    range = it.range,
                    label = it.groupValues[1],
                    target = it.groupValues[2],
                )
            },
            BOLD.find(text, from)?.takeIf { it.range.last < to }?.let {
                val inner = it.groups[1]!!
                InlineMatch(
                    kind = Kind.BOLD,
                    range = it.range,
                    groupStart = inner.range.first,
                    groupEnd = inner.range.last + 1,
                )
            },
            ITALIC.find(text, from)?.takeIf { it.range.last < to }?.let {
                val inner = it.groups[1]!!
                InlineMatch(
                    kind = Kind.ITALIC,
                    range = it.range,
                    groupStart = inner.range.first,
                    groupEnd = inner.range.last + 1,
                )
            },
        )
        return candidates.minByOrNull { it.range.first }
    }

    fun parseColor(hex: String): Color =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Unspecified)
}
