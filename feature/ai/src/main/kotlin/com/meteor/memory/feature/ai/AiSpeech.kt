package com.meteor.memory.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Read-aloud voice ids exposed by DeepSeek's TTS tool. */
enum class TtsVoice(val wire: String) {
    STELLA("stella"),
    TIDE("tide"),
    ;

    companion object {
        fun fromWire(value: String): TtsVoice =
            entries.firstOrNull { it.wire == value.trim().lowercase() } ?: STELLA
    }
}

/**
 * DeepSeek speech synthesis (TTS). The response is raw PCM, 24 kHz mono
 * 16-bit little-endian; playback wraps it in a WAV container.
 *
 * Endpoint: POST {origin}/v1/tools/tts
 * Body: { text, voice_id, format: "pcm", filter_markdown: true }
 */
object AiSpeech {

    const val SAMPLE_RATE = 24_000

    suspend fun synthesize(
        config: AiConfig,
        text: String,
        voice: TtsVoice,
    ): ByteArray {
        if (!config.isConfigured()) throw AiProviderException(0, "not-configured")
        if (config.provider != AiPresets.DEEPSEEK || !config.officialDeepSeekEndpoint) {
            throw AiProviderException(0, "not-configured")
        }
        if (text.isBlank()) throw AiProviderException(400, "tts-unavailable")
        val key = config.apiKey?.takeIf(String::isNotBlank)
            ?: throw AiProviderException(0, "missing-api-key")
        val payload = JSONObject().apply {
            put("text", text)
            put("voice_id", voice.wire)
            put("format", "pcm")
            put("filter_markdown", true)
        }.toString()

        return withContext(Dispatchers.IO) {
            val connection = (URL(config.speechEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                connection.outputStream.use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = connection.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    throw AiProviderException(code, AiProtocol.errorMessage(error))
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                connection.inputStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_AUDIO_BYTES) {
                            throw AiProviderException(0, "tts-too-large")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                if (output.size() == 0) throw AiProviderException(0, "tts-empty")
                output.toByteArray()
            } finally {
                connection.disconnect()
            }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 180_000
    private const val MAX_AUDIO_BYTES = 64 * 1024 * 1024
}
