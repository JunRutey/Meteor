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

    private class FakeToolbox(private val approvalRequired: Boolean = false) : Toolbox {
        val calls = mutableListOf<Triple<String, String, String?>>()

        override fun specs(): List<AiToolSpec> =
            listOf(AiToolSpec("search_archive", "search", """{"type":"object"}"""))

        override fun execute(name: String, arguments: String, allowedPersonId: String?): String {
            calls += Triple(name, arguments, allowedPersonId)
            return """{"ok":true}"""
        }

        override fun requiresApproval(name: String): Boolean = approvalRequired
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
    fun `a content-filter refusal is reported as a refusal`() {
        val agent = AiAgent(
            FakeProvider(
                AiCompletion("", emptyList(), finishReason = "content_filter"),
            ),
            FakeToolbox(),
        )

        val error = assertThrows(
            AiProviderException::class.java,
            ThrowingRunnable { runBlocking { agent.run(conversation(), allowedPersonId = null) } },
        )

        assertEquals("content-filter", error.message)
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
    fun `mutating tools are denied without explicit approval`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(null, listOf(AiToolCall("c1", "save_diary", """{"body":"change"}"""))),
            AiCompletion("not changed", emptyList()),
        )
        val toolbox = FakeToolbox(approvalRequired = true)
        val events = mutableListOf<String>()
        val listener = object : AiChatListener {
            override fun onContent(delta: String) = Unit
            override fun onReasoning(delta: String) = Unit
            override fun onToolCall(name: String, arguments: String) = Unit
            override fun onToolResult(name: String) = Unit
            override suspend fun onToolApproval(name: String, arguments: String): Boolean = false
            override fun onToolResult(name: String, result: String) {
                events += result
            }
        }

        AiAgent(provider, toolbox, listener = listener).run(conversation(), allowedPersonId = null)

        assertTrue(toolbox.calls.isEmpty())
        assertTrue(events.any { it.contains("user-denied") })
    }

    @Test
    fun `mutating tools fail closed when no approval listener exists`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(null, listOf(AiToolCall("c1", "save_diary", """{"body":"change"}"""))),
            AiCompletion("not changed", emptyList()),
        )
        val toolbox = FakeToolbox(approvalRequired = true)

        AiAgent(provider, toolbox).run(conversation(), allowedPersonId = null)

        assertTrue(toolbox.calls.isEmpty())
    }

    @Test
    fun `mutating tools execute only after explicit approval`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(null, listOf(AiToolCall("c1", "save_diary", """{"body":"change"}"""))),
            AiCompletion("done", emptyList()),
        )
        val toolbox = FakeToolbox(approvalRequired = true)
        val listener = object : AiChatListener {
            override fun onContent(delta: String) = Unit
            override fun onReasoning(delta: String) = Unit
            override fun onToolCall(name: String, arguments: String) = Unit
            override fun onToolResult(name: String) = Unit
            override suspend fun onToolApproval(name: String, arguments: String): Boolean =
                name == "save_diary" && arguments.contains("change")
        }

        AiAgent(provider, toolbox, listener = listener).run(conversation(), allowedPersonId = null)

        assertEquals(1, toolbox.calls.size)
    }

    @Test
    fun `mutations from one model turn share one approval decision`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(
                null,
                listOf(
                    AiToolCall("c1", "save_diary", """{"date":"2026-03-08","body":"one"}"""),
                    AiToolCall("c2", "save_diary", """{"date":"2026-03-09","body":"two"}"""),
                ),
            ),
            AiCompletion("done", emptyList()),
        )
        val toolbox = FakeToolbox(approvalRequired = true)
        val approvalBatches = mutableListOf<List<AiToolApproval>>()
        val listener = object : AiChatListener {
            override fun onContent(delta: String) = Unit
            override fun onReasoning(delta: String) = Unit
            override fun onToolCall(name: String, arguments: String) = Unit
            override fun onToolResult(name: String) = Unit

            override suspend fun onToolApprovals(approvals: List<AiToolApproval>): Boolean {
                approvalBatches += approvals
                return true
            }
        }

        AiAgent(provider, toolbox, listener = listener).run(conversation(), allowedPersonId = null)

        assertEquals(1, approvalBatches.size)
        assertEquals(
            listOf(
                AiToolApproval("save_diary", """{"date":"2026-03-08","body":"one"}"""),
                AiToolApproval("save_diary", """{"date":"2026-03-09","body":"two"}"""),
            ),
            approvalBatches.single(),
        )
        assertEquals(2, toolbox.calls.size)
    }

    @Test
    fun `denying a batch prevents every protected tool from executing`() = runBlocking {
        val provider = FakeProvider(
            AiCompletion(
                null,
                listOf(
                    AiToolCall("c1", "save_diary", """{"body":"one"}"""),
                    AiToolCall("c2", "save_diary", """{"body":"two"}"""),
                ),
            ),
            AiCompletion("not changed", emptyList()),
        )
        val toolbox = FakeToolbox(approvalRequired = true)
        val results = mutableListOf<String>()
        var approvalRequestCount = 0
        val listener = object : AiChatListener {
            override fun onContent(delta: String) = Unit
            override fun onReasoning(delta: String) = Unit
            override fun onToolCall(name: String, arguments: String) = Unit
            override fun onToolResult(name: String) = Unit

            override suspend fun onToolApprovals(approvals: List<AiToolApproval>): Boolean {
                approvalRequestCount++
                assertEquals(2, approvals.size)
                return false
            }

            override fun onToolResult(name: String, result: String) {
                results += result
            }
        }

        AiAgent(provider, toolbox, listener = listener).run(conversation(), allowedPersonId = null)

        assertEquals(1, approvalRequestCount)
        assertTrue(toolbox.calls.isEmpty())
        assertEquals(2, results.size)
        assertTrue(results.all { it.contains("user-denied") })
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
