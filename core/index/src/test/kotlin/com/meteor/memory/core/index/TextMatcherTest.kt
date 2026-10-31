package com.meteor.memory.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {

    private val terms = listOf("今天", "天气")

    @Test
    fun `counts how many query terms appear`() {
        assertEquals(2, TextMatcher.countMatches("今天的天气很好", terms))
        assertEquals(1, TextMatcher.countMatches("今天很忙", terms))
        assertEquals(0, TextMatcher.countMatches("完全无关的内容", terms))
    }

    @Test
    fun `is case insensitive for latin terms`() {
        assertEquals(1, TextMatcher.countMatches("Hello world", listOf("hello")))
    }

    @Test
    fun `short body is returned unchanged`() {
        assertEquals("今天", TextMatcher.snippet("今天", terms, maxChars = 100))
    }

    @Test
    fun `long body is cut around the first hit`() {
        val body = "前言".repeat(80) + "今天天气很好" + "后记".repeat(80)
        val snippet = TextMatcher.snippet(body, terms, maxChars = 40)

        assertTrue(snippet.contains("今天"))
        assertTrue(snippet.length <= 42)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `falls back to the head when nothing matches`() {
        val body = "a".repeat(200)
        val snippet = TextMatcher.snippet(body, terms, maxChars = 40)

        assertEquals(40, snippet.length)
        assertTrue(!snippet.startsWith("…"))
    }
}
