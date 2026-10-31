package com.meteor.memory.feature.settings

/**
 * Every user-tunable preference lives here and nowhere else (archive R11).
 *
 * Backed by DataStore. UI collects [settings]; changes are applied immediately
 * and persisted across launches.
 */
data class AppSettings(
    val darkMode: DarkMode = DarkMode.FOLLOW_SYSTEM,
    /** Material You wallpaper colours. Only takes effect on Android 12+. */
    val dynamicColor: Boolean = true,
    val animationsEnabled: Boolean = true,
    val fontScale: FontScale = FontScale.SYSTEM,
    val appLockEnabled: Boolean = false,
    /** Record every log level, not just warnings and errors. */
    val verboseLogging: Boolean = false,
    val autoBackupEnabled: Boolean = true,
    val backupRetention: Int = DEFAULT_BACKUP_RETENTION,
    /**
     * AI entry point (ADR-027). Off by default: a fresh install must not ship a
     * networked feature that is already on. Nothing leaves the device unless
     * this is on, [aiConsentAccepted] is true and a provider + API key is
     * configured. Turning it off hides the entry and stops all requests.
     */
    val aiEnabled: Boolean = false,
    /** Set only after the user accepted the "content leaves the device" dialog. */
    val aiConsentAccepted: Boolean = false,
    /** Provider preset id: `deepseek`, `openai` or `custom`. */
    val aiProvider: String = DEFAULT_AI_PROVIDER,
    /** Only used by the `custom` preset; presets carry their own endpoint. */
    val aiBaseUrl: String = "",
    /** Empty means "use the preset's default model". */
    val aiModel: String = "",
    /** Thinking effort: `auto` / `off` / `low` / `high` / `max` (DeepSeek only). */
    val aiReasoning: String = DEFAULT_AI_REASONING,
    /** When true the model may create/update diaries, moods and people. */
    val aiWriteEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_BACKUP_RETENTION = 7
        const val DEFAULT_AI_PROVIDER = "deepseek"
        const val DEFAULT_AI_REASONING = "auto"
    }
}

enum class DarkMode { FOLLOW_SYSTEM, DARK, LIGHT }

enum class FontScale { SYSTEM, SMALL, NORMAL, LARGE }

/**
 * R3 contract: when `:feature-settings` is removed, `aiEnabled` is permanently
 * false and every other module still compiles and reads existing data.
 */
class FeatureFlags(private val aiEnabled: Boolean) {
    val ai: Boolean get() = aiEnabled
}
