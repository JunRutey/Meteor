package com.meteor.memory.feature.ai

/**
 * One chat-completion backend. Only OpenAI-compatible HTTP endpoints exist
 * (ADR-024); a different protocol would be a new implementation of this seam.
 */
interface AiProvider {
    suspend fun complete(
        messages: List<AiMessage>,
        tools: List<AiToolSpec> = emptyList(),
        maxTokens: Int = 1500,
    ): AiCompletion

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
