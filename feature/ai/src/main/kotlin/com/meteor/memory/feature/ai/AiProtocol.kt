package com.meteor.memory.feature.ai

import org.json.JSONArray
import org.json.JSONObject

/** Wire roles of an OpenAI-compatible chat completion. */
enum class AiRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

/** One tool invocation requested by the model. */
data class AiToolCall(val id: String, val name: String, val arguments: String)

/**
 * One chat message. [toolCalls] is only used by assistant messages and
 * [toolCallId] only by tool messages.
 */
data class AiMessage(
    val role: AiRole,
    val content: String?,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    companion object {
        fun system(text: String) = AiMessage(AiRole.SYSTEM, text)

        fun user(text: String) = AiMessage(AiRole.USER, text)

        fun assistant(text: String?, toolCalls: List<AiToolCall>) =
            AiMessage(AiRole.ASSISTANT, text, toolCalls)

        fun tool(callId: String, text: String) = AiMessage(AiRole.TOOL, text, toolCallId = callId)
    }
}

/** Tool declaration sent to the model. [parametersJson] is a JSON-schema object. */
data class AiToolSpec(val name: String, val description: String, val parametersJson: String)

/** One completion: text, tool calls, or both. */
data class AiCompletion(val content: String?, val toolCalls: List<AiToolCall>)

/** Result of the cheap `/models` probe used by "test connection". */
data class ProbeResult(val ok: Boolean, val code: Int)

/**
 * Provider failure. Carries the HTTP status and a short machine-ish reason —
 * never the request body, so a leaked log line cannot contain user content.
 */
class AiProviderException(val code: Int, message: String) : Exception(message)

/** OpenAI-compatible request/response encoding, kept pure so it is unit-testable. */
object AiProtocol {

    fun requestBody(
        model: String,
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
        maxTokens: Int,
    ): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("messages", JSONArray(messages.map { messageJson(it) }))
        root.put("max_tokens", maxTokens)
        root.put("temperature", 0.7)
        if (tools.isNotEmpty()) {
            root.put("tools", JSONArray(tools.map { spec ->
                JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("parameters", JSONObject(spec.parametersJson))
                    })
                }
            }))
            root.put("tool_choice", "auto")
        }
        return root.toString()
    }

    fun parseCompletion(body: String): AiCompletion {
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw AiProviderException(0, "invalid-json") }
        val choices = root.optJSONArray("choices") ?: throw AiProviderException(0, "no-choices")
        if (choices.length() == 0) throw AiProviderException(0, "no-choices")

        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiProviderException(0, "no-message")

        val rawContent = message.opt("content")
        val content = if (rawContent == null || rawContent == JSONObject.NULL) {
            null
        } else {
            rawContent.toString().takeIf { it.isNotBlank() }
        }

        val calls = message.optJSONArray("tool_calls")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val function = item.optJSONObject("function") ?: return@mapNotNull null
                val name = function.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AiToolCall(
                    id = item.optString("id").ifBlank { "call_$index" },
                    name = name,
                    arguments = function.optString("arguments").ifBlank { "{}" },
                )
            }
        }.orEmpty()

        return AiCompletion(content, calls)
    }

    /**
     * Only the provider's structured `error.message`, never the whole body: a
     * proxy may echo the request, and that request contains archive excerpts.
     */
    fun errorMessage(body: String): String {
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return (message ?: "").trim().take(MAX_ERROR_CHARS).ifBlank { "provider-error" }
    }

    private fun messageJson(message: AiMessage): JSONObject = JSONObject().apply {
        put("role", message.role.wire)
        when (message.role) {
            AiRole.TOOL -> {
                put("tool_call_id", message.toolCallId.orEmpty())
                put("content", message.content.orEmpty())
            }

            AiRole.ASSISTANT -> {
                if (message.toolCalls.isEmpty()) {
                    put("content", message.content.orEmpty())
                } else {
                    put("content", message.content ?: JSONObject.NULL)
                    put("tool_calls", JSONArray(message.toolCalls.map { call ->
                        JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            })
                        }
                    }))
                }
            }

            else -> put("content", message.content.orEmpty())
        }
    }

    private const val MAX_ERROR_CHARS = 300
}
