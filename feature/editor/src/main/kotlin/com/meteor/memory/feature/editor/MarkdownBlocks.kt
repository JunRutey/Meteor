package com.meteor.memory.feature.editor

/**
 * One block of a stored body, as the editor sees it.
 *
 * Splitting the body into blocks is what lets a media reference be **shown as
 * the picture/player itself** while the file keeps the plain Markdown reference
 * (ADR-049, ADR-055). The split is line-exact and [MarkdownBlocks.serialize]
 * rebuilds the body byte for byte, so simply opening a record never rewrites it.
 */
internal sealed interface BodyBlock {

    /** The exact text this block contributes to the body. */
    val raw: String

    /** Consecutive non-reference lines, joined by `\n` (one text field's content). */
    data class Text(override val raw: String) : BodyBlock

    /**
     * A line that held nothing but `![caption](meteor://image/<token>)`.
     *
     * [caption] is the Markdown alt text and doubles as the picture's
     * description; [token] is the bare path `ImageStore.load` takes.
     */
    data class Image(
        override val raw: String,
        val token: String,
        val caption: String,
    ) : BodyBlock

    /**
     * A line that held nothing but `![caption](meteor://media/<id>)` (ADR-052).
     *
     * The kind (image / video / audio) is **not** in the body: it lives in the
     * encrypted metadata sidecar, so the editor cannot know how to draw this
     * block on its own. The host answers that question through [MediaBlockState]
     * and `MarkdownField(resolveMedia = …)`.
     */
    data class Media(
        override val raw: String,
        val id: String,
        val caption: String,
    ) : BodyBlock
}

/**
 * Lossless body ⇄ block conversion for the editing surface.
 *
 * Pure on purpose: the interesting rules (which lines count as a reference, how
 * blank lines survive) are unit-tested without Compose or Android.
 */
internal object MarkdownBlocks {

    /** Splits [body] into blocks; `serialize(parse(body)) == body` always holds. */
    fun parse(body: String): List<BodyBlock> {
        val blocks = ArrayList<BodyBlock>()
        val text = StringBuilder()
        var textLines = 0

        fun flushText() {
            if (textLines == 0) return
            blocks += BodyBlock.Text(text.toString())
            text.setLength(0)
            textLines = 0
        }

        // split('\n') on an empty body yields one empty line, which is exactly
        // the single empty text block a blank editor needs.
        for (line in body.split('\n')) {
            val reference = referenceBlockOf(line)
            if (reference == null) {
                if (textLines > 0) text.append('\n')
                text.append(line)
                textLines++
            } else {
                flushText()
                blocks += reference
            }
        }
        flushText()

        return blocks.ifEmpty { listOf(BodyBlock.Text("")) }
    }

    /** Joins blocks back into a body. The inverse of [parse]. */
    fun serialize(blocks: List<BodyBlock>): String = blocks.joinToString("\n") { it.raw }

    /**
     * The reference a line stands for, or null when the line is text.
     *
     * The rules live in [MarkdownReferences] so the editing surface, the preview
     * and the pure body manipulation share exactly one definition of "a reference
     * line". `raw` keeps the untouched line so indentation and trailing spaces
     * survive a round trip.
     */
    private fun referenceBlockOf(line: String): BodyBlock? =
        when (val reference = MarkdownReferences.of(line)) {
            null -> null

            is LineReference.Image -> BodyBlock.Image(
                raw = line,
                token = reference.token,
                caption = reference.caption,
            )

            is LineReference.Media -> BodyBlock.Media(
                raw = line,
                id = reference.id,
                caption = reference.caption,
            )
        }
}
