package com.meteor.memory.feature.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.Assert.assertThrows

class AiAgentTest {

    private class FakeProvider(vararg replies: AiCompletion) : AiProvider {
        private val queue = replies.toMutableList()
        val seen = mutableListOf<List<AiMessage>>()

        override suspend fun complete(
            messages: List<AiMessage>,
            tools: List<AiToolSpec>,
            maxTokens: Int,
        ): AiCompletion {
            seen += messages.toList()
            return queue.removeAt(0)
        }

        override suspend fun probe() = ProbeResult(ok = true, code = 200)
    }

    private class FakeToolbox : Toolbox {
        val calls = mutableListOf<Triple<String, String, String?>>()

        override fun specs(): List<AiToolSpec> =
            listOf(AiToolSpec("search_archive", "search", """{"type":"object"}"""))

        override fun execute(name: String, arguments: String, allowedPersonId: String?): String {
            calls += Triple(name, arguments, allowedPersonId)
            return """{"ok":true}"""
        }
    }

    private fun conversation() = listOf(AiMessage.system("system"), AiMessage.user("question"))

    @Test
    fun `runs a read-only tool then returns the final answer`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(null, listOf(AiToolCall("c1", "search_archive", """{"query":"x"}"""))),
            AiCompletion("final answer", emptyList()),
        )
        val toolbox = FakeToolbox()

        val result = AiAgent(provider, toolbox).run(conversation(), allowedPersonId = "p1")

        assertEquals("final answer", result.content)
        assertEquals(2, result.steps)
        assertEquals(1, result.toolCalls)
        assertEquals(1, toolbox.calls.size)
        assertEquals("p1", toolbox.calls[0].third)
        assertEquals("search_archive", toolbox.calls[0].first)
    }

    @Test
    fun `caps tool calls per step`() = runBlocking {
        val calls = (1..10).map { AiToolCall("c$it", "search_archive", "{}") }
        val provider = FakeProvider(AiCompletion(null, calls), AiCompletion("done", emptyList()))
        val toolbox = FakeToolbox()

        AiAgent(provider, toolbox, maxToolCallsPerStep = 4)
            .run(conversation(), allowedPersonId = null)

        assertEquals(4, toolbox.calls.size)
    }

    @Test
    fun `gives up after the step limit instead of looping forever`() {
        val replies = (1..10)
            .map { AiCompletion(null, listOf(AiToolCall("c$it", "search_archive", "{}"))) }
            .toTypedArray()
        val agent = AiAgent(FakeProvider(*replies), FakeToolbox(), maxSteps = 3)

        val error = assertThrows(
            AiProviderException::class.java,
            ThrowingRunnable { runBlocking { agent.run(conversation(), allowedPersonId = null) } },
        )

        assertEquals("step-limit", error.message)
    }

    @Test
    fun `an empty answer is an error, not a blank bubble`() {
        val agent = AiAgent(FakeProvider(AiCompletion("  ", emptyList())), FakeToolbox())

        val error = assertThrows(
            AiProviderException::class.java,
            ThrowingRunnable { runBlocking { agent.run(conversation(), allowedPersonId = null) } },
        )

        assertEquals("empty-response", error.message)
    }

    @Test
    fun `usage is summed across tool rounds`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(
                content = null,
                toolCalls = listOf(AiToolCall("c1", "search_archive", "{}")),
                usage = AiUsage(promptTokens = 100, completionTokens = 10),
            ),
            AiCompletion(
                content = "done",
                toolCalls = emptyList(),
                usage = AiUsage(promptTokens = 200, completionTokens = 20),
            ),
        )

        val result = AiAgent(provider, FakeToolbox()).run(conversation(), allowedPersonId = null)

        assertEquals(330, result.usage.totalTokens)
        assertEquals(300, result.usage.promptTokens)
        assertEquals(30, result.usage.completionTokens)
    }

    @Test
    fun `streaming reports content and the tool chain`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(null, listOf(AiToolCall("c1", "search_archive", "{}"))),
            AiCompletion("done", emptyList()),
        )
        val events = mutableListOf<String>()
        val listener = object : AiChatListener {
            override fun onContent(delta: String) {
                events += "content:$delta"
            }

            override fun onReasoning(delta: String) {
                events += "reasoning:$delta"
            }

            override fun onToolCall(name: String, arguments: String) {
                events += "call:$name"
            }

            override fun onToolResult(name: String) {
                events += "result:$name"
            }
        }

        val result = AiAgent(provider, FakeToolbox(), listener = listener)
            .run(conversation(), allowedPersonId = null)

        assertEquals("done", result.content)
        assertTrue(events.contains("call:search_archive"))
        assertTrue(events.contains("result:search_archive"))
        assertTrue(events.contains("content:done"))
    }

    @Test
    fun `the conversation must start with a system prompt`() {
        val error = assertThrows(
            IllegalArgumentException::class.java,
            ThrowingRunnable {
                runBlocking {
                    AiAgent(FakeProvider(AiCompletion("x", emptyList())), FakeToolbox())
                        .run(listOf(AiMessage.user("hi")), allowedPersonId = null)
                }
            },
        )

        assertTrue(error.message!!.contains("system"))
    }
}
