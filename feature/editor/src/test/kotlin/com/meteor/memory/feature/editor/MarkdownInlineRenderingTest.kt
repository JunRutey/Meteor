package com.meteor.memory.feature.editor

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hiding markers means the rendered text is shorter than the source, so the
 * offset mapping is the part that can break a caret. These tests pin it down
 * (ADR-051) — the v0.4 regression was exactly a mapping/length mismatch.
 */
class MarkdownInlineRenderingTest {

    private fun rendered(source: String): Pair<String, androidx.compose.ui.text.input.OffsetMapping> {
        val result = MarkdownInlineRendering.of(source)
        return result.text.text to result.mapping
    }

    @Test
    fun `bold markers disappear and the text stays`() {
        val (text, _) = rendered("a **粗** b")
        assertEquals("a 粗 b", text)
    }

    @Test
    fun `colour span markers disappear`() {
        val (text, _) = rendered("""前<span style="color:#E53935">红</span>后""")
        assertEquals("前红后", text)
    }

    @Test
    fun `unclosed bold styles the rest instead of showing the markers`() {
        val (text, _) = rendered("重点 **还在打字")
        assertEquals("重点 还在打字", text)
    }

    @Test
    fun `unclosed span styles the rest`() {
        val (text, _) = rendered("""<span style="color:#1E88E5">蓝""")
        assertEquals("蓝", text)
    }

    @Test
    fun `a lone marker pair renders as nothing`() {
        val (text, _) = rendered("****")
        assertEquals("", text)
    }

    @Test
    fun `plain text is untouched`() {
        val (text, _) = rendered("普通的一天")
        assertEquals("普通的一天", text)
    }

    @Test
    fun `nested bold and colour both render`() {
        val source = """**<span style="color:#E53935">红粗</span>**"""
        val (text, _) = rendered(source)
        assertEquals("红粗", text)

        val annotated = MarkdownInlineRendering.of(source).text
        val colors = annotated.spanStyles.count { it.item.color != androidx.compose.ui.graphics.Color.Unspecified }
        val bolds = annotated.spanStyles.count { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, colors)
        assertEquals(1, bolds)
    }

    @Test
    fun `text after a closed run is not styled`() {
        val source = "**粗**普通"
        val (text, _) = rendered(source)
        assertEquals("粗普通", text)

        val annotated = MarkdownInlineRendering.of(source).text
        // "粗" is bold, "普通" is not.
        assertEquals(2, annotated.text.length)
        val styled = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, styled.size)
        assertEquals(0, styled.single().start)
        assertEquals(1, styled.single().end)
    }

    // ---- the mapping --------------------------------------------------------

    @Test
    fun `both mappings cover every offset without going out of range`() {
        val source = "a**粗**b<span style=\"color:#E53935\">红</span>c"
        val result = MarkdownInlineRendering.of(source)
        val renderedLength = result.text.text.length

        for (offset in 0..source.length) {
            val mapped = result.mapping.originalToTransformed(offset)
            assertTrue("source $offset -> $mapped", mapped in 0..renderedLength)
        }
        for (offset in 0..renderedLength) {
            val mapped = result.mapping.transformedToOriginal(offset)
            assertTrue("rendered $offset -> $mapped", mapped in 0..source.length)
        }
    }

    @Test
    fun `the end of the source maps to the end of the rendered text`() {
        val source = "**粗**"
        val result = MarkdownInlineRendering.of(source)
        assertEquals(
            result.text.text.length,
            result.mapping.originalToTransformed(source.length),
        )
    }

    @Test
    fun `out-of-range offsets are clamped instead of throwing`() {
        val result = MarkdownInlineRendering.of("**粗**")
        // Compose and the IME can ask for one-past-the-end mid-composition.
        assertTrue(result.mapping.originalToTransformed(-1) >= 0)
        assertTrue(result.mapping.originalToTransformed(99) <= result.text.text.length)
        assertTrue(result.mapping.transformedToOriginal(-1) >= 0)
        assertTrue(result.mapping.transformedToOriginal(99) <= 5)
    }

    @Test
    fun `a caret between two hidden markers stays put`() {
        val source = "**粗**"
        val result = MarkdownInlineRendering.of(source)
        // Source offset 2..3 is the visible "粗"; both must map into the text.
        val start = result.mapping.originalToTransformed(2)
        val end = result.mapping.originalToTransformed(3)
        assertTrue(start in 0..result.text.text.length)
        assertTrue(end in 0..result.text.text.length)
        assertTrue("visible range must not collapse", start <= end)
    }

    @Test
    fun `half-typed marker does not lose characters`() {
        // Every prefix the user can type must keep all of its non-marker text.
        val full = "**重点**"
        for (length in 0..full.length) {
            val prefix = full.substring(0, length)
            val result = MarkdownInlineRendering.of(prefix)
            val visible = result.text.text
            assertTrue(
                "prefix '$prefix' lost text, got '$visible'",
                prefix.replace("**", "").startsWith(visible),
            )
        }
    }

    @Test
    fun `an empty source renders empty`() {
        val (text, mapping) = rendered("")
        assertEquals("", text)
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(0, mapping.transformedToOriginal(0))
    }

    @Test
    fun `italic and links are left as written`() {
        // Only bold and colour are hidden; everything else must stay editable.
        val source = "*斜* [链接](https://example.com)"
        val (text, _) = rendered(source)
        assertNotEquals("", text)
        assertTrue(text.contains("*斜*"))
        assertTrue(text.contains("[链接](https://example.com)"))
    }

    @Test
    fun `the transformation wraps the same rendering`() {
        val transformed = MarkdownStyledTransformation().filter(
            androidx.compose.ui.text.AnnotatedString("a **粗** b"),
        )
        assertEquals("a 粗 b", transformed.text.text)
    }
}
