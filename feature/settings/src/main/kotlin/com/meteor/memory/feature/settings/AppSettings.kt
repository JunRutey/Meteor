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
    val dynamicColor: Boolean = false,
    val animationsEnabled: Boolean = true,
    val fontScale: FontScale = FontScale.SYSTEM,
    val appLockEnabled: Boolean = false,
    /**
     * Hide the launcher entry. The app stays reachable by dialling
     * `*#*#314159#*#*` (ADR-031).
     *
     * This only records the intent: `:app` owns the `LauncherEntry` alias and
     * mirrors this flag onto the component state, so there is one knob and the
     * icon is derived from it (ADR-031).
     */
    val hideLauncherIcon: Boolean = false,
    /** Record every log level, not just warnings and errors. */
    val verboseLogging: Boolean = false,
    val autoBackupEnabled: Boolean = true,
    val backupRetention: Int = DEFAULT_BACKUP_RETENTION,
    /** Check GitHub Releases on each app process launch; off can be selected in Settings. */
    val autoCheckForUpdates: Boolean = true,
    /** Daily missing-diary reminder; opt-in on a fresh install. */
    val diaryReminderEnabled: Boolean = false,
    /** Minutes after local midnight; defaults to 20:00. */
    val diaryReminderTimeMinutes: Int = DEFAULT_DIARY_REMINDER_TIME_MINUTES,
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
    /** Sub-grant of [aiWriteEnabled]: diary entries and moods (ADR-030). */
    val aiWriteDiary: Boolean = true,
    /** Sub-grant of [aiWriteEnabled]: create/update person profiles (ADR-030). */
    val aiWritePeople: Boolean = true,
    /** Whether the first-launch introduction and birth-date setup were completed. */
    val onboardingCompleted: Boolean = false,
) {
    companion object {
        const val DEFAULT_BACKUP_RETENTION = 7
        const val DEFAULT_DIARY_REMINDER_TIME_MINUTES = 20 * 60
        const val DEFAULT_AI_PROVIDER = "deepseek"
        const val DEFAULT_AI_REASONING = "auto"
    }
}

enum class DarkMode { FOLLOW_SYSTEM, DARK, LIGHT }

enum class FontScale { SYSTEM, SMALL, NORMAL, LARGE }

/**
 * Whether the dial code is allowed to bring the app back from the background
 * (ADR-031).
 *
 * Android 10+ drops an activity start made by a background receiver unless the
 * app holds a background-activity-start exemption; the drop is silent, so the
 * only safe precondition for hiding the icon is this state. `BLOCKED` tells the
 * settings UI which system page to send the user to; `UNKNOWN` means the ROM
 * could not be queried. In both cases hiding stays disabled until a dial code
 * has actually returned to the app, which is the authoritative proof.
 */
enum class LauncherRecovery { READY, BLOCKED, UNKNOWN }

/**
 * R3 contract: when `:feature-settings` is removed, `aiEnabled` is permanently
 * false and every other module still compiles and reads existing data.
 */
class FeatureFlags(private val aiEnabled: Boolean) {
    val ai: Boolean get() = aiEnabled
}
