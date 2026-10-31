package com.meteor.memory.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFeaturesTest {

    @Test
    fun `cjk run becomes overlapping bigrams`() {
        assertEquals(listOf("今天", "天的", "的天", "天气"), TextFeatures.tokens("今天的天气"))
    }

    @Test
    fun `single cjk character stays a token`() {
        assertEquals(listOf("雨"), TextFeatures.tokens("雨"))
    }

    @Test
    fun `latin runs are lowercased words`() {
        assertEquals(listOf("hello"), TextFeatures.tokens("Hello"))
    }

    @Test
    fun `punctuation separates runs`() {
        assertEquals(listOf("你好", "hello"), TextFeatures.tokens("你好, hello!"))
    }

    @Test
    fun `blank text has no tokens`() {
        assertTrue(TextFeatures.tokens("   ").isEmpty())
    }

    @Test
    fun `a term that appears everywhere weighs less`() {
        assertTrue(TextFeatures.weight(1, 10) > TextFeatures.weight(5, 10))
        assertEquals(0.0, TextFeatures.weight(0, 10), 0.0001)
    }
}
