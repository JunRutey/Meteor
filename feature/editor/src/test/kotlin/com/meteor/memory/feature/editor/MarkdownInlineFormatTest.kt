package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline formatter only *inserts* markup; the field keeps showing it
 * (ADR-021, ADR-048). Every case below therefore asserts on the literal text and
 * the selection that comes back, exactly what `MarkdownField` will write into the
 * text field state.
 */
class MarkdownInlineFormatTest {

    private val red = "#E53935"
    private val blue = "#1E88E5"

    private fun open(hex: String) = """<span style="color:$hex">"""

    private fun span(hex: String, body: String) = open(hex) + body + "</span>"

    // ---- bold ---------------------------------------------------------------

    @Test
    fun `bold on an empty selection inserts a pair and puts the caret inside`() {
        val edit = MarkdownInlineFormat.toggleBold("你好", 1, 1)
        assertEquals("你****好", edit.text)
        assertEquals(3, edit.selectionStart)
        assertEquals(3, edit.selectionEnd)
    }

    @Test
    fun `bold wraps the selection`() {
        val edit = MarkdownInlineFormat.toggleBold("hello", 0, 5)
        assertEquals("**hello**", edit.text)
        assertEquals(2, edit.selectionStart)
        assertEquals(7, edit.selectionEnd)
    }

    @Test
    fun `bold again on the same selection unwraps it`() {
        val edit = MarkdownInlineFormat.toggleBold("**hello**", 2, 7)
        assertEquals("hello", edit.text)
        assertEquals(0, edit.selectionStart)
        assertEquals(5, edit.selectionEnd)
    }

    @Test
    fun `bold removes a pair the selection contains`() {
        val edit = MarkdownInlineFormat.toggleBold("**hello**", 0, 9)
        assertEquals("hello", edit.text)
        assertEquals(0, edit.selectionStart)
        assertEquals(5, edit.selectionEnd)
    }

    @Test
    fun `bold inserted twice on an empty field leaves nothing behind`() {
        val once = MarkdownInlineFormat.toggleBold("", 0, 0)
        assertEquals("****", once.text)
        assertEquals(2, once.selectionStart)

        val twice = MarkdownInlineFormat.toggleBold(
            once.text,
            once.selectionStart,
            once.selectionEnd,
        )
        assertEquals("", twice.text)
        assertEquals(0, twice.selectionStart)
        assertEquals(0, twice.selectionEnd)
    }

    @Test
    fun `bold clamps an out-of-range selection instead of throwing`() {
        val edit = MarkdownInlineFormat.toggleBold("abc", -5, 99)
        assertEquals("**abc**", edit.text)
        assertEquals(2, edit.selectionStart)
        assertEquals(5, edit.selectionEnd)
    }

    @Test
    fun `bold accepts a reversed selection`() {
        val edit = MarkdownInlineFormat.toggleBold("abc", 3, 0)
        assertEquals("**abc**", edit.text)
        assertEquals(2, edit.selectionStart)
        assertEquals(5, edit.selectionEnd)
    }

    @Test
    fun `bold on empty text and an empty selection inserts one pair`() {
        val edit = MarkdownInlineFormat.toggleBold("", 0, 0)
        assertEquals("****", edit.text)
        assertEquals(2, edit.selectionStart)
        assertEquals(2, edit.selectionEnd)
    }

    @Test
    fun `bold does not unwrap when only one side has markers`() {
        val edit = MarkdownInlineFormat.toggleBold("**hello", 2, 7)
        assertEquals("****hello**", edit.text)
        assertEquals(4, edit.selectionStart)
    }

    // ---- colour -------------------------------------------------------------

    @Test
    fun `colour wraps the selection in a span`() {
        val edit = MarkdownInlineFormat.applyColor("危险", 0, 2, red)
        assertEquals(span(red, "危险"), edit.text)
        assertEquals(open(red).length, edit.selectionStart)
        assertEquals(open(red).length + 2, edit.selectionEnd)
    }

    @Test
    fun `colour on an empty selection inserts an empty span and puts the caret inside`() {
        val edit = MarkdownInlineFormat.applyColor("ab", 1, 1, red)
        assertEquals("a" + open(red) + "</span>b", edit.text)
        assertEquals(1 + open(red).length, edit.selectionStart)
        assertEquals(1 + open(red).length, edit.selectionEnd)
    }

    @Test
    fun `applying the same colour twice does not nest a second span`() {
        val once = MarkdownInlineFormat.applyColor("危险", 0, 2, red)
        val twice = MarkdownInlineFormat.applyColor(
            once.text,
            once.selectionStart,
            once.selectionEnd,
            red,
        )

        assertEquals("危险", twice.text)
        assertEquals(0, twice.selectionStart)
        assertEquals(2, twice.selectionEnd)
    }

    @Test
    fun `a different colour replaces the existing span instead of nesting`() {
        val once = MarkdownInlineFormat.applyColor("危险", 0, 2, red)
        val twice = MarkdownInlineFormat.applyColor(
            once.text,
            once.selectionStart,
            once.selectionEnd,
            blue,
        )

        assertEquals(span(blue, "危险"), twice.text)
        assertEquals(1, Regex("<span").findAll(twice.text).count())
        assertEquals(once.selectionStart, twice.selectionStart)
        assertEquals(once.selectionEnd, twice.selectionEnd)
    }

    @Test
    fun `colour normalises a lower-case hex`() {
        val edit = MarkdownInlineFormat.applyColor("字", 0, 1, "#e53935")
        assertEquals(span(red, "字"), edit.text)
    }

    @Test
    fun `colour without a leading hash is still accepted`() {
        val edit = MarkdownInlineFormat.applyColor("字", 0, 1, "E53935")
        assertEquals(span(red, "字"), edit.text)
    }

    @Test
    fun `an unrenderable hex leaves the text untouched`() {
        val text = "字"
        for (hex in listOf("", "red", "#E5393", "#E539355", "#GGGGGG")) {
            val edit = MarkdownInlineFormat.applyColor(text, 0, 1, hex)
            assertEquals(text, edit.text)
            assertEquals(0, edit.selectionStart)
            assertEquals(1, edit.selectionEnd)
        }
    }

    @Test
    fun `recolouring a selection inside an existing span rewrites that span`() {
        val source = span(red, "红色的文字")
        val innerStart = open(red).length + 1
        val innerEnd = innerStart + 2
        val edit = MarkdownInlineFormat.applyColor(source, innerStart, innerEnd, blue)

        assertEquals(span(blue, "红色的文字"), edit.text)
        assertEquals(innerStart, edit.selectionStart)
        assertEquals(innerEnd, edit.selectionEnd)
    }

    @Test
    fun `recolouring with the same colour inside a span changes nothing`() {
        val source = span(red, "红色的文字")
        val innerStart = open(red).length
        val edit = MarkdownInlineFormat.applyColor(source, innerStart, innerStart + 2, red)
        assertEquals(source, edit.text)
    }

    @Test
    fun `a selection carrying a span tag is not wrapped again`() {
        val source = span(red, "红") + " 蓝"
        // From the span's content to the end: the selection itself contains
        // "</span>", so wrapping it would nest a span inside a span.
        val edit = MarkdownInlineFormat.applyColor(source, open(red).length, source.length, blue)
        assertEquals(source, edit.text)
        assertEquals(open(red).length, edit.selectionStart)
        assertEquals(source.length, edit.selectionEnd)
    }

    @Test
    fun `a hand-selected whole span is unwrapped by the same colour`() {
        val source = "前" + span(red, "红") + "后"
        val edit = MarkdownInlineFormat.applyColor(source, 1, 1 + span(red, "红").length, red)
        assertEquals("前红后", edit.text)
        assertEquals(1, edit.selectionStart)
        assertEquals(2, edit.selectionEnd)
    }

    @Test
    fun `colour clamps an out-of-range selection instead of throwing`() {
        val edit = MarkdownInlineFormat.applyColor("字", -1, 42, red)
        assertEquals(span(red, "字"), edit.text)
        assertEquals(open(red).length, edit.selectionStart)
    }

    @Test
    fun `colour on empty text inserts an empty span`() {
        val edit = MarkdownInlineFormat.applyColor("", 0, 0, red)
        assertEquals(open(red) + "</span>", edit.text)
        assertEquals(open(red).length, edit.selectionStart)
        assertEquals(open(red).length, edit.selectionEnd)
    }

    @Test
    fun `the produced span matches the preview regex`() {
        val preview = Regex(
            """<span style="color:(#[0-9A-Fa-f]{6})">(.*?)</span>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (hex in MarkdownInlineFormat.COLORS) {
            val edit = MarkdownInlineFormat.applyColor("文字", 0, 2, hex)
            val match = preview.matchEntire(edit.text)
            assertTrue("unrenderable span for $hex: ${edit.text}", match != null)
            assertEquals(hex, match!!.groupValues[1])
        }
    }

    @Test
    fun `the palette only offers renderable six-digit colours`() {
        assertTrue(MarkdownInlineFormat.COLORS.size >= 2)
        assertEquals(
            MarkdownInlineFormat.COLORS.size,
            MarkdownInlineFormat.COLORS.distinct().size,
        )
        MarkdownInlineFormat.COLORS.forEach { hex ->
            assertTrue(
                "bad swatch: $hex",
                Regex("#[0-9A-Fa-f]{6}").matches(hex),
            )
        }
    }

    @Test
    fun `bold and colour compose without breaking the markers`() {
        val bolded = MarkdownInlineFormat.toggleBold("重点", 0, 2)
        val colored = MarkdownInlineFormat.applyColor(
            bolded.text,
            bolded.selectionStart,
            bolded.selectionEnd,
            red,
        )
        assertEquals("**" + span(red, "重点") + "**", colored.text)
        assertFalse(colored.text.contains("****"))
    }
}
