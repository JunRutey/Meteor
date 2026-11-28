package com.meteor.memory.ui.search

import com.meteor.memory.core.index.CjkTokenizer
import com.meteor.memory.core.index.TextMatcher
import com.meteor.memory.core.storage.StoredAnswer
import com.meteor.memory.core.storage.StoredQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Searchable-text contract for 每日一问 (ADR-074): one question is one hit made
 * of its text plus every answer, and ranking reuses the main index's [TextMatcher]
 * so a lone CJK character still matches.
 */
class QuestionSearchableTextTest {

    private val created = Instant.parse("2026-11-27T14:10:00Z")

    private fun question(vararg answers: String): StoredQuestion = StoredQuestion(
        id = "a".repeat(32),
        text = "我为什么想换工作？",
        createdAt = created,
        answers = answers.mapIndexed { index, text ->
            StoredAnswer(id = index.toString().padStart(32, '0'), text = text, answeredAt = created)
        },
    )

    private fun score(question: StoredQuestion, query: String): Int =
        TextMatcher.countMatches(
            questionSearchableText(question),
            CjkTokenizer.tokenize(query).distinct(),
        )

    @Test
    fun `searchable text joins question and all answers`() {
        val text = questionSearchableText(question("因为想学新东西", "其实是不喜欢团队"))

        assertTrue(text.contains("我为什么想换工作？"))
        assertTrue(text.contains("因为想学新东西"))
        assertTrue(text.contains("其实是不喜欢团队"))
    }

    @Test
    fun `an answer body is searchable`() {
        assertTrue(score(question("因为想学新东西"), "学新东西") > 0)
    }

    @Test
    fun `the question text is searchable`() {
        assertTrue(score(question(), "换工作") > 0)
    }

    @Test
    fun `an unrelated term does not match`() {
        assertEquals(0, score(question("因为想学新东西"), "秦始皇"))
    }

    @Test
    fun `a lone CJK character matches through the substring matcher`() {
        assertTrue(score(question("因为想学新东西"), "换") > 0)
    }

    @Test
    fun `a deleted answer stops matching`() {
        val before = question("因为想学新东西", "其实是不喜欢团队")
        val after = question("因为想学新东西")

        assertTrue(score(before, "团队") > 0)
        assertEquals(0, score(after, "团队"))
    }

    @Test
    fun `snippet is derived from the joined text`() {
        val snippet = TextMatcher.snippet(
            questionSearchableText(question("其实是不喜欢现在的团队")),
            listOf("团队"),
            160,
        )
        assertTrue(snippet.contains("团队"))
    }
}
