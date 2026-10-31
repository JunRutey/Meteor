package com.meteor.memory.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hiding markup characters shortens the rendered text, so the offset mapping is
 * the only thing keeping the caret in place. These tests pin both the visible
 * text and the mapping boundaries.
 */
class EditorRenderingTest {

    @Test
    fun `bold markers are hidden`() {
        val rendered = EditorRendering.of("**粗**")
        assertEquals("粗", rendered.text.text)
    }

    @Test
    fun `italic markers are hidden`() {
        assertEquals("斜", EditorRendering.of("*斜*").text.text)
    }

    @Test
    fun `nested colour and bold hide both pairs of markers`() {
        val source = """**<span style="color:#E53935">红</span>**"""
        assertEquals("红", EditorRendering.of(source).text.text)
    }

    @Test
    fun `plain text is untouched`() {
        assertEquals("普通文字", EditorRendering.of("普通文字").text.text)
    }

    @Test
    fun `image syntax stays visible for editing`() {
        val source = "![](meteor://image/a.enc)"
        assertEquals(source, EditorRendering.of(source).text.text)
    }

    @Test
    fun `link syntax stays visible for editing`() {
        val source = "[小明](meteor://person/abc)"
        assertEquals(source, EditorRendering.of(source).text.text)
    }

    @Test
    fun `caret maps from the start of the hidden marker to the first visible char`() {
        val rendered = EditorRendering.of("**粗**")
        assertEquals(0, rendered.mapping.originalToTransformed(0))
        assertEquals(0, rendered.mapping.originalToTransformed(2))
        assertEquals(2, rendered.mapping.transformedToOriginal(0))
    }

    @Test
    fun `end offset maps to end of visible text`() {
        val rendered = EditorRendering.of("**粗**")
        assertEquals(1, rendered.mapping.originalToTransformed(5))
        assertEquals(5, rendered.mapping.transformedToOriginal(1))
    }

    @Test
    fun `text after a hidden span keeps its position`() {
        val rendered = EditorRendering.of("**a**b")
        assertEquals("ab", rendered.text.text)
        assertEquals(2, rendered.mapping.originalToTransformed(6))
        assertEquals(5, rendered.mapping.transformedToOriginal(1))
    }

    @Test
    fun `empty source maps trivially`() {
        val rendered = EditorRendering.of("")
        assertEquals("", rendered.text.text)
        assertEquals(0, rendered.mapping.originalToTransformed(0))
        assertEquals(0, rendered.mapping.transformedToOriginal(0))
    }
}
