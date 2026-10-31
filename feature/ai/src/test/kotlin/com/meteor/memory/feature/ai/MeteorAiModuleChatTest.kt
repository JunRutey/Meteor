package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.PersonEntry
import com.meteor.memory.core.storage.PersonProfile
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.Relation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class MeteorAiModuleChatTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "meteor-chat-test")
    private val config = AiConfig(provider = AiPresets.DEEPSEEK, apiKey = "k")
    private val fixedNow = Instant.parse("2026-01-01T00:00:00Z")

    private fun diary(name: String, at: String, body: String) = Document(
        file = File(root, name),
        personId = null,
        type = RecordType.DIARY,
        timestamp = Instant.parse(at),
        source = RecordSource.USER,
        body = body,
    )

    private class CapturingProvider : AiProvider {
        val requests = mutableListOf<List<AiMessage>>()

        override suspend fun complete(
            messages: List<AiMessage>,
            tools: List<AiToolSpec>,
            maxTokens: Int,
        ): AiCompletion {
            requests += messages.toList()
            return AiCompletion("好的", emptyList())
        }

        override suspend fun probe() = ProbeResult(ok = true, code = 200)
    }

    private class StubPeople : PersonQueries {
        override fun list(): List<PersonEntry> = emptyList()

        override fun read(personId: String): PersonProfile? = null

        override fun create(displayName: String, relation: Relation?, notes: String): String = "id"

        override fun update(personId: String, displayName: String, relation: Relation?, notes: String) = Unit
    }

    private class StubMoods : MoodQueries {
        override fun range(from: LocalDate, to: LocalDate): Map<LocalDate, Mood> = emptyMap()

        override fun read(date: LocalDate): Mood? = null

        override fun save(date: LocalDate, mood: Mood) = Unit
    }

    private class StubPersonaStore : PersonaStore {
        override fun read(personId: String): PersonaSnapshot? = null

        override fun write(snapshot: PersonaSnapshot) = Unit
    }

    private fun module(provider: AiProvider, documents: List<Document>) = MeteorAiModule(
        rootDir = root,
        scanner = RecordScanner { documents },
        personQueries = StubPeople(),
        moodQueries = StubMoods(),
        personaStore = StubPersonaStore(),
        skillLoader = { CHAT_SKILL },
        providerFactory = { provider },
        now = { fixedNow },
    )

    @Test
    fun `question keywords pull a short diary excerpt into the prompt`() = runBlocking {
        val provider = CapturingProvider()
        val ai = module(
            provider,
            listOf(diary("d1.md", "2025-03-14T10:00:00Z", "今天和小明去爬山，很累")),
        )

        ai.chat(ChatRequest(skillName = "test-chat", question = "爬山", config = config))

        val system = provider.requests.single().first().content.orEmpty()
        assertTrue(system, system.contains("type=diary"))
        assertTrue(system, system.contains("record_id="))
        assertTrue(system, system.contains("爬山"))
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `a scoped persona chat does not prefetch other records`() = runBlocking {
        val provider = CapturingProvider()
        val ai = module(
            provider,
            listOf(diary("d1.md", "2025-03-14T10:00:00Z", "今天和小明去爬山，很累")),
        )

        ai.chat(ChatRequest(skillName = "test-chat", question = "爬山", personId = "p1", config = config))

        val system = provider.requests.single().first().content.orEmpty()
        assertFalse(system, system.contains("今天和小明去爬山"))
        assertTrue(system, system.contains("No archive record matched"))
    }

    private companion object {
        val CHAT_SKILL = """
            ---
            name: test-chat
            description: d
            stage: chat
            requires-person: false
            ---
            body
        """.trimIndent()
    }
}
