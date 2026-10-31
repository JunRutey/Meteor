package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor applies a [MarkdownStyledTransformation] with `OffsetMapping.Identity`,
 * so the styled text **must keep the same length as the source**. Dropping the
 * `**` markers here made the transformed text shorter and crashed Compose at
 * runtime; these tests lock that invariant down.
 */
class MarkdownStylesTest {

    @Test
    fun `editor mode preserves length for bold`() {
        val text = "a **b** c"
        assertEquals(text.length, MarkdownStyles.annotate(text, imageLabel = null).length)
    }

    @Test
    fun `editor mode preserves length for italic`() {
        val text = "a *b* c"
        assertEquals(text.length, MarkdownStyles.annotate(text, imageLabel = null).length)
    }

    @Test
    fun `editor mode preserves length for links`() {
        val text = "见 [小明](meteor://person/abc) 的记录"
        assertEquals(text.length, MarkdownStyles.annotate(text, imageLabel = null).length)
    }

    @Test
    fun `editor mode preserves length for images`() {
        val text = "![](meteor://image/legacy/images/_self/a.enc)"
        assertEquals(text.length, MarkdownStyles.annotate(text, imageLabel = null).length)
    }

    @Test
    fun `editor mode keeps the raw markers visible`() {
        val annotated = MarkdownStyles.annotate("**粗**", imageLabel = null)
        assertEquals("**粗**", annotated.text)
    }

    @Test
    fun `preview mode collapses an image to its label`() {
        val annotated = MarkdownStyles.annotate(
            "![](meteor://image/a.enc)",
            imageLabel = "[图片]",
        )
        assertEquals("[图片]", annotated.text)
        assertTrue(annotated.getStringAnnotations(TAG_TARGET, 0, 0).isNotEmpty())
    }

    @Test
    fun `preview mode strips bold markers`() {
        assertEquals("粗", MarkdownStyles.annotate("**粗**", imageLabel = "").text)
    }
}
