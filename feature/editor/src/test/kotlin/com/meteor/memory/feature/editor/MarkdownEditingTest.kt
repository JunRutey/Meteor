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

    // ---- italic -------------------------------------------------------------

    @Test
    fun `italic wraps the selection`() {
        assertEquals("*斜*", MarkdownEditing.toggleItalic("斜", 0, 1).text)
    }

    @Test
    fun `italic with no selection inserts a marker pair`() {
        val result = MarkdownEditing.toggleItalic("", 0, 0)
        assertEquals("**", result.text)
        assertEquals(1, result.selectionStart)
    }

    // ---- colour -------------------------------------------------------------

    @Test
    fun `colour wraps the selection in a span`() {
        assertEquals(
            """<span style="color:#E53935">重要</span>""",
            MarkdownEditing.toggleColor("重要", 0, 2, "#E53935").text,
        )
    }

    @Test
    fun `colour and bold can be nested`() {
        val bold = MarkdownEditing.toggleBold("重要", 0, 2).text
        val colored = MarkdownEditing.toggleColor(bold, 2, 4, "#1E88E5").text
        assertEquals("""**<span style="color:#1E88E5">重要</span>**""", colored)
    }

    @Test
    fun `colour with no selection parks the caret inside the tags`() {
        val result = MarkdownEditing.toggleColor("", 0, 0, "#43A047")
        val open = """<span style="color:#43A047">"""
        assertEquals("$open</span>", result.text)
        assertEquals(open.length, result.selectionStart)
        assertEquals(open.length, result.selectionEnd)
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
