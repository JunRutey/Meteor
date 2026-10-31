package com.meteor.memory.ui.ai

import com.meteor.memory.feature.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides whether AI may run *right now*: enabled by the user, consent given,
 * key stored, and endpoint/model valid (ADR-024). Every AI screen asks this.
 */
class AiGate(private val reader: AiSettingsReader) {

    suspend fun blocked(settings: AppSettings): AiBlocked? {
        if (!settings.aiEnabled) return AiBlocked.DISABLED
        if (!settings.aiConsentAccepted) return AiBlocked.NEEDS_CONSENT
        val readiness = withContext(Dispatchers.IO) { runCatching { reader.read() }.getOrNull() }
            ?: return AiBlocked.NEEDS_CONFIG
        return when {
            !readiness.hasApiKey -> AiBlocked.NEEDS_KEY
            !readiness.config.isConfigured() -> AiBlocked.NEEDS_CONFIG
            else -> null
        }
    }
}
