package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class AskEngineTest {

    private fun doc(type: RecordType, personId: String?, at: String, body: String) = Document(
        file = File("record.md"),
        personId = personId,
        type = type,
        timestamp = Instant.parse(at),
        source = RecordSource.USER,
        body = body,
    )

    @Test
    fun `only records that contain the question are returned`() {
        val corpus = listOf(
            doc(RecordType.DIARY, null, "2025-01-02T10:00:00Z", "今天去爬山，很累"),
            doc(RecordType.DIARY, null, "2025-01-01T10:00:00Z", "在家加班写代码"),
        )

        val answer = AskEngine.answer(AskRequest("爬山"), corpus)

        assertEquals(2, answer.consultedRecords)
        assertEquals(1, answer.matches.size)
        assertTrue(answer.matches[0].snippet.contains("爬山"))
    }

    @Test
    fun `a person scope keeps their records and diaries that link to them`() {
        val corpus = listOf(
            doc(RecordType.DIARY, null, "2025-01-01T10:00:00Z", "[小明](meteor://person/p1) 来了"),
            doc(RecordType.DIARY, null, "2025-01-02T10:00:00Z", "和小红吃饭"),
            doc(RecordType.CHAT_LOG, "p1", "2025-01-03T10:00:00Z", "晚安"),
        )

        val answer = AskEngine.answer(AskRequest(question = "晚安", personId = "p1"), corpus)

        assertEquals(2, answer.consultedRecords)
        assertEquals(1, answer.matches.size)
        assertEquals(RecordType.CHAT_LOG, answer.matches[0].type)
    }

    @Test
    fun `moods and personas are not answer sources`() {
        val corpus = listOf(
            doc(RecordType.MOOD, null, "2025-01-01T10:00:00Z", "happy"),
            doc(RecordType.PERSONA, "p1", "2025-01-02T10:00:00Z", "name: 小明\n---\n"),
        )

        val answer = AskEngine.answer(AskRequest("happy"), corpus)

        assertEquals(0, answer.consultedRecords)
        assertTrue(answer.matches.isEmpty())
    }

    @Test
    fun `an unknown term yields nothing rather than a guess`() {
        val corpus = listOf(doc(RecordType.DIARY, null, "2025-01-01T10:00:00Z", "在家加班写代码"))

        val answer = AskEngine.answer(AskRequest("潜水艇"), corpus)

        assertEquals(1, answer.consultedRecords)
        assertTrue(answer.matches.isEmpty())
    }

    @Test
    fun `max hits is clamped instead of trusting the caller`() {
        val corpus = (1..30).map { index ->
            doc(RecordType.DIARY, null, "2025-01-01T10:00:00Z", "爬山第 $index 次")
        }

        val answer = AskEngine.answer(AskRequest("爬山", maxHits = 1000), corpus)

        assertEquals(20, answer.matches.size)
    }

    @Test
    fun `the most recent record wins an otherwise equal tie`() {
        val corpus = listOf(
            doc(RecordType.DIARY, null, "2025-01-01T10:00:00Z", "爬山"),
            doc(RecordType.DIARY, null, "2025-02-01T10:00:00Z", "爬山"),
        )

        val answer = AskEngine.answer(AskRequest("爬山"), corpus)

        assertEquals(Instant.parse("2025-02-01T10:00:00Z"), answer.matches[0].at)
    }
}
