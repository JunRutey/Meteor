package com.meteor.memory.feature.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/**
 * Theme values the renderer borrows from its caller (ADR-050).
 *
 * [AiReplyMarkdown] is plain Kotlin on purpose and never reads `MaterialTheme`
 * itself, so the whole parser runs in an ordinary JVM unit test. Every value is
 * "unspecified" by default, which means "leave it to the `Text` that draws the
 * result".
 */
data class AiReplyStyle(
    /**
     * Font sizes for heading levels 1, 2, 3, …; the last entry is reused for
     * deeper levels. An empty list keeps headings at body size (still bold).
     */
    val headingFontSizes: List<TextUnit> = emptyList(),
    /** Background behind inline code and fenced code blocks. */
    val codeBackground: Color = Color.Unspecified,
    /** Colour of a link's visible label. */
    val linkColor: Color = Color.Unspecified,
    /** Colour of a horizontal rule and other de-emphasised glyphs. */
    val mutedColor: Color = Color.Unspecified,
)

/**
 * Read-only Markdown renderer for one AI reply (ADR-050).
 *
 * The model answers in Markdown, so a raw `Text(bubble.text)` shows the markers
 * verbatim. This turns the common inline and block markers into spans of a
 * single [AnnotatedString], which `Text` draws directly.
 *
 * Two rules govern every decision here:
 *
 * 1. **Nothing readable is ever dropped.** A marker is consumed only when it is
 *    complete; anything the parser does not understand stays in the output as
 *    literal text. A half-streamed reply (`**abc`, an unclosed ` ``` `) therefore
 *    shows its characters instead of eating them.
 * 2. **Markup nests.** A match recurses into its own content, so
 *    `**<span style="color:#E53935">红粗</span>**` is bold *and* red instead of
 *    being flattened by a single regex pass.
 *
 * This is a deliberate second implementation of `feature-editor`'s
 * `MarkdownStyles`: `:feature-ai` must not depend on `:feature-editor`
 * (archive §3 / R1), and this one renders a whole reply into one string instead
 * of feeding an interactive preview.
 */
object AiReplyMarkdown {

    /** What a `- `, `* ` or `+ ` line becomes. */
    private const val BULLET = "• "

    /** Fenced code blocks open and close with this prefix. */
    private const val FENCE = "```"

    /**
     * A rule has to fit inside one `Text`: a text run cannot measure the bubble
     * it will be laid out in, so it is a fixed-length line rather than a
     * `HorizontalDivider`.
     */
    private const val RULE_TEXT = "──────────────────"

    /** Deepest inline nesting; beyond this the rest is copied verbatim. */
    private const val MAX_NESTING = 8

    private val HEADING = Regex("""^(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$""")
    private val BULLET_LINE = Regex("""^[-*+][ \t]+(.*)$""")
    private val ORDERED_LINE = Regex("""^(\d{1,9}[.)])[ \t]+(.*)$""")
    private val RULE_LINE = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    /** A bare code-fence language tag, which is metadata rather than prose. */
    private val LANGUAGE_TAG = Regex("""^[A-Za-z0-9_+#.-]{1,24}$""")

    private val COLOR_SPAN = Regex(
        """<span\s+style\s*=\s*["']\s*color\s*:\s*(#[0-9A-Fa-f]{6})\s*["']\s*>(.*?)</span>""",
    )
    private val INLINE_CODE = Regex("""`([^`]+)`""")
    private val IMAGE = Regex("""!\[([^\]]*)]\(([^)]*)\)""")
    private val LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")
    private val BOLD_ITALIC = Regex("""\*\*\*(.+?)\*\*\*""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")

    /**
     * The lookbehind keeps a single `*` from opening an italic run in the middle
     * of a `**` pair: streaming `**a` then `*` would otherwise flicker from
     * `**a` to `*a`, and `***` belongs to [BOLD_ITALIC].
     */
    private val ITALIC = Regex("""(?<!\*)\*([^*]+?)\*""")

    /**
     * Renders [text] into one [AnnotatedString].
     *
     * Safe for a reply that is still streaming: an incomplete marker is not a
     * marker, so the visible characters always survive.
     */
    fun render(text: String, style: AiReplyStyle = AiReplyStyle()): AnnotatedString {
        // A streamed reply often ends in a newline, which would otherwise leave
        // a trailing blank line inside the bubble. Only whitespace is removed.
        val source = text.trimEnd()
        return buildAnnotatedString { appendReply(source, style) }
    }

    // ----------------------------------------------------------------- blocks

    private fun AnnotatedString.Builder.appendReply(text: String, style: AiReplyStyle) {
        val lines = text.lines()

        // Every branch below writes "a newline, then this line's content", but
        // only once something already exists — so a reply never starts with a
        // blank line, whatever the model emitted first.
        val newline: () -> Unit = { if (length > 0) append("\n") }

        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            index++

            if (trimmed.startsWith(FENCE)) {
                index = appendFenced(trimmed, lines, index, style, newline)
                continue
            }

            if (trimmed.isEmpty()) {
                newline()
                continue
            }

            if (RULE_LINE.matches(trimmed)) {
                newline()
                pushStyle(SpanStyle(color = style.mutedColor))
                append(RULE_TEXT)
                pop()
                continue
            }

            val heading = HEADING.find(trimmed)
            if (heading != null) {
                newline()
                pushStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = headingFontSize(style, heading.groupValues[1].length),
                    ),
                )
                heading.groups[2]?.let { appendInline(it.value, style, depth = 0) }
                pop()
                continue
            }

            val bullet = BULLET_LINE.find(trimmed)
            if (bullet != null) {
                newline()
                // Keep the model's indentation so nested items stay nested.
                append(indentationOf(raw))
                append(BULLET)
                bullet.groups[1]?.let { appendInline(it.value, style, depth = 0) }
                continue
            }

            val ordered = ORDERED_LINE.find(trimmed)
            if (ordered != null) {
                newline()
                append(indentationOf(raw))
                append(ordered.groupValues[1])
                append(" ")
                ordered.groups[2]?.let { appendInline(it.value, style, depth = 0) }
                continue
            }

            newline()
            appendInline(raw, style, depth = 0)
        }
    }

    /** Leading whitespace of [raw]; the marker itself is replaced. */
    private fun indentationOf(raw: String): String =
        raw.take(raw.length - raw.trimStart().length).filter { it == ' ' || it == '\t' }

    /**
     * Appends one fenced code block and returns the index of the first line
     * after it. Markup inside the block is not parsed — code is shown as code.
     *
     * A plain language tag (` ```kotlin `) is metadata: it styles the block and
     * is deliberately not shown as prose. Anything else after the fence opener
     * is kept as the block's first line, because it is text a reader can see.
     */
    private fun AnnotatedString.Builder.appendFenced(
        fenceLine: String,
        lines: List<String>,
        index: Int,
        style: AiReplyStyle,
        newline: () -> Unit,
    ): Int {
        val codeStyle = SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = style.codeBackground,
        )
        val info = fenceLine.substring(FENCE.length)

        // ```code``` on one line: an info string cannot contain a fence, so
        // everything before the second fence is the block's own content.
        if (info.contains(FENCE)) {
            newline()
            pushStyle(codeStyle)
            append(info.substringBefore(FENCE).trim())
            pop()
            return index
        }

        val infoTag = info.trim()
        if (infoTag.isNotEmpty() && !LANGUAGE_TAG.matches(infoTag)) {
            newline()
            pushStyle(codeStyle)
            append(infoTag)
            pop()
        }

        var cursor = index
        while (cursor < lines.size && !lines[cursor].trimStart().startsWith(FENCE)) {
            newline()
            pushStyle(codeStyle)
            append(lines[cursor])
            pop()
            cursor++
        }
        // An unclosed fence (the reply is still streaming) simply runs to the
        // end of the text; there is no closing line to skip.
        return if (cursor < lines.size) cursor + 1 else cursor
    }

    private fun headingFontSize(style: AiReplyStyle, level: Int): TextUnit =
        style.headingFontSizes.getOrElse(level - 1) { TextUnit.Unspecified }

    // ----------------------------------------------------------------- inline

    /**
     * Appends [text] — one line, or the content of one outer marker — turning
     * recognised markers into spans and copying everything else through
     * verbatim.
     *
     * Recursion works on substrings rather than on index ranges, so a nested
     * marker can only ever see its own content: there is no way for an inner
     * match to reach past the outer marker and swallow text.
     */
    private fun AnnotatedString.Builder.appendInline(
        text: String,
        style: AiReplyStyle,
        depth: Int,
    ) {
        if (depth >= MAX_NESTING) {
            append(text)
            return
        }

        var cursor = 0
        while (cursor < text.length) {
            val match = earliest(text, cursor)
            if (match == null) {
                append(text.substring(cursor))
                return
            }
            if (match.start > cursor) append(text.substring(cursor, match.start))

            when (match.kind) {
                Kind.BOLD -> pushStyled(SpanStyle(fontWeight = FontWeight.Bold), match, text, style, depth)
                Kind.ITALIC -> pushStyled(SpanStyle(fontStyle = FontStyle.Italic), match, text, style, depth)
                Kind.BOLD_ITALIC -> pushStyled(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                    match,
                    text,
                    style,
                    depth,
                )

                Kind.COLOR -> pushStyled(SpanStyle(color = match.color), match, text, style, depth)

                Kind.LINK -> pushStyled(
                    SpanStyle(color = style.linkColor, textDecoration = TextDecoration.Underline),
                    match,
                    text,
                    style,
                    depth,
                )

                // An image reference keeps its alt text: a reply has no picture
                // to show, and the alt text is the only readable part of it.
                Kind.IMAGE -> appendInline(
                    text = text.substring(match.contentStart, match.contentEnd),
                    style = style,
                    depth = depth + 1,
                )

                Kind.CODE -> {
                    // Code is literal: no marker inside it is parsed.
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = style.codeBackground,
                        ),
                    )
                    append(text.substring(match.contentStart, match.contentEnd))
                    pop()
                }
            }
            cursor = match.end
        }
    }

    /** Renders the inner range of [match] with [span], recursing into it. */
    private fun AnnotatedString.Builder.pushStyled(
        span: SpanStyle,
        match: InlineMatch,
        text: String,
        style: AiReplyStyle,
        depth: Int,
    ) {
        pushStyle(span)
        appendInline(
            text = text.substring(match.contentStart, match.contentEnd),
            style = style,
            depth = depth + 1,
        )
        pop()
    }

    /**
     * Earliest marker starting at or after [from].
     *
     * At the same start the longer marker wins, which is what makes `***` beat
     * `**` and `**` beat `*` without a second pass.
     */
    private fun earliest(text: String, from: Int): InlineMatch? {
        var best: InlineMatch? = null

        fun offer(candidate: InlineMatch?) {
            if (candidate == null) return
            if (candidate.end <= candidate.start) return
            val current = best
            best = when {
                current == null -> candidate
                candidate.start < current.start -> candidate
                candidate.start == current.start && candidate.end > current.end -> candidate
                else -> current
            }
        }

        COLOR_SPAN.find(text, from)?.let { match ->
            val inner = match.groups[2]
            if (inner != null && inner.value.isNotEmpty()) {
                offer(
                    InlineMatch(
                        kind = Kind.COLOR,
                        start = match.range.first,
                        end = match.range.last + 1,
                        contentStart = inner.range.first,
                        contentEnd = inner.range.last + 1,
                        color = parseColor(match.groupValues[1]),
                    ),
                )
            }
        }

        INLINE_CODE.find(text, from)?.let { offer(emphasised(Kind.CODE, it, it.groups[1])) }

        // A link or image with nothing between the brackets has no readable
        // part: leaving it unrecognised keeps the whole source on screen.
        IMAGE.find(text, from)?.let { offer(emphasised(Kind.IMAGE, it, it.groups[1])) }
        LINK.find(text, from)?.let { offer(emphasised(Kind.LINK, it, it.groups[1])) }

        BOLD_ITALIC.find(text, from)?.let { offer(emphasised(Kind.BOLD_ITALIC, it, it.groups[1])) }
        BOLD.find(text, from)?.let { offer(emphasised(Kind.BOLD, it, it.groups[1])) }
        ITALIC.find(text, from)?.let { offer(emphasised(Kind.ITALIC, it, it.groups[1])) }

        return best
    }

    /**
     * A match whose inner range is rendered with a style, or `null` when the
     * match must stay literal.
     *
     * Emphasis is rejected when its content is blank or padded with whitespace,
     * so ordinary prose such as `2 * 3 * 4` is not mistaken for italics. Links
     * and images are rejected only when they have no visible text at all.
     */
    private fun emphasised(kind: Kind, match: MatchResult, content: MatchGroup?): InlineMatch? {
        if (content == null) return null
        val inner = content.value
        if (inner.isEmpty()) return null
        val needsPaddedCheck = kind == Kind.BOLD ||
            kind == Kind.ITALIC ||
            kind == Kind.BOLD_ITALIC
        if (needsPaddedCheck && (inner.first().isWhitespace() || inner.last().isWhitespace())) {
            return null
        }
        return InlineMatch(
            kind = kind,
            start = match.range.first,
            end = match.range.last + 1,
            contentStart = content.range.first,
            contentEnd = content.range.last + 1,
        )
    }

    /**
     * Parses `#RRGGBB` without touching `android.graphics`, which keeps the
     * whole parser unit-testable on the JVM.
     */
    private fun parseColor(hex: String): Color {
        val digits = hex.removePrefix("#")
        if (digits.length != 6) return Color.Unspecified
        var value = 0L
        for (digit in digits) {
            val part = digit.digitToIntOrNull(radix = 16) ?: return Color.Unspecified
            value = value * 16 + part
        }
        return Color(0xFF000000L or value)
    }

    private enum class Kind { BOLD, ITALIC, BOLD_ITALIC, CODE, LINK, IMAGE, COLOR }

    /** One recognised marker; [end] and [contentEnd] are exclusive. */
    private class InlineMatch(
        val kind: Kind,
        val start: Int,
        val end: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val color: Color = Color.Unspecified,
    )
}
