package com.meteor.memory.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigTest {

    @Test
    fun `deepseek preset resolves endpoint and default model`() {
        val config = AiConfig(provider = AiPresets.DEEPSEEK, apiKey = "k")

        assertEquals("https://api.deepseek.com/v1", config.resolvedBaseUrl)
        assertEquals("deepseek-chat", config.resolvedModel)
        assertEquals("https://api.deepseek.com/v1/chat/completions", config.endpoint)
        assertTrue(config.isConfigured())
    }

    @Test
    fun `openai preset uses its own defaults`() {
        val config = AiConfig(provider = AiPresets.OPENAI, apiKey = "k")

        assertTrue(config.endpoint.startsWith("https://api.openai.com/v1/"))
        assertEquals("gpt-4o-mini", config.resolvedModel)
    }

    @Test
    fun `an unknown provider falls back to deepseek`() {
        val config = AiConfig(provider = "nope", apiKey = "k")

        assertEquals("https://api.deepseek.com/v1", config.resolvedBaseUrl)
    }

    @Test
    fun `a custom provider must supply a base url`() {
        assertFalse(AiConfig(provider = AiPresets.CUSTOM, apiKey = "k").isConfigured())
        assertTrue(
            AiConfig(
                provider = AiPresets.CUSTOM,
                baseUrl = "https://example.com/v1",
                model = "m",
                apiKey = "k",
            ).isConfigured(),
        )
    }

    @Test
    fun `plain http is refused`() {
        assertFalse(
            AiConfig(
                provider = AiPresets.CUSTOM,
                baseUrl = "http://example.com/v1",
                model = "m",
                apiKey = "k",
            ).isConfigured(),
        )
        assertFalse(
            AiConfig(
                provider = AiPresets.CUSTOM,
                baseUrl = "http://127.0.0.1:8080/v1",
                model = "m",
                apiKey = "k",
            ).isConfigured(),
        )
    }

    @Test
    fun `a missing key means not configured`() {
        assertFalse(AiConfig(provider = AiPresets.DEEPSEEK).isConfigured())
    }
}
