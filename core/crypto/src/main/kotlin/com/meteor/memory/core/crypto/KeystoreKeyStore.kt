package com.meteor.memory.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Owns the single file-encryption key inside Android Keystore.
 *
 * The key is generated on first use, is AES-256, and can never be exported —
 * even this process can only ask the Keystore to encrypt/decrypt with it.
 * Losing the keystore (app uninstall, device change) therefore loses the key,
 * which is why backups are exported as plaintext (archive §6, ADR-004).
 */
class KeystoreKeyStore {

    /**
     * Cached key handles (ADR-058).
     *
     * Opening the Android Keystore is a Binder round-trip to the keystore
     * daemon, not a local lookup. Chunked media encryption asks for the key once
     * per 1 MiB block, so a 600 MB video used to pay 600 Keystore round-trips
     * before the first byte was even encrypted. A `SecretKey` handle is
     * reusable across `Cipher` instances, so it is cached here and only dropped
     * when the key is deleted.
     */
    private val cachedKeys = ConcurrentHashMap<String, SecretKey>()

    fun secretKey(alias: String = DEFAULT_ALIAS): SecretKey {
        cachedKeys[alias]?.let { return it }
        synchronized(this) {
            cachedKeys[alias]?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generate(alias)
            cachedKeys[alias] = key
            return key
        }
    }

    fun deleteKey(alias: String = DEFAULT_ALIAS) {
        cachedKeys.remove(alias)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun generate(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "meteor_file_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}
