package com.meteor.memory.core.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.util.Base64

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

/** Minimal string storage seam, so the encryption wrapper is unit-testable. */
interface SecretStringStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** Plain preferences file; values written here are already ciphertext. */
class PrefsStringStore(
    context: Context,
    name: String = DEFAULT_PREFS_NAME,
) : SecretStringStore {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val DEFAULT_PREFS_NAME = "meteor_ai_credentials_v2"
    }
}

/**
 * Credentials encrypted with the **same Keystore AES-256-GCM key as the archive
 * itself** ([FileCrypto], default Keystore alias), so there is exactly one
 * device-bound key protecting both diary files and the API key (ADR-024).
 *
 * The preferences file only ever contains Base64 ciphertext; a failed decrypt
 * (wrong key, tampering, restored backup) yields `null`, never partial data.
 * [legacy] migrates keys written earlier by [EncryptedPrefsSecretStore].
 */
class KeyStoreSecretStore(
    private val storage: SecretStringStore,
    private val crypto: FileCrypto,
    private val legacy: SecretStore? = null,
) : SecretStore {

    override fun get(key: String): String? {
        storage.get(key)?.let { return decrypt(it) }
        val old = legacy?.get(key) ?: return null
        put(key, old)
        legacy.remove(key)
        return old
    }

    override fun put(key: String, value: String) {
        val encrypted = crypto.encrypt(value.toByteArray(StandardCharsets.UTF_8))
        storage.put(key, Base64.getEncoder().encodeToString(encrypted))
    }

    override fun remove(key: String) {
        storage.remove(key)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        String(crypto.decrypt(bytes), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Production factory: archive Keystore key + legacy migration + private prefs. */
fun androidKeystoreSecretStore(context: Context, crypto: FileCrypto): SecretStore =
    KeyStoreSecretStore(
        storage = PrefsStringStore(context),
        crypto = crypto,
        legacy = EncryptedPrefsSecretStore(context),
    )

/**
 * Older `EncryptedSharedPreferences` namespace, kept only to migrate existing
 * installations. New writes go through [KeyStoreSecretStore].
 */
class EncryptedPrefsSecretStore(context: Context) : SecretStore {

    private val prefs by lazy { createPreferences(context) }

    override fun get(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun createPreferences(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private companion object {
        const val LEGACY_PREFS_NAME = "meteor_ai_credentials"
    }
}
