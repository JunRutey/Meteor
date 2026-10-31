package com.meteor.memory.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkTokenizerTest {

    @Test
    fun `splits a chinese run into overlapping bigrams`() {
        // 今 天 的 天 气  ->  今天 天的 的天 天气
        assertEquals(listOf("今天", "天的", "的天", "天气"), CjkTokenizer.tokenize("今天的天气"))
    }

    @Test
    fun `keeps a single chinese character as its own token`() {
        assertEquals(listOf("雨"), CjkTokenizer.tokenize("雨"))
    }

    @Test
    fun `two characters produce exactly one bigram`() {
        assertEquals(listOf("天气"), CjkTokenizer.tokenize("天气"))
    }

    @Test
    fun `lowercases latin words`() {
        assertEquals(listOf("hello", "world"), CjkTokenizer.tokenize("Hello WORLD"))
    }

    @Test
    fun `separates latin from chinese`() {
        assertEquals(
            listOf("今天", "天的", "的天", "天气", "good"),
            CjkTokenizer.tokenize("今天的天气 good"),
        )
    }

    @Test
    fun `drops punctuation`() {
        assertEquals(listOf("你好"), CjkTokenizer.tokenize("你好！！"))
    }

    @Test
    fun `handles digits next to chinese`() {
        assertEquals(listOf("2026", "年"), CjkTokenizer.tokenize("2026年"))
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(CjkTokenizer.tokenize("").isEmpty())
        assertTrue(CjkTokenizer.tokenize("   ").isEmpty())
    }

    @Test
    fun `indexed text is space joined`() {
        assertEquals("今天 天的 的天 天气", CjkTokenizer.indexedText("今天的天气"))
    }

    @Test
    fun `match expression quotes and ORs distinct tokens`() {
        val expression = CjkTokenizer.matchExpression("今天的天气")
        assertEquals("\"今天\" OR \"天的\" OR \"的天\" OR \"天气\"", expression)
    }

    @Test
    fun `match expression deduplicates repeated terms`() {
        assertEquals("\"天气\"", CjkTokenizer.matchExpression("天气 天气"))
    }

    @Test
    fun `match expression is empty for punctuation only`() {
        assertEquals("", CjkTokenizer.matchExpression("！？。"))
    }

    @Test
    fun `quotes inside tokens are neutralised`() {
        val expression = CjkTokenizer.matchExpression("say \"hi\"")
        assertEquals("\"say\" OR \"hi\"", expression)
    }
}
