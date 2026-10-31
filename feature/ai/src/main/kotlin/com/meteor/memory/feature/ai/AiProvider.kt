package com.meteor.memory.feature.ai

import org.json.JSONObject

/** Live deltas from a streaming completion; content is never logged. */
interface AiStreamCallback {
    fun onContent(delta: String) {}

    fun onReasoning(delta: String) {}
}

/**
 * One chat-completion backend. Only OpenAI-compatible HTTP endpoints exist
 * (ADR-027); a different protocol would be a new implementation of this seam.
 */
interface AiProvider {
    suspend fun complete(
        messages: List<AiMessage>,
        tools: List<AiToolSpec> = emptyList(),
        maxTokens: Int = 1500,
    ): AiCompletion

    /** Streaming variant; the default replays a non-streaming completion once. */
    suspend fun completeStream(
        messages: List<AiMessage>,
        tools: List<AiToolSpec> = emptyList(),
        maxTokens: Int = 1500,
        callback: AiStreamCallback,
    ): AiCompletion {
        val completion = complete(messages, tools, maxTokens)
        completion.content?.takeIf { it.isNotBlank() }?.let(callback::onContent)
        completion.reasoningContent?.takeIf { it.isNotBlank() }?.let(callback::onReasoning)
        return completion
    }

    /** Cheap `/models` call behind the "test connection" button. */
    suspend fun probe(): ProbeResult

    /** Model ids the provider currently offers; used by the settings picker. */
    suspend fun listModels(): List<String> = emptyList()
}

/**
 * OpenAI-compatible provider. Base URL, model and key all come from [config];
 * nothing is hardcoded beyond the two endpoint paths.
 */
class OpenAiCompatibleProvider(
    private val config: AiConfig,
    private val transport: HttpTransport = UrlConnectionTransport(),
) : AiProvider {

    override suspend fun complete(
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
        maxTokens: Int,
    ): AiCompletion {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException(0, "missing-api-key")
        if (!config.isConfigured()) throw AiProviderException(0, "not-configured")

        val payload = AiProtocol.requestBody(
            model = config.resolvedModel,
            messages = messages,
            tools = tools,
            maxTokens = maxTokens,
            reasoning = config.reasoningMode,
            provider = config.provider,
        )
        val response = transport.post(
            url = config.endpoint,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = payload,
        )
        if (response.code !in 200..299) {
            throw AiProviderException(response.code, AiProtocol.errorMessage(response.body))
        }
        return AiProtocol.parseCompletion(response.body)
    }

    override suspend fun completeStream(
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
        maxTokens: Int,
        callback: AiStreamCallback,
    ): AiCompletion {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException(0, "missing-api-key")
        if (!config.isConfigured()) throw AiProviderException(0, "not-configured")

        val payload = AiProtocol.requestBody(
            model = config.resolvedModel,
            messages = messages,
            tools = tools,
            maxTokens = maxTokens,
            reasoning = config.reasoningMode,
            provider = config.provider,
            stream = true,
        )

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, StreamingCall>()
        var usage: AiUsage? = null

        val response = transport.postStream(
            url = config.endpoint,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Accept" to "text/event-stream",
            ),
            body = payload,
        ) { line ->
            val data = line.trim().removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return@postStream
            val root = runCatching { JSONObject(data) }.getOrNull() ?: return@postStream

            AiProtocol.parseUsage(root)?.let { chunk -> usage = usage?.merge(chunk) ?: chunk }

            val delta = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?: return@postStream

            delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let {
                reasoning.append(it)
                callback.onReasoning(it)
            }
            delta.optString("content").takeIf { it.isNotEmpty() }?.let {
                content.append(it)
                callback.onContent(it)
            }

            val toolDeltas = delta.optJSONArray("tool_calls") ?: return@postStream
            for (index in 0 until toolDeltas.length()) {
                val item = toolDeltas.optJSONObject(index) ?: continue
                val slot = item.optInt("index", index)
                val builder = calls.getOrPut(slot) { StreamingCall() }
                item.optString("id").takeIf { it.isNotBlank() }?.let { builder.id = it }
                val function = item.optJSONObject("function") ?: continue
                function.optString("name").takeIf { it.isNotBlank() }?.let { builder.name = it }
                function.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                    builder.arguments.append(it)
                }
            }
        }
        if (response.code !in 200..299) {
            throw AiProviderException(response.code, AiProtocol.errorMessage(response.body))
        }

        return AiCompletion(
            content = content.toString().ifBlank { null },
            toolCalls = calls.entries.mapNotNull { (slot, builder) ->
                builder.name?.let { name ->
                    AiToolCall(
                        id = builder.id.ifBlank { "call_$slot" },
                        name = name,
                        arguments = builder.arguments.toString().ifBlank { "{}" },
                    )
                }
            },
            usage = usage,
            reasoningContent = reasoning.toString().ifBlank { null },
        )
    }

    override suspend fun probe(): ProbeResult {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: return ProbeResult(ok = false, code = 0)
        if (!config.isConfigured()) return ProbeResult(ok = false, code = 0)

        return runCatching {
            val response = transport.get(
                url = config.modelsEndpoint,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )
            ProbeResult(ok = response.code in 200..299, code = response.code)
        }.getOrDefault(ProbeResult(ok = false, code = 0))
    }

    override suspend fun listModels(): List<String> {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException(0, "missing-api-key")
        if (!config.isConfigured()) throw AiProviderException(0, "not-configured")

        val response = transport.get(
            url = config.modelsEndpoint,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )
        if (response.code !in 200..299) {
            throw AiProviderException(response.code, AiProtocol.errorMessage(response.body))
        }
        return AiProtocol.parseModels(response.body)
    }
}

/** Fetches available model ids for the settings picker. */
object AiModels {
    suspend fun fetch(config: AiConfig): List<String> = OpenAiCompatibleProvider(config).listModels()
}

/** Accumulates one `tool_calls[index]` delta stream. */
private class StreamingCall {
    var id: String = ""
    var name: String? = null
    val arguments = StringBuilder()
}
