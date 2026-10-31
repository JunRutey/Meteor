package com.meteor.memory.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TimePhraseTest {

    private val today = LocalDate.of(2026, 10, 31)

    @Test
    fun `today is a single day`() {
        val window = TimePhrase.parse("今天写了什么", today)!!
        assertEquals(today, window.from)
        assertEquals(today, window.to)
    }

    @Test
    fun `yesterday and the day before`() {
        assertEquals(today.minusDays(1), TimePhrase.parse("昨天呢", today)!!.from)
        assertEquals(today.minusDays(2), TimePhrase.parse("前天呢", today)!!.from)
    }

    @Test
    fun `this week runs monday to sunday`() {
        val window = TimePhrase.parse("这周都干嘛了", today)!!
        assertEquals(LocalDate.of(2026, 10, 26), window.from)
        assertEquals(LocalDate.of(2026, 11, 1), window.to)
    }

    @Test
    fun `last week is the previous monday`() {
        val window = TimePhrase.parse("上周", today)!!
        assertEquals(LocalDate.of(2026, 10, 19), window.from)
        assertEquals(LocalDate.of(2026, 10, 25), window.to)
    }

    @Test
    fun `last month is the full calendar month`() {
        val window = TimePhrase.parse("上个月我去了哪", today)!!
        assertEquals(LocalDate.of(2026, 9, 1), window.from)
        assertEquals(LocalDate.of(2026, 9, 30), window.to)
    }

    @Test
    fun `last n days ends today`() {
        val window = TimePhrase.parse("最近7天", today)!!
        assertEquals(LocalDate.of(2026, 10, 25), window.from)
        assertEquals(today, window.to)
    }

    @Test
    fun `iso and chinese dates are accepted`() {
        assertEquals(LocalDate.of(2026, 3, 5), TimePhrase.parse("2026-03-05 那天", today)!!.from)
        assertEquals(LocalDate.of(2026, 3, 5), TimePhrase.parse("2026年3月5日", today)!!.from)
    }

    @Test
    fun `a bare month and day uses the past year when needed`() {
        assertEquals(LocalDate.of(2026, 3, 5), TimePhrase.parse("3月5日", today)!!.from)
        assertEquals(LocalDate.of(2025, 12, 31), TimePhrase.parse("12月31日", today)!!.from)
    }

    @Test
    fun `a whole year is a range`() {
        val window = TimePhrase.parse("去年发生了什么", today)!!
        assertEquals(LocalDate.of(2025, 1, 1), window.from)
        assertEquals(LocalDate.of(2025, 12, 31), window.to)
    }

    @Test
    fun `no time phrase means no window`() {
        assertNull(TimePhrase.parse("我最近在学什么", today))
    }
}
