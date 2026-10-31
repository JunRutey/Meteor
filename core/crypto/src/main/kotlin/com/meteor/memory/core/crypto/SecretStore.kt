package com.meteor.memory.core.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key/value storage for credentials that must never reach DataStore,
 * an exported backup or a log line (archive §6 credentials namespaces).
 *
 * Deliberately tiny: only short secrets live here, keyed by ASCII names.
 */
interface SecretStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/**
 * `meteor_ai_credentials` namespace, kept apart from the app-lock namespace so
 * clearing AI credentials can never drop the lock (and vice versa).
 */
class EncryptedPrefsSecretStore(context: Context) : SecretStore {

    private val prefs: SharedPreferences by lazy { createPreferences(context) }

    override fun get(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun createPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val PREFS_NAME = "meteor_ai_credentials"
    }
}
