package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.MoodQueries
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

    /**
     * The question path under test never reaches these collaborators' methods;
     * only their presence is required. A proxy keeps the test focused instead of
     * re-declaring every interface member by hand.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> stub(type: Class<T>): T =
        java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "list" -> emptyList<Any>()
                "range" -> emptyMap<Any, Any>()
                "create" -> "id"
                "toString" -> "stub"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as T

    private fun module(provider: AiProvider, documents: List<Document>) = MeteorAiModule(
        rootDir = root,
        scanner = RecordScanner { documents },
        personQueries = stub(PersonQueries::class.java),
        personWriter = object : PersonWriter {
            override fun create(displayName: String, relation: Relation?, notes: String): String = "id"

            override fun update(personId: String, displayName: String, relation: Relation?, notes: String) = Unit
        },
        moodQueries = stub(MoodQueries::class.java),
        diaryQueries = stub(DiaryQueries::class.java),
        personaStore = stub(PersonaStore::class.java),
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

    @Test
    fun `a dated question prefetches that day instead of searching keywords`() = runBlocking {
        val provider = CapturingProvider()
        val ai = module(
            provider,
            listOf(diary("d2.md", "2026-10-31T10:00:00Z", "今天和小明去爬山，很累")),
        )

        ai.chat(ChatRequest(skillName = "test-chat", question = "2026-10-31 那天写了什么", config = config))

        val system = provider.requests.single().first().content.orEmpty()
        assertTrue(system, system.contains("Records in 2026-10-31..2026-10-31"))
        assertTrue(system, system.contains("爬山"))
        assertTrue(system, system.contains("Current date:"))
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
