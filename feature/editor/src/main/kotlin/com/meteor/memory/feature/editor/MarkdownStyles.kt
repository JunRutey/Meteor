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
 * Inline parser behind the **read-only preview**.
 *
 * Markup nests, so `**<span style="color:#E53935">红粗</span>**` renders bold *and*
 * red: a match recurses into its own content instead of guessing with one flat
 * regex.
 *
 * Colour and italic are still parsed even though the toolbar no longer offers
 * them (ADR-019 §3): records written by v0.4-rc and earlier keep their look.
 * The editing surface deliberately does **not** use this parser — see
 * [EditorRendering].
 */
object MarkdownStyles {

    private val COLOR = Regex("""<span style="color:(#[0-9A-Fa-f]{6})">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE = Regex("""!\[([^\]]*)]\(([^)]*)\)""")
    private val LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val ITALIC = Regex("""\*([^*]+?)\*""")

    /**
     * @param imageLabel text that replaces an image reference; links keep only
     *   their label.
     */
    fun annotate(text: String, imageLabel: String): AnnotatedString = buildAnnotatedString {
        appendRange(text, 0, text.length, imageLabel)
    }

    private fun AnnotatedString.Builder.appendRange(
        text: String,
        from: Int,
        to: Int,
        imageLabel: String,
    ) {
        var cursor = from
        while (cursor < to) {
            val match = earliest(text, cursor, to) ?: run {
                append(text.substring(cursor, to))
                return
            }

            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))

            when (match.kind) {
                Kind.COLOR -> styled(match, text, imageLabel, SpanStyle(color = parseColor(match.extra)))
                Kind.BOLD -> styled(match, text, imageLabel, SpanStyle(fontWeight = FontWeight.Bold))
                Kind.ITALIC -> styled(match, text, imageLabel, SpanStyle(fontStyle = FontStyle.Italic))

                Kind.IMAGE -> {
                    val start = length
                    append(imageLabel)
                    addStringAnnotation(TAG_TARGET, match.target, start, length)
                }

                Kind.LINK -> {
                    val start = length
                    pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    append(match.label)
                    pop()
                    addStringAnnotation(TAG_TARGET, match.target, start, length)
                }
            }
            cursor = match.range.last + 1
        }
    }

    /** Renders the inner range with [style], leaving the markers out. */
    private fun AnnotatedString.Builder.styled(
        match: InlineMatch,
        text: String,
        imageLabel: String,
        style: SpanStyle,
    ) {
        pushStyle(style)
        appendRange(text, match.groupStart, match.groupEnd, imageLabel)
        pop()
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

    /**
     * Earliest match starting at or after [from] and ending before [to].
     *
     * [kinds] narrows the scan: the editing surface only asks for [Kind.BOLD] and
     * lets every other construct through as literal text.
     */
    internal fun earliest(
        text: String,
        from: Int,
        to: Int,
        kinds: Set<Kind> = ALL_KINDS,
    ): InlineMatch? {
        val candidates = buildList {
            if (Kind.COLOR in kinds) {
                COLOR.find(text, from)?.takeIf { it.range.last < to }?.let {
                    val inner = it.groups[2]!!
                    add(
                        InlineMatch(
                            kind = Kind.COLOR,
                            range = it.range,
                            groupStart = inner.range.first,
                            groupEnd = inner.range.last + 1,
                            extra = it.groupValues[1],
                        ),
                    )
                }
            }
            if (Kind.IMAGE in kinds) {
                IMAGE.find(text, from)?.takeIf { it.range.last < to }?.let {
                    add(
                        InlineMatch(
                            kind = Kind.IMAGE,
                            range = it.range,
                            label = it.groupValues[1],
                            target = it.groupValues[2],
                        ),
                    )
                }
            }
            if (Kind.LINK in kinds) {
                LINK.find(text, from)?.takeIf { it.range.last < to }?.let {
                    add(
                        InlineMatch(
                            kind = Kind.LINK,
                            range = it.range,
                            label = it.groupValues[1],
                            target = it.groupValues[2],
                        ),
                    )
                }
            }
            if (Kind.BOLD in kinds) {
                BOLD.find(text, from)?.takeIf { it.range.last < to }?.let {
                    val inner = it.groups[1]!!
                    add(
                        InlineMatch(
                            kind = Kind.BOLD,
                            range = it.range,
                            groupStart = inner.range.first,
                            groupEnd = inner.range.last + 1,
                        ),
                    )
                }
            }
            if (Kind.ITALIC in kinds) {
                ITALIC.find(text, from)?.takeIf { it.range.last < to }?.let {
                    val inner = it.groups[1]!!
                    add(
                        InlineMatch(
                            kind = Kind.ITALIC,
                            range = it.range,
                            groupStart = inner.range.first,
                            groupEnd = inner.range.last + 1,
                        ),
                    )
                }
            }
        }
        return candidates.minByOrNull { it.range.first }
    }

    private val ALL_KINDS: Set<Kind> = Kind.entries.toSet()

    fun parseColor(hex: String): Color =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Unspecified)
}
