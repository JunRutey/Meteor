package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.PersonProfile
import com.meteor.memory.core.storage.PersonProfileCodec
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.Relation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class PersonaEngineTest {

    private fun doc(type: RecordType, personId: String?, at: String, body: String) = Document(
        file = File("record.md"),
        personId = personId,
        type = type,
        timestamp = Instant.parse(at),
        source = RecordSource.USER,
        body = body,
    )

    private val generated = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `mentions are diaries that link to the person`() {
        val corpus = listOf(
            doc(RecordType.DIARY, null, "2025-03-14T10:00:00Z", "今天和小明去了公园"),
            doc(RecordType.DIARY, null, "2025-03-15T10:00:00Z", "他说 [小明](meteor://person/p1) 要来"),
            doc(RecordType.CHAT_LOG, "p1", "2025-03-16T10:00:00Z", "你吃了吗"),
        )

        val snapshot = PersonaEngine.snapshot(
            personId = "p1",
            profile = PersonProfile("小明", Relation.FRIEND),
            corpus = corpus,
            generatedAt = generated,
        )

        assertEquals(1, snapshot.recordCount)
        assertEquals(1, snapshot.diaryMentions)
        assertEquals(Instant.parse("2025-03-15T10:00:00Z"), snapshot.firstSeen)
        assertEquals(Instant.parse("2025-03-16T10:00:00Z"), snapshot.lastSeen)
        assertEquals(2, snapshot.quotes.size)
        assertEquals("你吃了吗", snapshot.quotes[0].text)
        assertEquals("他说 小明 要来", snapshot.quotes[1].text)
    }

    @Test
    fun `the person's own name never becomes a topic`() {
        val corpus = listOf(
            doc(RecordType.DIARY, null, "2025-03-14T10:00:00Z", "[小明](meteor://person/p1) 爬山，爬山，爬山"),
        )

        val snapshot = PersonaEngine.snapshot(
            personId = "p1",
            profile = PersonProfile("小明", Relation.FRIEND),
            corpus = corpus,
            generatedAt = generated,
        )

        assertEquals("爬山", snapshot.topics.first().term)
        assertEquals(3, snapshot.topics.first().count)
        assertFalse(snapshot.topics.any { it.term == "小明" })
        assertFalse(snapshot.topics.any { it.term.contains("meteor") })
    }

    @Test
    fun `profile notes feed the topic list`() {
        // Same construction path as production: PersonQueries.read decodes the
        // stored profile body, it never builds a PersonProfile by hand.
        val profile = PersonProfileCodec.decode("name: 阿明\nrelation: friend\n---\n喜欢钓鱼\n")!!
        val snapshot = PersonaEngine.snapshot(
            personId = "p1",
            profile = profile,
            corpus = emptyList(),
            generatedAt = generated,
        )

        assertEquals("喜欢钓鱼", profile.notes)
        assertTrue(
            "topics=${snapshot.topics} notes='${profile.notes}'",
            snapshot.topics.any { it.term == "钓鱼" },
        )
    }

    @Test
    fun `an empty archive still produces a usable snapshot`() {
        val snapshot = PersonaEngine.snapshot(
            personId = "p1",
            profile = null,
            corpus = emptyList(),
            generatedAt = generated,
        )

        assertEquals(0, snapshot.recordCount)
        assertEquals(0, snapshot.diaryMentions)
        assertNull(snapshot.firstSeen)
        assertNull(snapshot.displayName)
        assertTrue(snapshot.topics.isEmpty())
        assertTrue(snapshot.quotes.isEmpty())
    }

    @Test
    fun `a stored persona record is never its own evidence`() {
        val corpus = listOf(
            doc(RecordType.PERSONA, "p1", "2026-01-01T00:00:00Z", "name: 小明\nrecords: 5\n---\n"),
            doc(RecordType.DIARY, null, "2025-03-14T10:00:00Z", "[小明](meteor://person/p1) 来了"),
        )

        val snapshot = PersonaEngine.snapshot("p1", null, corpus, generated)

        assertEquals(0, snapshot.recordCount)
        assertEquals(1, snapshot.diaryMentions)
    }
}
