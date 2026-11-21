package com.meteor.memory.feature.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiReplyMarkdown] feeds the read-only AI reply bubble (ADR-050).
 *
 * Besides the individual markers, two invariants are pinned here: nothing a
 * reader can see is ever dropped, and a reply that is still streaming (an
 * incomplete marker at the very end) never loses the text that follows it.
 */
class AiReplyMarkdownTest {

    private fun String.render(): String = AiReplyMarkdown.render(this).text

    /** Styles covering [offset]; more than one when markup nests. */
    private fun AnnotatedString.stylesAt(offset: Int): List<SpanStyle> =
        spanStyles.filter { offset >= it.start && offset < it.end }.map { it.item }

    private fun AnnotatedString.hasStyleAt(offset: Int, predicate: (SpanStyle) -> Boolean): Boolean =
        stylesAt(offset).any(predicate)

    // ------------------------------------------------------- inline markers

    @Test
    fun `bold markers are removed and the text turns bold`() {
        val annotated = AiReplyMarkdown.render("前 **加粗** 后")

        assertEquals("前 加粗 后", annotated.text)
        assertTrue(annotated.hasStyleAt(2) { it.fontWeight == FontWeight.Bold })
        assertFalse(annotated.hasStyleAt(0) { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `italic markers are removed and the text turns italic`() {
        val annotated = AiReplyMarkdown.render("*斜体*")

        assertEquals("斜体", annotated.text)
        assertTrue(annotated.hasStyleAt(0) { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `triple asterisks are bold and italic at once`() {
        val annotated = AiReplyMarkdown.render("***强调***")

        assertEquals("强调", annotated.text)
        assertTrue(
            annotated.hasStyleAt(0) {
                it.fontWeight == FontWeight.Bold && it.fontStyle == FontStyle.Italic
            },
        )
    }

    @Test
    fun `inline code loses its backticks and turns monospaced`() {
        val style = AiReplyStyle(codeBackground = Color(0x22000000L))
        val annotated = AiReplyMarkdown.render("运行 `git status` 看看", style)

        assertEquals("运行 git status 看看", annotated.text)
        assertTrue(annotated.hasStyleAt(3) { it.fontFamily == FontFamily.Monospace })
        assertTrue(annotated.hasStyleAt(3) { it.background == Color(0x22000000L) })
    }

    @Test
    fun `markers inside inline code are not parsed`() {
        assertEquals("**原样**", "`**原样**`".render())
    }

    @Test
    fun `a link keeps only its visible label and is underlined`() {
        val style = AiReplyStyle(linkColor = Color(0xFF1E88E5L))
        val annotated = AiReplyMarkdown.render("见[官方文档](https://example.com)吧", style)

        assertEquals("见官方文档吧", annotated.text)
        assertTrue(annotated.hasStyleAt(1) { it.textDecoration == TextDecoration.Underline })
        assertTrue(annotated.hasStyleAt(1) { it.color == Color(0xFF1E88E5L) })
    }

    @Test
    fun `a colour span keeps its text and takes the colour`() {
        val annotated = AiReplyMarkdown.render("""<span style="color:#E53935">红色</span>""")

        assertEquals("红色", annotated.text)
        assertTrue(annotated.hasStyleAt(0) { it.color == Color(0xFFE53935L) })
    }

    @Test
    fun `an image reference keeps its alt text`() {
        assertEquals("表情", "![表情](https://example.com/a.png)".render())
    }

    @Test
    fun `nested markup keeps every style and loses every marker`() {
        val source = """**<span style="color:#1E88E5">又是粗体又是蓝色</span>**"""
        val annotated = AiReplyMarkdown.render(source)

        assertEquals("又是粗体又是蓝色", annotated.text)
        assertTrue(annotated.hasStyleAt(0) { it.fontWeight == FontWeight.Bold })
        assertTrue(annotated.hasStyleAt(0) { it.color == Color(0xFF1E88E5L) })
    }

    @Test
    fun `bold wrapping code keeps both`() {
        val annotated = AiReplyMarkdown.render("**`fun`**")

        assertEquals("fun", annotated.text)
        assertTrue(annotated.hasStyleAt(0) { it.fontWeight == FontWeight.Bold })
        assertTrue(annotated.hasStyleAt(0) { it.fontFamily == FontFamily.Monospace })
    }

    // -------------------------------------------------------- block markers

    @Test
    fun `headings lose their hashes and get a larger size`() {
        val style = AiReplyStyle(headingFontSizes = listOf(24.sp, 20.sp, 17.sp))
        val annotated = AiReplyMarkdown.render("## 今天的记录", style)

        assertEquals("今天的记录", annotated.text)
        assertTrue(annotated.hasStyleAt(0) { it.fontWeight == FontWeight.Bold })
        assertTrue(annotated.hasStyleAt(0) { it.fontSize == 20.sp })
    }

    @Test
    fun `heading text still goes through the inline parser`() {
        val annotated = AiReplyMarkdown.render("# 关于**你**的事")

        assertEquals("关于你的事", annotated.text)
        assertTrue(annotated.hasStyleAt(0) { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `bullet lines become a dot and keep their indentation`() {
        val bullet = "\u2022 "

        assertEquals("${bullet}一\n  ${bullet}二", "- 一\n  - 二".render())
    }

    @Test
    fun `numbered lines keep their numbers`() {
        assertEquals("1. 一\n2. 二", "1. 一\n2. 二".render())
    }

    @Test
    fun `a horizontal rule becomes one line of dashes`() {
        val rendered = "上文\n\n---\n\n下文".render()

        assertTrue(rendered.startsWith("上文\n\n"))
        assertTrue(rendered.endsWith("\n\n下文"))
        val rule = rendered.removePrefix("上文\n\n").removeSuffix("\n\n下文")
        assertTrue(rule.isNotEmpty())
        assertTrue(rule.all { it == '\u2500' })
    }

    @Test
    fun `a fenced block is shown verbatim`() {
        val source = """
            ```kotlin
            val **x** = 1
            ```
        """.trimIndent()

        assertEquals("val **x** = 1", source.render())
    }

    @Test
    fun `a fenced block survives between paragraphs`() {
        val source = """
            前

            ```
            code
            ```

            后
        """.trimIndent()

        assertEquals("前\n\ncode\n\n后", source.render())
    }

    @Test
    fun `a one line fenced block is supported`() {
        assertEquals("val x = 1", "```val x = 1```".render())
    }

    // ------------------------------------------------------- degrade safely

    @Test
    fun `plain text is returned unchanged`() {
        val source = "把旧照片整理了一遍。\n没有标记，也没有结构。"
        assertEquals(source, source.render())
    }

    @Test
    fun `an empty string renders empty`() {
        assertEquals("", "".render())
    }

    @Test
    fun `unknown markup is left alone`() {
        assertEquals("__x__ and ~~y~~", "__x__ and ~~y~~".render())
    }

    @Test
    fun `an unclosed bold marker is literal`() {
        assertEquals("**abc", "**abc".render())
    }

    @Test
    fun `an unclosed italic marker is literal`() {
        assertEquals("*abc", "*abc".render())
    }

    @Test
    fun `an unclosed code span is literal`() {
        assertEquals("`abc", "`abc".render())
    }

    @Test
    fun `an unclosed link is literal`() {
        assertEquals("[abc](https://e", "[abc](https://e".render())
    }

    @Test
    fun `an unclosed fence keeps the code that follows it`() {
        val annotated = AiReplyMarkdown.render("说明：\n```\nval x = 1")

        assertEquals("说明：\nval x = 1", annotated.text)
        assertTrue(annotated.hasStyleAt(4) { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `a lone fence line opens an empty code block`() {
        assertEquals("", "```".render())
    }

    @Test
    fun `a malformed colour span keeps its text`() {
        // `#XYZ` is not a colour we can parse, so the whole source stays visible
        // rather than the text disappearing behind a half-understood tag.
        val source = """<span style="color:#XYZ">红</span>"""
        assertEquals(source, source.render())
    }

    @Test
    fun `stray asterisks that form no emphasis stay put`() {
        assertEquals("2 * 3 * 4 = 24", "2 * 3 * 4 = 24".render())
    }

    @Test
    fun `markers padded with spaces are not emphasis`() {
        assertEquals("a * b * c", "a * b * c".render())
    }

    @Test
    fun `a half-typed bold marker does not flicker into italic`() {
        // Streaming `**a` then `*`: without the lookbehind, the single `*` would
        // open an italic run and briefly render `*a`, then flip back. A marker
        // that is not yet complete must stay exactly as typed.
        assertEquals("**a", "**a".render())
        assertEquals("**a*", "**a*".render())
        assertEquals("***a", "***a".render())
    }

    @Test
    fun `a half-typed code or link keeps every character`() {
        assertEquals("`a", "`a".render())
        assertEquals("[a](b", "[a](b".render())
        assertEquals("![a](b", "![a](b".render())
    }

    // ------------------------------------------------------------ streaming

    /**
     * The reply is streamed token by token, so the renderer sees every prefix.
     * The Chinese characters are the text a reader must never lose, whatever
     * the parser makes of a trailing fragment such as `**`, `*` or `` ` ``.
     */
    @Test
    fun `no visible character is lost at any streamed prefix`() {
        val source = "**加粗**、*斜体*、`代码`、[链接](https://a.example)与" +
            """<span style="color:#E53935">红色</span>""" +
            "\n\n## 标题\n- 列表\n\n```\ncode\n```"

        for (end in 0..source.length) {
            val prefix = source.substring(0, end)
            val rendered = AiReplyMarkdown.render(prefix).text

            assertEquals(
                "prefix of length $end lost text",
                prefix.filter(::isCjk),
                rendered.filter(::isCjk),
            )
        }
    }

    @Test
    fun `every streamed prefix renders and the last one is the finished reply`() {
        val source = "**粗** 与 `码`\n\n- 项"
        var last = ""
        for (end in 0..source.length) {
            last = AiReplyMarkdown.render(source.substring(0, end)).text
        }

        assertEquals(source.render(), last)
    }

    // ----------------------------------------------------------- invariants

    @Test
    fun `visible text equals the source with every marker removed`() {
        val source = "# 标题\n\n**粗**、*斜*、`码`、[链接](https://a.example)、" +
            """<span style="color:#E53935">红</span>"""

        assertEquals("标题\n\n粗、斜、码、链接、红", source.render())
    }

    private fun isCjk(character: Char): Boolean =
        character in '\u4E00'..'\u9FFF' || character in '\u3000'..'\u303F'
}
