package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkdownStyles] only feeds the read-only preview: the editing surface hides
 * bold markers itself (see [EditorRenderingTest]). Colour and italic are still
 * parsed here so records written before ADR-019 keep their look.
 */
class MarkdownStylesTest {

    private val placeholder = "[图片]"

    @Test
    fun `preview strips bold markers`() {
        assertEquals("a b c", MarkdownStyles.annotate("a **b** c", placeholder).text)
    }

    @Test
    fun `preview strips italic markers`() {
        assertEquals("斜", MarkdownStyles.annotate("*斜*", placeholder).text)
    }

    @Test
    fun `preview keeps only the text inside a legacy colour span`() {
        val source = """<span style="color:#E53935">红</span>"""
        assertEquals("红", MarkdownStyles.annotate(source, placeholder).text)
    }

    @Test
    fun `preview collapses an image to the placeholder and keeps its target`() {
        val annotated = MarkdownStyles.annotate("![](meteor://image/a.enc)", placeholder)
        assertEquals(placeholder, annotated.text)
        assertEquals(
            "meteor://image/a.enc",
            annotated.getStringAnnotations(TAG_TARGET, 0, 0).single().item,
        )
    }

    @Test
    fun `preview keeps the label of a link and drops the target`() {
        val annotated = MarkdownStyles.annotate("见[小明](meteor://person/abc)的记录", placeholder)
        assertEquals("见小明的记录", annotated.text)
        assertEquals(
            "meteor://person/abc",
            annotated.getStringAnnotations(TAG_TARGET, 1, 1).single().item,
        )
    }

    @Test
    fun `nested markup loses every marker`() {
        val source = """**<span style="color:#1E88E5">又是粗体又是蓝色</span>**"""
        assertEquals("又是粗体又是蓝色", MarkdownStyles.annotate(source, placeholder).text)
    }

    @Test
    fun `unknown markup is left alone`() {
        assertTrue(MarkdownStyles.annotate("__x__", placeholder).text == "__x__")
    }
}
