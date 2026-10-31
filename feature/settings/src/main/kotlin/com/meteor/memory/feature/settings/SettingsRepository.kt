package com.meteor.memory.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for user preferences (archive R11 / ADR-013).
 *
 * Pure Kotlin: no Hilt annotations. `:app` owns the DI composition root and
 * wires this type, which keeps this module removable and unit-testable.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            darkMode = prefs[KEY_DARK_MODE]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
                ?: DarkMode.FOLLOW_SYSTEM,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            animationsEnabled = prefs[KEY_ANIMATIONS] ?: true,
            fontScale = prefs[KEY_FONT_SCALE]?.let { runCatching { FontScale.valueOf(it) }.getOrNull() }
                ?: FontScale.SYSTEM,
            appLockEnabled = prefs[KEY_APP_LOCK] ?: false,
            verboseLogging = prefs[KEY_VERBOSE_LOGGING] ?: false,
            autoBackupEnabled = prefs[KEY_AUTO_BACKUP] ?: true,
            backupRetention = prefs[KEY_BACKUP_RETENTION] ?: AppSettings.DEFAULT_BACKUP_RETENTION,
            aiEnabled = prefs[KEY_AI_ENABLED] ?: true,
            aiConsentAccepted = prefs[KEY_AI_CONSENT] ?: false,
            aiProvider = prefs[KEY_AI_PROVIDER] ?: AppSettings.DEFAULT_AI_PROVIDER,
            aiBaseUrl = prefs[KEY_AI_BASE_URL].orEmpty(),
            aiModel = prefs[KEY_AI_MODEL].orEmpty(),
            aiReasoning = prefs[KEY_AI_REASONING] ?: AppSettings.DEFAULT_AI_REASONING,
            aiWriteEnabled = prefs[KEY_AI_WRITE] ?: false,
        )
    }

    suspend fun setDarkMode(mode: DarkMode) = store.edit { it[KEY_DARK_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setAnimationsEnabled(enabled: Boolean) = store.edit { it[KEY_ANIMATIONS] = enabled }

    suspend fun setFontScale(scale: FontScale) = store.edit { it[KEY_FONT_SCALE] = scale.name }

    suspend fun setAppLockEnabled(enabled: Boolean) = store.edit { it[KEY_APP_LOCK] = enabled }

    suspend fun setVerboseLogging(enabled: Boolean) = store.edit { it[KEY_VERBOSE_LOGGING] = enabled }

    suspend fun setAutoBackupEnabled(enabled: Boolean) = store.edit { it[KEY_AUTO_BACKUP] = enabled }

    suspend fun setBackupRetention(count: Int) =
        store.edit { it[KEY_BACKUP_RETENTION] = count.coerceIn(1, 30) }

    suspend fun setAiEnabled(enabled: Boolean) = store.edit { it[KEY_AI_ENABLED] = enabled }

    suspend fun setAiConsentAccepted(accepted: Boolean) =
        store.edit { it[KEY_AI_CONSENT] = accepted }

    suspend fun setAiProvider(provider: String) =
        store.edit { it[KEY_AI_PROVIDER] = provider.trim() }

    suspend fun setAiBaseUrl(baseUrl: String) =
        store.edit { it[KEY_AI_BASE_URL] = baseUrl.trim() }

    suspend fun setAiModel(model: String) =
        store.edit { it[KEY_AI_MODEL] = model.trim() }

    suspend fun setAiReasoning(reasoning: String) =
        store.edit { it[KEY_AI_REASONING] = reasoning.trim() }

    suspend fun setAiWriteEnabled(enabled: Boolean) =
        store.edit { it[KEY_AI_WRITE] = enabled }

    private companion object {
        val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_ANIMATIONS = booleanPreferencesKey("animations_enabled")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        val KEY_VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging")
        val KEY_AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
        val KEY_BACKUP_RETENTION = intPreferencesKey("backup_retention")
        val KEY_AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val KEY_AI_CONSENT = booleanPreferencesKey("ai_consent_accepted")
        val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider")
        val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        val KEY_AI_REASONING = stringPreferencesKey("ai_reasoning")
        val KEY_AI_WRITE = booleanPreferencesKey("ai_write_enabled")
    }
}
