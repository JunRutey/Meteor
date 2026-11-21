package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toolbar buttons are **toggles** (ADR-051): tapping bold arms it, typing
 * after that is bold, tapping again disarms it. These tests pin the marker
 * bookkeeping that makes that safe in a plain Markdown string.
 */
class MarkdownInlineEditingTest {

    private val red = InlineFormat.Color("#E53935")
    private val blue = InlineFormat.Color("#1E88E5")

    private val redOpen = """<span style="color:#E53935">"""

    // ---- format state -------------------------------------------------------

    @Test
    fun `tapping an inactive format turns it on`() {
        assertEquals(InlineFormat.Bold, InlineFormat.afterTap(null, InlineFormat.Bold))
    }

    @Test
    fun `tapping the active format turns it off`() {
        assertNull(InlineFormat.afterTap(InlineFormat.Bold, InlineFormat.Bold))
    }

    @Test
    fun `tapping another colour switches instead of stacking`() {
        assertEquals(blue, InlineFormat.afterTap(red, blue))
    }

    @Test
    fun `bold and colour are different modes`() {
        assertEquals(InlineFormat.Bold, InlineFormat.afterTap(red, InlineFormat.Bold))
    }

    // ---- opening ------------------------------------------------------------

    @Test
    fun `opening bold puts the caret inside the markers`() {
        val edit = MarkdownInlineEditing.open("你好", 1, InlineFormat.Bold)
        assertEquals("你****好", edit.text)
        assertEquals(3, edit.selectionStart)
        assertEquals(3, edit.selectionEnd)
    }

    @Test
    fun `opening a colour writes a renderable span`() {
        val edit = MarkdownInlineEditing.open("", 0, red)
        assertEquals(redOpen, edit.text)
        assertEquals(redOpen.length, edit.selectionStart)
    }

    @Test
    fun `opening clamps an out-of-range caret`() {
        val edit = MarkdownInlineEditing.open("字", 99, InlineFormat.Bold)
        assertEquals("字****", edit.text)
        assertEquals(3, edit.selectionStart)
    }

    // ---- closing ------------------------------------------------------------

    @Test
    fun `typing inside and closing bold balances the markers`() {
        // Turn bold on at the end, type "重点", then turn it off.
        var edit = MarkdownInlineEditing.open("", 0, InlineFormat.Bold)
        val typed = edit.text.substring(0, 2) + "重点" + edit.text.substring(2)
        edit = MarkdownInlineEditing.close(typed, 4, InlineFormat.Bold)

        assertEquals("**重点**", edit.text)
        assertEquals(6, edit.selectionStart)
    }

    @Test
    fun `closing without typing removes the empty markers`() {
        val opened = MarkdownInlineEditing.open("你好", 1, InlineFormat.Bold)
        val closed = MarkdownInlineEditing.close(
            opened.text,
            opened.selectionStart,
            InlineFormat.Bold,
        )
        // On and straight back off must leave the text exactly as it was.
        assertEquals("你好", closed.text)
        assertEquals(1, closed.selectionStart)
    }

    @Test
    fun `closing a colour without typing removes the empty span`() {
        val opened = MarkdownInlineEditing.open("", 0, red)
        val closed = MarkdownInlineEditing.close(opened.text, opened.selectionStart, red)
        assertEquals("", closed.text)
    }

    @Test
    fun `closing a colour after typing balances the tags`() {
        val opened = MarkdownInlineEditing.open("", 0, red)
        val typed = opened.text + "危险"
        val closed = MarkdownInlineEditing.close(typed, typed.length, red)
        assertEquals(redOpen + "危险</span>", closed.text)
    }

    @Test
    fun `closing twice does not add a second close tag`() {
        val opened = MarkdownInlineEditing.open("", 0, red)
        val typed = opened.text + "危险"
        val once = MarkdownInlineEditing.close(typed, typed.length, red)
        val twice = MarkdownInlineEditing.close(once.text, once.selectionStart, red)
        assertEquals(once.text, twice.text)
    }

    @Test
    fun `closing outside any run leaves the text alone`() {
        // A stray close would corrupt the paragraph, so a bare "off" tap is a no-op.
        val closed = MarkdownInlineEditing.close("普通文字", 2, InlineFormat.Bold)
        assertEquals("普通文字", closed.text)
        assertEquals(2, closed.selectionStart)
    }

    @Test
    fun `closing finds the open run past finished runs`() {
        val text = "**甲**乙" + "**丙"
        val closed = MarkdownInlineEditing.close(text, text.length, InlineFormat.Bold)
        assertEquals("**甲**乙" + "**丙**", closed.text)
    }

    @Test
    fun `closing only closes this colour, not another one`() {
        val text = redOpen + "红"
        val closed = MarkdownInlineEditing.close(text, text.length, blue)
        // No blue run is open, so nothing changes.
        assertEquals(text, closed.text)
    }

    @Test
    fun `closing clamps an out-of-range caret`() {
        val opened = MarkdownInlineEditing.open("", 0, InlineFormat.Bold)
        val closed = MarkdownInlineEditing.close(opened.text + "字", 99, InlineFormat.Bold)
        assertEquals("**字**", closed.text)
    }

    // ---- open detection -----------------------------------------------------

    @Test
    fun `an unfinished run reports open`() {
        assertTrue(MarkdownInlineEditing.isOpen("**重点", 3, InlineFormat.Bold))
    }

    @Test
    fun `a finished run reports closed`() {
        assertFalse(MarkdownInlineEditing.isOpen("**重点**", 6, InlineFormat.Bold))
    }

    @Test
    fun `nothing open reports closed`() {
        assertFalse(MarkdownInlineEditing.isOpen("普通", 2, InlineFormat.Bold))
    }

    @Test
    fun `a colour run is tracked per colour`() {
        val text = redOpen + "红"
        assertTrue(MarkdownInlineEditing.isOpen(text, text.length, red))
        assertFalse(MarkdownInlineEditing.isOpen(text, text.length, blue))
    }

    // ---- markers ------------------------------------------------------------

    @Test
    fun `colour markers use an upper-case six-digit hex`() {
        val edit = MarkdownInlineEditing.open("", 0, InlineFormat.Color("#e53935"))
        assertEquals(redOpen, edit.text)
        assertEquals("</span>", MarkdownInlineEditing.closingMarker(red))
    }

    @Test
    fun `the written span matches the preview regex`() {
        val preview = Regex(
            """<span style="color:(#[0-9A-Fa-f]{6})">(.*?)</span>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (hex in MarkdownInlineFormat.COLORS) {
            val format = InlineFormat.Color(hex)
            val opened = MarkdownInlineEditing.open("", 0, format)
            val closed = MarkdownInlineEditing.close(opened.text + "字", 3, format)
            assertTrue("unrenderable span for $hex: ${closed.text}", preview.matches(closed.text))
        }
    }
}
