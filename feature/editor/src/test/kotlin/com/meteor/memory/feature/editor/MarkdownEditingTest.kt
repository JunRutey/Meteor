package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEditingTest {

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

    @Test
    fun `wraps a selection with the marker`() {
        assertEquals("**重要**", MarkdownEditing.wrapsWith("重要", 0, 2, "**"))
    }

    @Test
    fun `wrapping keeps surrounding text intact`() {
        val body = "前面重要后面"
        val result = MarkdownEditing.wrapsWith(body, 2, 4, "**")
        assertEquals("前面**重要**后面", result)
    }

    @Test
    fun `out of range selection is a no-op`() {
        assertEquals("abc", MarkdownEditing.wrapsWith("abc", 5, 9, "**"))
    }

    @Test
    fun `italic uses a single marker`() {
        assertEquals("*斜*", MarkdownEditing.italicize("斜", 0, 1))
    }
}
