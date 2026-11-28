package com.meteor.memory.backup

import com.meteor.memory.feature.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON payload of the encrypted settings snapshot (ADR-040).
 *
 * Only non-secret, non-device-bound preferences are written. API keys live in
 * the SecretStore and never reach DataStore, the app lock and screenshot
 * protection are device security choices, and the AI consent dialog is shown
 * again on the device that imports the backup. Unknown fields are ignored so a
 * backup written by a newer build still restores everything it understands.
 */
object SettingsSnapshotCodec {
    const val VERSION = 1

    fun encode(settings: AppSettings): String = JSONObject().apply {
        put(KEY_VERSION, VERSION)
        put(KEY_DARK_MODE, settings.darkMode.name)
        put(KEY_DYNAMIC_COLOR, settings.dynamicColor)
        put(KEY_ANIMATIONS, settings.animationsEnabled)
        put(KEY_FONT_SCALE, settings.fontScale.name)
        put(KEY_HIDE_STATUS_BAR, settings.hideStatusBar)
        put(KEY_VERBOSE_LOGGING, settings.verboseLogging)
        put(KEY_AUTO_BACKUP, settings.autoBackupEnabled)
        put(KEY_BACKUP_RETENTION, settings.backupRetention)
        put(KEY_AUTO_CHECK_FOR_UPDATES, settings.autoCheckForUpdates)
        put(KEY_NOTIFICATIONS_ENABLED, settings.notificationsEnabled)
        put(KEY_DIARY_REMINDER_ENABLED, settings.diaryReminderEnabled)
        put(KEY_QUESTION_REMINDER_ENABLED, settings.questionReminderEnabled)
        put(KEY_UPDATE_NOTIFICATION_ENABLED, settings.updateNotificationEnabled)
        put(KEY_PEOPLE_TAGS, JSONArray(settings.peopleTags.sorted()))
        put(KEY_AI_ENABLED, settings.aiEnabled)
        put(KEY_AI_PROVIDER, settings.aiProvider)
        put(KEY_AI_MODEL, settings.aiModel)
        put(KEY_AI_REASONING, settings.aiReasoning)
        put(KEY_AI_WRITE, settings.aiWriteEnabled)
        put(KEY_AI_WRITE_DIARY, settings.aiWriteDiary)
        put(KEY_AI_WRITE_PEOPLE, settings.aiWritePeople)
        put(KEY_AI_TTS_VOICE, settings.aiTtsVoice)
        put(KEY_AI_TOTAL_PROMPT_TOKENS, settings.aiTotalPromptTokens)
        put(KEY_AI_TOTAL_COMPLETION_TOKENS, settings.aiTotalCompletionTokens)
        put(KEY_AI_TOTAL_CACHE_HIT_TOKENS, settings.aiTotalCacheHitTokens)
        put(KEY_AI_TOTAL_CACHE_MISS_TOKENS, settings.aiTotalCacheMissTokens)
        put(KEY_AI_TOTAL_ELAPSED_MS, settings.aiTotalElapsedMs)
        put(KEY_AI_TOTAL_REQUESTS, settings.aiTotalRequests)
        put(KEY_ONBOARDING_COMPLETED, settings.onboardingCompleted)
        put(KEY_ALBUM_SORT_MODE, settings.albumSortMode)
        put(KEY_MEDIA_PICKER_MODE, settings.mediaPickerMode.name)
    }.toString()

    fun decode(json: String): AppSettings {
        val root = JSONObject(json)
        val defaults = AppSettings()
        return AppSettings(
            darkMode = enumOrDefault(root.optString(KEY_DARK_MODE), defaults.darkMode),
            dynamicColor = root.optBoolean(KEY_DYNAMIC_COLOR, defaults.dynamicColor),
            animationsEnabled = root.optBoolean(KEY_ANIMATIONS, defaults.animationsEnabled),
            fontScale = enumOrDefault(root.optString(KEY_FONT_SCALE), defaults.fontScale),
            hideStatusBar = root.optBoolean(KEY_HIDE_STATUS_BAR, defaults.hideStatusBar),
            verboseLogging = root.optBoolean(KEY_VERBOSE_LOGGING, defaults.verboseLogging),
            autoBackupEnabled = root.optBoolean(KEY_AUTO_BACKUP, defaults.autoBackupEnabled),
            backupRetention = root.optInt(KEY_BACKUP_RETENTION, defaults.backupRetention),
            autoCheckForUpdates = root.optBoolean(KEY_AUTO_CHECK_FOR_UPDATES, defaults.autoCheckForUpdates),
            notificationsEnabled = root.optBoolean(KEY_NOTIFICATIONS_ENABLED, defaults.notificationsEnabled),
            diaryReminderEnabled =
                root.optBoolean(KEY_DIARY_REMINDER_ENABLED, defaults.diaryReminderEnabled),
            questionReminderEnabled =
                root.optBoolean(KEY_QUESTION_REMINDER_ENABLED, defaults.questionReminderEnabled),
            updateNotificationEnabled =
                root.optBoolean(KEY_UPDATE_NOTIFICATION_ENABLED, defaults.updateNotificationEnabled),
            peopleTags = root.optJSONArray(KEY_PEOPLE_TAGS).toStringSet(),
            aiEnabled = root.optBoolean(KEY_AI_ENABLED, defaults.aiEnabled),
            aiProvider = root.optString(KEY_AI_PROVIDER, defaults.aiProvider),
            aiModel = root.optString(KEY_AI_MODEL, defaults.aiModel),
            aiReasoning = root.optString(KEY_AI_REASONING, defaults.aiReasoning),
            aiWriteEnabled = root.optBoolean(KEY_AI_WRITE, defaults.aiWriteEnabled),
            aiWriteDiary = root.optBoolean(KEY_AI_WRITE_DIARY, defaults.aiWriteDiary),
            aiWritePeople = root.optBoolean(KEY_AI_WRITE_PEOPLE, defaults.aiWritePeople),
            aiTtsVoice = root.optString(KEY_AI_TTS_VOICE, defaults.aiTtsVoice),
            aiTotalPromptTokens = root.optLong(KEY_AI_TOTAL_PROMPT_TOKENS, defaults.aiTotalPromptTokens),
            aiTotalCompletionTokens =
                root.optLong(KEY_AI_TOTAL_COMPLETION_TOKENS, defaults.aiTotalCompletionTokens),
            aiTotalCacheHitTokens =
                root.optLong(KEY_AI_TOTAL_CACHE_HIT_TOKENS, defaults.aiTotalCacheHitTokens),
            aiTotalCacheMissTokens =
                root.optLong(KEY_AI_TOTAL_CACHE_MISS_TOKENS, defaults.aiTotalCacheMissTokens),
            aiTotalElapsedMs = root.optLong(KEY_AI_TOTAL_ELAPSED_MS, defaults.aiTotalElapsedMs),
            aiTotalRequests = root.optInt(KEY_AI_TOTAL_REQUESTS, defaults.aiTotalRequests),
            onboardingCompleted = root.optBoolean(KEY_ONBOARDING_COMPLETED, defaults.onboardingCompleted),
            albumSortMode = root.optString(KEY_ALBUM_SORT_MODE, defaults.albumSortMode),
            mediaPickerMode = enumOrDefault(
                root.optString(KEY_MEDIA_PICKER_MODE, defaults.mediaPickerMode.name),
                defaults.mediaPickerMode,
            ),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val values = LinkedHashSet<String>(length())
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) values += value
        }
        return values
    }

    private const val KEY_VERSION = "version"
    private const val KEY_DARK_MODE = "darkMode"
    private const val KEY_DYNAMIC_COLOR = "dynamicColor"
    private const val KEY_ANIMATIONS = "animationsEnabled"
    private const val KEY_FONT_SCALE = "fontScale"
    private const val KEY_HIDE_STATUS_BAR = "hideStatusBar"
    private const val KEY_VERBOSE_LOGGING = "verboseLogging"
    private const val KEY_AUTO_BACKUP = "autoBackupEnabled"
    private const val KEY_BACKUP_RETENTION = "backupRetention"
    private const val KEY_AUTO_CHECK_FOR_UPDATES = "autoCheckForUpdates"
    private const val KEY_NOTIFICATIONS_ENABLED = "notificationsEnabled"
    private const val KEY_DIARY_REMINDER_ENABLED = "diaryReminderEnabled"
    private const val KEY_QUESTION_REMINDER_ENABLED = "questionReminderEnabled"
    private const val KEY_UPDATE_NOTIFICATION_ENABLED = "updateNotificationEnabled"
    private const val KEY_PEOPLE_TAGS = "peopleTags"
    private const val KEY_AI_ENABLED = "aiEnabled"
    private const val KEY_AI_PROVIDER = "aiProvider"
    private const val KEY_AI_MODEL = "aiModel"
    private const val KEY_AI_REASONING = "aiReasoning"
    private const val KEY_AI_WRITE = "aiWriteEnabled"
    private const val KEY_AI_WRITE_DIARY = "aiWriteDiary"
    private const val KEY_AI_WRITE_PEOPLE = "aiWritePeople"
    private const val KEY_AI_TTS_VOICE = "aiTtsVoice"
    private const val KEY_AI_TOTAL_PROMPT_TOKENS = "aiTotalPromptTokens"
    private const val KEY_AI_TOTAL_COMPLETION_TOKENS = "aiTotalCompletionTokens"
    private const val KEY_AI_TOTAL_CACHE_HIT_TOKENS = "aiTotalCacheHitTokens"
    private const val KEY_AI_TOTAL_CACHE_MISS_TOKENS = "aiTotalCacheMissTokens"
    private const val KEY_AI_TOTAL_ELAPSED_MS = "aiTotalElapsedMs"
    private const val KEY_AI_TOTAL_REQUESTS = "aiTotalRequests"
    private const val KEY_ONBOARDING_COMPLETED = "onboardingCompleted"
    private const val KEY_ALBUM_SORT_MODE = "albumSortMode"
    private const val KEY_MEDIA_PICKER_MODE = "mediaPickerMode"
}
