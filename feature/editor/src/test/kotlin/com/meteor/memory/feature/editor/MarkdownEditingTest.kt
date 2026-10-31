package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEditingTest {

    // ---- image / link -------------------------------------------------------

    @Test
    fun `appends an image block on an empty body`() {
        val result = MarkdownEditing.appendImage("", "legacy/images/_self/a.enc", alt = "")
        assertEquals("![](meteor://image/legacy/images/_self/a.enc)\n", result)
    }

    @Test
    fun `separates an image block from existing text`() {
        val result = MarkdownEditing.appendImage("今天很好", "t/a.enc")
        assertTrue(result.startsWith("今天很好\n\n"))
        assertTrue(result.contains("meteor://image/t/a.enc"))
    }

    @Test
    fun `appends a link inline with a space`() {
        val result = MarkdownEditing.appendLink("看这里", "meteor://person/abc", "小明")
        assertEquals("看这里 [小明](meteor://person/abc)", result)
    }

    @Test
    fun `uses the target as the label when none is given`() {
        val result = MarkdownEditing.appendLink("", "https://example.com", null)
        assertEquals("[https://example.com](https://example.com)", result)
    }

    // ---- attached images ----------------------------------------------------

    @Test
    fun `finds every image reference in document order`() {
        val body = "开头\n\n![](meteor://image/a.enc)\n\n![](meteor://image/b.enc)\n"
        assertEquals(
            listOf("meteor://image/a.enc", "meteor://image/b.enc"),
            MarkdownEditing.imagesOf(body).map { it.token },
        )
    }

    @Test
    fun `keeps the alt text of a reference`() {
        val body = "![生日](meteor://image/a.enc)"
        assertEquals("生日", MarkdownEditing.imagesOf(body).single().label)
    }

    @Test
    fun `ignores plain links and external images`() {
        val body = "[看图](https://example.com/x.png) ![远程](https://example.com/y.png)"
        assertTrue(MarkdownEditing.imagesOf(body).isEmpty())
    }

    @Test
    fun `reports a repeated reference only once`() {
        val body = "![](meteor://image/a.enc)\n![](meteor://image/a.enc)"
        assertEquals(1, MarkdownEditing.imagesOf(body).size)
    }

    // ---- image removal ------------------------------------------------------

    @Test
    fun `removing a block image takes the blank line with it`() {
        val body = "今天很好\n\n![](meteor://image/t/a.enc)\n"
        assertEquals("今天很好", MarkdownEditing.removeImage(body, "t/a.enc"))
    }

    @Test
    fun `removing an inline image keeps the surrounding text`() {
        val body = "看图 ![](meteor://image/t/a.enc) 不错"
        assertEquals("看图 不错", MarkdownEditing.removeImage(body, "t/a.enc"))
    }

    @Test
    fun `a body that only held the image becomes empty`() {
        val body = "![](meteor://image/t/a.enc)\n"
        assertEquals("", MarkdownEditing.removeImage(body, "t/a.enc"))
    }

    @Test
    fun `removing one image leaves the others alone`() {
        val body = "![](meteor://image/t/a.enc)\n\n![](meteor://image/t/b.enc)\n"
        assertEquals(
            "![](meteor://image/t/b.enc)",
            MarkdownEditing.removeImage(body, "t/a.enc"),
        )
    }

    @Test
    fun `removing an unknown token changes nothing`() {
        val body = "正文\n\n![](meteor://image/t/a.enc)\n"
        assertEquals(body, MarkdownEditing.removeImage(body, "t/missing.enc"))
    }

    @Test
    fun `removing an image from plain text changes nothing`() {
        assertEquals("普通文字", MarkdownEditing.removeImage("普通文字", "t/a.enc"))
    }

    // ---- bold ---------------------------------------------------------------

    @Test
    fun `bold wraps the selection`() {
        val result = MarkdownEditing.toggleBold("重要", 0, 2)
        assertEquals("**重要**", result.text)
    }

    @Test
    fun `bold keeps surrounding text and selects the same words`() {
        val result = MarkdownEditing.toggleBold("前面重要后面", 2, 4)
        assertEquals("前面**重要**后面", result.text)
        assertEquals(4, result.selectionStart)
        assertEquals(6, result.selectionEnd)
    }

    @Test
    fun `bold unwraps when the selection is already bold`() {
        val result = MarkdownEditing.toggleBold("**重要**", 2, 4)
        assertEquals("重要", result.text)
        assertEquals(0, result.selectionStart)
    }

    @Test
    fun `bold with no selection inserts markers and parks the caret inside`() {
        val result = MarkdownEditing.toggleBold("abc", 3, 3)
        assertEquals("abc****", result.text)
        assertEquals(5, result.selectionStart)
        assertEquals(5, result.selectionEnd)
    }

    // ---- guards -------------------------------------------------------------

    @Test
    fun `out of range selection is a no-op`() {
        val result = MarkdownEditing.toggleBold("abc", 5, 9)
        assertEquals("abc", result.text)
    }

    @Test
    fun `wrapsWith still keeps surrounding text`() {
        assertEquals("前面**重要**后面", MarkdownEditing.wrapsWith("前面重要后面", 2, 4, "**"))
    }
}
