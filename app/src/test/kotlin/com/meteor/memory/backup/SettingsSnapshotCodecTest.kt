package com.meteor.memory.backup

import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.DarkMode
import com.meteor.memory.feature.settings.FontScale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSnapshotCodecTest {

    private val settings = AppSettings(
        darkMode = DarkMode.DARK,
        dynamicColor = true,
        animationsEnabled = false,
        fontScale = FontScale.LARGE,
        hideStatusBar = true,
        verboseLogging = true,
        autoBackupEnabled = false,
        backupRetention = 12,
        autoCheckForUpdates = false,
        notificationsEnabled = false,
        peopleTags = setOf("friend", "colleague"),
        aiEnabled = true,
        aiProvider = "deepseek",
        aiModel = "deepseek-chat",
        aiReasoning = "high",
        aiWriteEnabled = true,
        aiWriteDiary = false,
        aiWritePeople = true,
        aiTtsVoice = "tide",
        aiTotalPromptTokens = 11L,
        aiTotalCompletionTokens = 22L,
        aiTotalCacheHitTokens = 33L,
        aiTotalCacheMissTokens = 44L,
        aiTotalElapsedMs = 55L,
        aiTotalRequests = 6,
        onboardingCompleted = true,
    )

    @Test
    fun `snapshot survives an encode and decode round trip`() {
        assertEquals(settings, SettingsSnapshotCodec.decode(SettingsSnapshotCodec.encode(settings)))
    }

    @Test
    fun `missing fields fall back to the app defaults`() {
        assertEquals(AppSettings(), SettingsSnapshotCodec.decode("{}"))
    }

    @Test
    fun `a snapshot without the master switch keeps notifications on`() {
        val decoded = SettingsSnapshotCodec.decode("""{"version":1,"darkMode":"DARK"}""")

        assertEquals(DarkMode.DARK, decoded.darkMode)
        assertTrue(decoded.notificationsEnabled)
    }

    @Test
    fun `unknown fields and newer versions are ignored, known fields still load`() {
        val decoded = SettingsSnapshotCodec.decode(
            """{"version":99,"futureField":true,"darkMode":"LIGHT","notificationsEnabled":false}""",
        )

        assertEquals(DarkMode.LIGHT, decoded.darkMode)
        assertFalse(decoded.notificationsEnabled)
    }

    @Test
    fun `snapshot never contains an api key`() {
        val encoded = SettingsSnapshotCodec.encode(settings)
        val root = JSONObject(encoded)

        assertFalse(encoded.contains("apiKey", ignoreCase = true))
        assertFalse(encoded.contains("api_key", ignoreCase = true))
        assertFalse(root.keys().asSequence().any { it.contains("key", ignoreCase = true) })
    }

    @Test
    fun `an api key injected into an old snapshot is dropped on re-encode`() {
        val decoded = SettingsSnapshotCodec.decode(
            """{"version":1,"apiKey":"sk-should-not-survive","darkMode":"DARK"}""",
        )

        assertEquals(DarkMode.DARK, decoded.darkMode)
        assertFalse(SettingsSnapshotCodec.encode(decoded).contains("apiKey", ignoreCase = true))
    }
}
