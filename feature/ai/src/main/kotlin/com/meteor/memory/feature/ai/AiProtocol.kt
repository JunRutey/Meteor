package com.meteor.memory.feature.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * `JSONObject.optString` renders a JSON `null` as the literal text `"null"`.
 *
 * Streaming deltas carry `"content": null` / `"reasoning_content": null` while
 * the model is only emitting tool calls, so reading them with `optString`
 * appended a literal "null" to the answer for every such delta. Treat JSON
 * null (and a literal "null" string) as absent instead.
 */
internal fun JSONObject.optTextOrNull(name: String): String? {
    val raw = opt(name) ?: return null
    if (raw === JSONObject.NULL) return null
    val text = raw.toString()
    return text.takeIf { it.isNotEmpty() && it != "null" }
}

/** Wire roles of an OpenAI-compatible chat completion. */
enum class AiRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

/** One tool invocation requested by the model. */
data class AiToolCall(val id: String, val name: String, val arguments: String)

data class AiFileReference(
    val id: String,
    val fileName: String,
    val purpose: String = "user_data",
)

data class AiCitation(
    val url: String,
    val title: String,
    val startIndex: Int,
    val endIndex: Int,
)

data class AiWebSource(
    val title: String,
    val url: String,
    val snippet: String = "",
    val siteName: String? = null,
    val siteIcon: String? = null,
)

data class AiServerToolEvent(
    val type: String,
    val toolName: String? = null,
    val action: String? = null,
    val url: String? = null,
    val queries: List<String> = emptyList(),
    val sources: List<AiWebSource> = emptyList(),
    val status: String? = null,
)

data class AiProviderOptions(
    val deepSeekWebSearch: Boolean = false,
    val deepSeekReadUrl: Boolean = false,
)

/**
 * One chat message. [toolCalls] is only used by assistant messages,
 * [toolCallId] only by tool messages, and [reasoningContent] only by assistant
 * messages when the provider returns a thinking trace (DeepSeek requires it to
 * be echoed back while tools are in play).
 */
data class AiMessage(
    val role: AiRole,
    val content: String?,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val reasoningContent: String? = null,
    val serverToolCallsJson: String? = null,
    val files: List<AiFileReference> = emptyList(),
    val citations: List<AiCitation> = emptyList(),
) {
    companion object {
        fun system(text: String) = AiMessage(AiRole.SYSTEM, text)

        fun user(text: String, files: List<AiFileReference> = emptyList()) =
            AiMessage(AiRole.USER, text, files = files)

        fun assistant(text: String?, toolCalls: List<AiToolCall>) =
            AiMessage(AiRole.ASSISTANT, text, toolCalls)

        fun tool(callId: String, text: String) = AiMessage(AiRole.TOOL, text, toolCallId = callId)
    }
}

/** Tool declaration sent to the model. [parametersJson] is a JSON-schema object. */
data class AiToolSpec(val name: String, val description: String, val parametersJson: String)

/**
 * Token accounting returned by the provider. DeepSeek reports cache hits
 * separately (`prompt_cache_hit_tokens`); OpenAI nests it under
 * `prompt_tokens_details.cached_tokens`. Both are normalised here.
 */
data class AiUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    /** Cache-hit denominator, zero when the provider reported no cache data. */
    val cacheTokens: Int get() = cacheHitTokens + cacheMissTokens

    fun merge(other: AiUsage) = AiUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cacheHitTokens = cacheHitTokens + other.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + other.cacheMissTokens,
    )
}

/** One completion: text, tool calls, and how many tokens it cost. */
data class AiCompletion(
    val content: String?,
    val toolCalls: List<AiToolCall>,
    val usage: AiUsage? = null,
    val reasoningContent: String? = null,
    val serverToolCallsJson: String? = null,
    val citations: List<AiCitation> = emptyList(),
    val title: String? = null,
    val finishReason: String? = null,
)

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
        reasoning: ReasoningMode = ReasoningMode.AUTO,
        provider: String = AiPresets.DEEPSEEK,
        stream: Boolean = false,
        providerOptions: AiProviderOptions = AiProviderOptions(),
    ): String {
        val hasServerTools = providerOptions.deepSeekWebSearch || providerOptions.deepSeekReadUrl
        require(!hasServerTools || provider == AiPresets.DEEPSEEK) {
            "DeepSeek server tools require the DeepSeek provider"
        }
        require(!hasServerTools || tools.isEmpty()) {
            "DeepSeek server tools cannot be combined with function tools"
        }
        val root = JSONObject()
        root.put("model", model)
        root.put("messages", JSONArray(messages.map { messageJson(it, provider) }))
        root.put("max_tokens", maxTokens)
        root.put("temperature", 0.7)
        if (stream) {
            root.put("stream", true)
            // DeepSeek/OpenAI only report usage in the final chunk when asked.
            root.put("stream_options", JSONObject().put("include_usage", true))
        }
        // Thinking parameters are DeepSeek-specific; other providers may reject
        // unknown body fields, so they are only sent for DeepSeek (ADR-027).
        if (provider == AiPresets.DEEPSEEK && reasoning != ReasoningMode.AUTO) {
            root.put(
                "thinking",
                JSONObject().put("type", if (reasoning == ReasoningMode.OFF) "disabled" else "enabled"),
            )
            reasoning.effort?.let { root.put("reasoning_effort", it) }
        }
        if (hasServerTools) {
            root.put("tools", JSONArray(buildList {
                if (providerOptions.deepSeekWebSearch) {
                    add(JSONObject().put("type", "deepseek_web_search"))
                }
                if (providerOptions.deepSeekReadUrl) {
                    add(JSONObject().put("type", "deepseek_read_url"))
                }
            }))
        } else if (tools.isNotEmpty()) {
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
        root.optJSONObject("error")?.let { error ->
            throw AiProviderException(0, error.optString("message").ifBlank { "provider-error" })
        }
        val choices = root.optJSONArray("choices") ?: throw AiProviderException(0, "no-choices")
        if (choices.length() == 0) throw AiProviderException(0, "no-choices")

        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiProviderException(0, "no-message")
        val finishReason = choices.optJSONObject(0)?.optTextOrNull("finish_reason")

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

        val reasoning = message.optTextOrNull("reasoning_content")
        val serverToolCalls = message.optJSONArray("server_tool_calls")?.toString()
        val citations = parseCitations(message.optJSONArray("annotations"))
        val title = message.optTextOrNull("title")

        return AiCompletion(
            content = content,
            toolCalls = calls,
            usage = parseUsage(root),
            reasoningContent = reasoning,
            serverToolCallsJson = serverToolCalls,
            citations = citations,
            title = title,
            finishReason = finishReason,
        )
    }

    internal fun parseCitations(annotations: JSONArray?): List<AiCitation> =
        annotations?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val annotation = array.optJSONObject(index) ?: return@mapNotNull null
                val citation = annotation.optJSONObject("url_citation") ?: return@mapNotNull null
                val url = citation.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
                AiCitation(
                    url = url,
                    title = citation.optString("title").ifBlank { url },
                    startIndex = citation.optInt("start_index", 0),
                    endIndex = citation.optInt("end_index", 0),
                )
            }
        }.orEmpty()

    internal fun parseServerToolEvent(event: JSONObject): AiServerToolEvent {
        val call = event.optJSONObject("call") ?: JSONObject()
        val action = call.optJSONObject("action") ?: JSONObject()
        val sources = call.optJSONArray("sources")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val source = array.optJSONObject(index) ?: return@mapNotNull null
                val url = source.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
                AiWebSource(
                    title = source.optString("title").ifBlank { url },
                    url = url,
                    snippet = source.optString("snippet"),
                    siteName = source.optTextOrNull("site_name"),
                    siteIcon = source.optTextOrNull("site_icon"),
                )
            }
        }.orEmpty()
        val queries = action.optJSONArray("queries")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return AiServerToolEvent(
            type = event.optString("type"),
            toolName = call.optTextOrNull("type"),
            action = action.optTextOrNull("type"),
            url = action.optTextOrNull("url"),
            queries = queries,
            sources = sources,
            status = call.optTextOrNull("status"),
        )
    }

    internal fun parseUsage(root: JSONObject): AiUsage? {
        val usage = root.optJSONObject("usage") ?: return null
        val prompt = usage.optInt("prompt_tokens", -1)
        val completion = usage.optInt("completion_tokens", -1)
        if (prompt < 0 && completion < 0) return null

        var hit = usage.optInt("prompt_cache_hit_tokens", 0)
        var miss = usage.optInt("prompt_cache_miss_tokens", 0)
        if (hit == 0 && miss == 0) {
            val cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
            if (cached > 0) {
                hit = cached
                miss = (prompt - cached).coerceAtLeast(0)
            }
        }
        return AiUsage(
            promptTokens = prompt.coerceAtLeast(0),
            completionTokens = completion.coerceAtLeast(0),
            cacheHitTokens = hit,
            cacheMissTokens = miss,
        )
    }

    /** `/models` response: only the ids are useful to the picker. */
    fun parseModels(body: String): List<String> {
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw AiProviderException(0, "invalid-json") }
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
        }.distinct().sorted()
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

    private fun messageJson(message: AiMessage, provider: String): JSONObject = JSONObject().apply {
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
                // DeepSeek requires the trace to be echoed while tools are used.
                message.reasoningContent?.takeIf { it.isNotBlank() }?.let {
                    put("reasoning_content", it)
                }
                if (provider == AiPresets.DEEPSEEK) {
                    message.serverToolCallsJson?.let { put("server_tool_calls", JSONArray(it)) }
                }
            }

            AiRole.USER -> {
                if (provider == AiPresets.DEEPSEEK && message.files.isNotEmpty()) {
                    put("content", JSONArray(buildList {
                        message.content?.takeIf(String::isNotBlank)?.let {
                            add(JSONObject().put("type", "text").put("text", it))
                        }
                        message.files.forEach { file ->
                            add(
                                JSONObject().put("type", "file")
                                    .put("file_id", file.id),
                            )
                        }
                    }))
                } else {
                    put("content", message.content.orEmpty())
                }
            }

            else -> put("content", message.content.orEmpty())
        }
    }

    internal const val MAX_ERROR_CHARS = 300
}
