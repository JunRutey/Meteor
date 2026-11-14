package com.meteor.memory.ui.ai

import com.meteor.memory.core.crypto.SecretStore
import com.meteor.memory.feature.ai.AiConfig
import com.meteor.memory.feature.ai.AiCredentialKeys
import com.meteor.memory.feature.ai.AiPresets
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/** Snapshot of what the AI needs right now: user settings + the secret key. */
data class AiReadiness(
    val settings: AppSettings,
    val config: AiConfig,
    val hasApiKey: Boolean,
)

/**
 * Bridges `:feature-settings` (non-secret preferences) and `:core-crypto`
 * (encrypted key) into the immutable [AiConfig] the AI module expects.
 */
class AiSettingsReader(
    private val repository: SettingsRepository,
    private val secrets: SecretStore,
) {

    suspend fun read(): AiReadiness {
        val settings = repository.settings.first()
        val key = secrets.get(AiCredentialKeys.API_KEY)
        return AiReadiness(
            settings = settings,
            config = AiConfig(
                // DeepSeek is the only supported provider; legacy provider and
                // base-URL preferences are intentionally ignored.
                provider = AiPresets.DEEPSEEK,
                baseUrl = "",
                model = settings.aiModel,
                apiKey = key,
                reasoning = settings.aiReasoning,
                writeEnabled = settings.aiWriteEnabled,
                writeDiary = settings.aiWriteDiary,
                writePeople = settings.aiWritePeople,
            ),
            hasApiKey = key != null,
        )
    }

    fun hasApiKey(): Boolean = secrets.get(AiCredentialKeys.API_KEY) != null

    /**
     * Plaintext key, only for the settings screen's reveal/copy actions. Callers
     * must do this off the main thread and must never log the result.
     */
    fun apiKey(): String? = secrets.get(AiCredentialKeys.API_KEY)

    fun saveApiKey(key: String) {
        if (key.isNotBlank()) secrets.put(AiCredentialKeys.API_KEY, key.trim())
    }

    fun clearApiKey() {
        secrets.remove(AiCredentialKeys.API_KEY)
    }
}
