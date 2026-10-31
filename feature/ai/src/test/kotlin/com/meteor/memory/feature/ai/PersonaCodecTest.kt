package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Relation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class PersonaCodecTest {

    private val snapshot = PersonaSnapshot(
        personId = "p1",
        displayName = "小明",
        relation = Relation.FRIEND,
        recordCount = 2,
        diaryMentions = 3,
        firstSeen = Instant.parse("2024-05-01T00:00:00Z"),
        lastSeen = Instant.parse("2026-10-01T00:00:00Z"),
        topics = listOf(PersonaTopic("爬山", 3), PersonaTopic("工作", 1)),
        quotes = listOf(PersonaQuote(Instant.parse("2025-03-14T10:00:00Z"), "那天下雨")),
        generatedAt = Instant.parse("2026-10-31T09:00:00Z"),
        savedAt = Instant.parse("2026-10-31T09:05:00Z"),
    )

    @Test
    fun `encode and decode round-trip every derived field`() {
        val decoded = PersonaCodec.decode("p1", PersonaCodec.encode(snapshot))

        assertEquals(snapshot.copy(savedAt = null), decoded)
    }

    @Test
    fun `a hand-written body after the separator does not break the parse`() {
        val encoded = PersonaCodec.encode(snapshot) + "手写补充：他爱喝冰美式\n"

        val decoded = PersonaCodec.decode("p1", encoded)

        assertEquals("小明", decoded?.displayName)
        assertEquals(2, decoded?.recordCount)
    }

    @Test
    fun `the distilled body, provider and skill survive a round-trip`() {
        val distilled = snapshot.copy(
            body = "# 小明 — 数字人格\n## Layer 0：硬规则\n不突然温柔。",
            provider = "deepseek",
            skill = "meteor-persona-distill",
        )

        val decoded = PersonaCodec.decode("p1", PersonaCodec.encode(distilled))!!

        assertEquals(distilled.body, decoded.body)
        assertEquals("deepseek", decoded.provider)
        assertEquals("meteor-persona-distill", decoded.skill)
        assertEquals(true, decoded.distilled)
    }

    @Test
    fun `a body that is not a snapshot decodes to null`() {
        assertNull(PersonaCodec.decode("p1", "随手写的笔记\n"))
        assertNull(PersonaCodec.decode("p1", "name: 小明\n---\n"))
    }

    @Test
    fun `a missing display name is allowed`() {
        val anonymous = snapshot.copy(displayName = null, relation = null)

        val decoded = PersonaCodec.decode("p1", PersonaCodec.encode(anonymous))

        assertNull(decoded?.displayName)
        assertNull(decoded?.relation)
    }
}
