package com.meteor.memory.feature.editor

import com.meteor.memory.core.storage.ImageStore

/**
 * One block of a stored body, as the editor sees it.
 *
 * Splitting the body into blocks is what lets an image reference be **shown as
 * the picture itself** while the file keeps the plain Markdown reference
 * (ADR-047). The split is line-exact and [MarkdownBlocks.serialize] rebuilds the
 * body byte for byte, so simply opening a record never rewrites it.
 */
internal sealed interface BodyBlock {

    /** The exact text this block contributes to the body. */
    val raw: String

    /** Consecutive non-image lines, joined by `\n` (one text field's content). */
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
}

/**
 * Lossless body ⇄ block conversion for the editing surface.
 *
 * Pure on purpose: the interesting rules (which lines count as an image, how
 * blank lines survive) are unit-tested without Compose or Android.
 */
internal object MarkdownBlocks {

    /**
     * Anchored on the whole line, so an image *inside* a sentence
     * (`看图 ![](...)`) stays ordinary text the author can still edit.
     */
    private val IMAGE_LINE = Regex("""^!\[([^\]]*)]\(([^)]+)\)$""")

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
            val image = imageBlockOf(line)
            if (image == null) {
                if (textLines > 0) text.append('\n')
                text.append(line)
                textLines++
            } else {
                flushText()
                blocks += image
            }
        }
        flushText()

        return blocks.ifEmpty { listOf(BodyBlock.Text("")) }
    }

    /** Joins blocks back into a body. The inverse of [parse]. */
    fun serialize(blocks: List<BodyBlock>): String = blocks.joinToString("\n") { it.raw }

    /**
     * The image a line stands for, or null when the line is text.
     *
     * Only `meteor://image/` targets qualify: an external `https://` picture
     * cannot be decrypted here, so it must stay visible as text.
     */
    private fun imageBlockOf(line: String): BodyBlock.Image? {
        val match = IMAGE_LINE.matchEntire(line.trim()) ?: return null
        val target = match.groupValues[2]
        if (!target.startsWith(ImageStore.IMAGE_SCHEME)) return null

        val token = target.removePrefix(ImageStore.IMAGE_SCHEME)
        if (token.isBlank()) return null

        // `raw` keeps the untouched line so indentation and trailing spaces
        // survive a round trip.
        return BodyBlock.Image(
            raw = line,
            token = token,
            caption = match.groupValues[1],
        )
    }
}
