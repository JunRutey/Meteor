package com.meteor.memory.feature.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderStreamTest {

    private class FakeTransport(private val lines: List<String>) : HttpTransport {
        var lastBody: String = ""

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            lastBody = body
            return HttpResponse(500, "not used")
        }

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(200, """{"data":[]}""")

        override suspend fun postStream(
            url: String,
            headers: Map<String, String>,
            body: String,
            onLine: (String) -> Unit,
        ): HttpResponse {
            lastBody = body
            lines.forEach(onLine)
            return HttpResponse(200, "")
        }
    }

    @Test
    fun `stream accumulates text, reasoning, tool deltas and usage`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                """data: {"choices":[{"delta":{"reasoning_content":"想"}}]}""",
                """data: {"choices":[{"delta":{"content":"你"}}]}""",
                """data: {"choices":[{"delta":{"content":"好"}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read_diary","arguments":"{\"date\":"}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"2026-10-31\"}"}}]}}]}""",
                """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_cache_hit_tokens":4,"prompt_cache_miss_tokens":6}}""",
                "data: [DONE]",
            ),
        )
        val provider = OpenAiCompatibleProvider(
            config = AiConfig(provider = AiPresets.DEEPSEEK, apiKey = "k"),
            transport = transport,
        )
        val streamed = StringBuilder()
        val thought = StringBuilder()

        val completion = provider.completeStream(
            messages = listOf(AiMessage.user("q")),
            callback = object : AiStreamCallback {
                override fun onContent(delta: String) {
                    streamed.append(delta)
                }

                override fun onReasoning(delta: String) {
                    thought.append(delta)
                }
            },
        )

        assertEquals("你好", completion.content)
        assertEquals("想", completion.reasoningContent)
        assertEquals("你好", streamed.toString())
        assertEquals("想", thought.toString())
        assertEquals(1, completion.toolCalls.size)
        assertEquals("read_diary", completion.toolCalls[0].name)
        assertEquals("{\"date\":\"2026-10-31\"}", completion.toolCalls[0].arguments)
        assertEquals(15, completion.usage?.totalTokens)
        assertEquals(4, completion.usage?.cacheHitTokens)
        assertTrue(transport.lastBody.contains("\"stream\":true"))
        assertTrue(transport.lastBody.contains("include_usage"))
    }
}
