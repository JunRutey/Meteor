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
        // Bare paths: this is what ImageStore.load and delete take.
        assertEquals(
            listOf("a.enc", "b.enc"),
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

    // ---- image descriptions -------------------------------------------------

    @Test
    fun `adds a description to an image that had none`() {
        val body = "![](meteor://image/t/a.enc)\n"
        assertEquals(
            "![毕业那天](meteor://image/t/a.enc)\n",
            MarkdownEditing.setImageCaption(body, "t/a.enc", "毕业那天"),
        )
    }

    @Test
    fun `replaces an existing description`() {
        val body = "![旧的](meteor://image/t/a.enc)"
        assertEquals(
            "![新的](meteor://image/t/a.enc)",
            MarkdownEditing.setImageCaption(body, "t/a.enc", "新的"),
        )
    }

    @Test
    fun `an empty description removes the alt text`() {
        val body = "![旧的](meteor://image/t/a.enc)"
        assertEquals(
            "![](meteor://image/t/a.enc)",
            MarkdownEditing.setImageCaption(body, "t/a.enc", "   "),
        )
    }

    @Test
    fun `a description cannot break the surrounding markdown`() {
        val body = "![](meteor://image/t/a.enc)"
        assertEquals(
            "![甲（乙）丙 丁](meteor://image/t/a.enc)",
            MarkdownEditing.setImageCaption(body, "t/a.enc", "甲[乙]丙\n丁"),
        )
    }

    @Test
    fun `only the targeted image gets the description`() {
        val body = "![](meteor://image/t/a.enc)\n\n![](meteor://image/t/b.enc)"
        val result = MarkdownEditing.setImageCaption(body, "t/b.enc", "第二张")
        assertTrue(result.endsWith("![第二张](meteor://image/t/b.enc)"))
        assertTrue(result.startsWith("![](meteor://image/t/a.enc)"))
    }

    @Test
    fun `describing an unknown image changes nothing`() {
        val body = "![](meteor://image/t/a.enc)"
        assertEquals(body, MarkdownEditing.setImageCaption(body, "t/gone.enc", "无"))
    }

    @Test
    fun `replacing a placeholder keeps the caption and swaps the id`() {
        val from = "a".repeat(32)
        val to = "b".repeat(32)
        val body = "![假期](meteor://media/$from)"

        assertEquals("![假期](meteor://media/$to)", MarkdownEditing.replaceMediaId(body, from, to))
    }

    @Test
    fun `replacing an unknown placeholder changes nothing`() {
        val body = "![](meteor://media/${"a".repeat(32)})"

        assertEquals(body, MarkdownEditing.replaceMediaId(body, "c".repeat(32), "b".repeat(32)))
    }

    @Test
    fun `replacing a placeholder only touches that reference`() {
        val from = "a".repeat(32)
        val other = "c".repeat(32)
        val to = "b".repeat(32)
        val body = "![](meteor://media/$from)\n\n![](meteor://media/$other)"

        val result = MarkdownEditing.replaceMediaId(body, from, to)

        assertTrue(result.startsWith("![](meteor://media/$to)"))
        assertTrue(result.endsWith("![](meteor://media/$other)"))
    }
}
