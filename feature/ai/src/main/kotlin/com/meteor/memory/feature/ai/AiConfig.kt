package com.meteor.memory.feature.ai

/** Keys used in the encrypted credential store (`core-crypto` SecretStore). */
object AiCredentialKeys {
    const val API_KEY = "ai_api_key"
}

/**
 * Thinking effort for providers that expose it. AUTO sends nothing at all, so a
 * chat works against any OpenAI-compatible endpoint; only DeepSeek receives the
 * `thinking` / `reasoning_effort` fields (ADR-027).
 */
enum class ReasoningMode(val wire: String, val effort: String?) {
    AUTO("auto", null),
    OFF("off", null),
    LOW("low", "low"),
    HIGH("high", "high"),
    MAX("max", "max"),
    ;

    companion object {
        fun fromWire(value: String): ReasoningMode =
            entries.firstOrNull { it.wire == value.trim().lowercase() } ?: AUTO
    }
}

/**
 * Provider presets. A base URL always includes the API version segment, so the
 * final endpoints are `{base}/chat/completions` and `{base}/models`.
 */
object AiPresets {

    const val DEEPSEEK = "deepseek"
    const val OPENAI = "openai"
    const val CUSTOM = "custom"

    val ids: List<String> = listOf(DEEPSEEK, OPENAI, CUSTOM)

    fun defaultBaseUrl(id: String): String = when (id) {
        DEEPSEEK -> "https://api.deepseek.com/v1"
        OPENAI -> "https://api.openai.com/v1"
        else -> ""
    }

    fun defaultModel(id: String): String = when (id) {
        DEEPSEEK -> "deepseek-chat"
        OPENAI -> "gpt-4o-mini"
        else -> ""
    }

    fun isKnown(id: String): Boolean = id in ids
}

/**
 * Everything one API call needs. Built by `:app` from settings + the encrypted
 * credential store, so the AI module itself never reads preferences.
 */
data class AiConfig(
    val provider: String,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String? = null,
    val reasoning: String = "auto",
    /** When true the model may use write tools (diary, mood, people). */
    val writeEnabled: Boolean = false,
) {

    private val resolved: Pair<String, String> = AiPresets.resolveProvider(provider, baseUrl, model)

    val resolvedBaseUrl: String get() = resolved.first

    val resolvedModel: String get() = resolved.second

    val endpoint: String get() = "${resolvedBaseUrl.trimEnd('/')}/chat/completions"

    val modelsEndpoint: String get() = "${resolvedBaseUrl.trimEnd('/')}/models"

    val reasoningMode: ReasoningMode get() = ReasoningMode.fromWire(reasoning)

    /** Only DeepSeek documents the thinking fields; others keep AUTO. */
    val reasoningSupported: Boolean get() = provider == AiPresets.DEEPSEEK

    fun isConfigured(): Boolean =
        !apiKey.isNullOrBlank() && resolvedModel.isNotBlank() && endpoint.isHttps()
}

/**
 * TLS in transit is part of the privacy story (ADR-027), and Android blocks
 * cleartext for this target SDK anyway — so a plain-http endpoint is treated as
 * "not configured" instead of failing later with a network error.
 */
internal fun String.isHttps(): Boolean = startsWith("https://", ignoreCase = true)

private fun AiPresets.resolveProvider(provider: String, baseUrl: String, model: String): Pair<String, String> {
    val id = if (isKnown(provider)) provider else AiPresets.DEEPSEEK
    val base = baseUrl.trim().ifEmpty { defaultBaseUrl(id) }
    val chosen = model.trim().ifEmpty { defaultModel(id) }
    return base to chosen
}
