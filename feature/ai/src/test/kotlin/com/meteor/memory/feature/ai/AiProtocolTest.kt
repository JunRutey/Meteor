package com.meteor.memory.feature.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProtocolTest {

    @Test
    fun `request carries model, messages and max tokens`() {
        val body = AiProtocol.requestBody(
            model = "deepseek-chat",
            messages = listOf(AiMessage.system("sys"), AiMessage.user("hi")),
            tools = emptyList(),
            maxTokens = 128,
        )
        val json = JSONObject(body)

        assertEquals("deepseek-chat", json.getString("model"))
        assertEquals(128, json.getInt("max_tokens"))
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `tools are encoded as function schemas`() {
        val body = AiProtocol.requestBody(
            model = "m",
            messages = listOf(AiMessage.user("q")),
            tools = listOf(AiToolSpec("search_archive", "search", """{"type":"object"}""")),
            maxTokens = 10,
        )
        val tool = JSONObject(body).getJSONArray("tools").getJSONObject(0)

        assertEquals("function", tool.getString("type"))
        assertEquals("search_archive", tool.getJSONObject("function").getString("name"))
    }

    @Test
    fun `assistant tool call and tool result encode`() {
        val body = AiProtocol.requestBody(
            model = "m",
            messages = listOf(
                AiMessage.assistant(null, listOf(AiToolCall("c1", "search_archive", "{}"))),
                AiMessage.tool("c1", "result"),
            ),
            tools = emptyList(),
            maxTokens = 10,
        )
        val messages = JSONObject(body).getJSONArray("messages")
        val assistant = messages.getJSONObject(0)
        val tool = messages.getJSONObject(1)

        assertEquals("c1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", tool.getString("role"))
        assertEquals("c1", tool.getString("tool_call_id"))
    }

    @Test
    fun `parses a plain content response`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}"""

        val completion = AiProtocol.parseCompletion(body)

        assertEquals("hello", completion.content)
        assertTrue(completion.toolCalls.isEmpty())
    }

    @Test
    fun `parses tool calls and a null content`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"c1","type":"function","function":{"name":"read_record","arguments":"{\"record_id\":\"a\"}"}}
            ]}}]}
        """.trimIndent()

        val completion = AiProtocol.parseCompletion(body)

        assertNull(completion.content)
        assertEquals(1, completion.toolCalls.size)
        assertEquals("read_record", completion.toolCalls[0].name)
        assertEquals("{\"record_id\":\"a\"}", completion.toolCalls[0].arguments)
    }

    @Test
    fun `a malformed response fails with a code-only reason`() {
        try {
            AiProtocol.parseCompletion("not json")
            throw AssertionError("expected AiProviderException")
        } catch (expected: AiProviderException) {
            assertEquals("invalid-json", expected.message)
        }
    }

    @Test
    fun `error text uses only the structured message`() {
        assertEquals("bad key", AiProtocol.errorMessage("""{"error":{"message":"bad key"}}"""))
        assertNotEquals(
            "my secret diary text",
            AiProtocol.errorMessage("my secret diary text"),
        )
        assertEquals("provider-error", AiProtocol.errorMessage("oops"))
    }
}
