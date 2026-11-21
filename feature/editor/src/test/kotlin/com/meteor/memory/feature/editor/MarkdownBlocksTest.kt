package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkdownBlocks] is the guarantee that an image reference is **shown as a
 * picture without being rewritten**: the body must survive parse → serialize
 * untouched (ADR-049).
 */
class MarkdownBlocksTest {

    @Test
    fun `a lone image line becomes an image block`() {
        val block = MarkdownBlocks.parse("![](meteor://image/t/a.enc)").single()
        assertTrue(block is BodyBlock.Image)
        block as BodyBlock.Image
        assertEquals("t/a.enc", block.token)
        assertEquals("", block.caption)
    }

    @Test
    fun `keeps the caption of an image line`() {
        val block = MarkdownBlocks.parse("![生日那天](meteor://image/t/a.enc)").single()
        assertEquals("生日那天", (block as BodyBlock.Image).caption)
    }

    @Test
    fun `plain text becomes a single text block`() {
        val block = MarkdownBlocks.parse("今天很好\n明天也好").single()
        assertEquals(BodyBlock.Text("今天很好\n明天也好"), block)
    }

    @Test
    fun `text around an image splits into three blocks`() {
        val blocks = MarkdownBlocks.parse("上\n![](meteor://image/t/a.enc)\n下")
        assertEquals(
            listOf("上", "![](meteor://image/t/a.enc)", "下"),
            blocks.map { it.raw },
        )
    }

    @Test
    fun `an inline image reference stays editable text`() {
        val blocks = MarkdownBlocks.parse("看图 ![](meteor://image/t/a.enc) 不错")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is BodyBlock.Text)
    }

    @Test
    fun `an external image stays text because it cannot be decrypted`() {
        val blocks = MarkdownBlocks.parse("![](https://example.com/y.png)")
        assertTrue(blocks.single() is BodyBlock.Text)
    }

    @Test
    fun `an ordinary link is never an image`() {
        val blocks = MarkdownBlocks.parse("[看图](meteor://image/t/a.enc)")
        assertTrue(blocks.single() is BodyBlock.Text)
    }

    @Test
    fun `an empty body is one empty text block`() {
        assertEquals(listOf(BodyBlock.Text("")), MarkdownBlocks.parse(""))
    }

    @Test
    fun `blank lines around an image are preserved`() {
        val body = "文字\n\n![](meteor://image/t/a.enc)\n\n结尾"
        assertEquals(body, MarkdownBlocks.serialize(MarkdownBlocks.parse(body)))
    }

    @Test
    fun `round trip keeps a body ending with a newline`() {
        val body = "文字\n\n![](meteor://image/t/a.enc)\n"
        assertEquals(body, MarkdownBlocks.serialize(MarkdownBlocks.parse(body)))
    }

    @Test
    fun `round trip keeps an image-only body`() {
        val body = "![](meteor://image/t/a.enc)"
        assertEquals(body, MarkdownBlocks.serialize(MarkdownBlocks.parse(body)))
    }

    @Test
    fun `round trip keeps a plain text body`() {
        val body = "第一行\n\n第三行\n"
        assertEquals(body, MarkdownBlocks.serialize(MarkdownBlocks.parse(body)))
    }

    @Test
    fun `round trip keeps a legacy reference with a description`() {
        val body = "开头\n\n![毕业照](meteor://image/legacy/images/_self/a.enc)\n"
        assertEquals(body, MarkdownBlocks.serialize(MarkdownBlocks.parse(body)))
    }

    @Test
    fun `an image with surrounding spaces keeps them`() {
        val body = "   ![](meteor://image/t/a.enc)  "
        assertEquals(body, MarkdownBlocks.serialize(MarkdownBlocks.parse(body)))
    }
}
