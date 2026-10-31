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
    val aiEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_BACKUP_RETENTION = 7
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
