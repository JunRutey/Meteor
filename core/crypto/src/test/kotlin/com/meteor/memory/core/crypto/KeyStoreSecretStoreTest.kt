package com.meteor.memory.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class KeyStoreSecretStoreTest {

    private class MapStore : SecretStringStore {
        val values = mutableMapOf<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private fun crypto(): FileCrypto {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return AesGcmFileCrypto { SecretKeySpec(key, "AES") }
    }

    @Test
    fun `the stored value is ciphertext, not the api key`() {
        val storage = MapStore()
        val store = KeyStoreSecretStore(storage, crypto())

        store.put("k", "sk-secret-value")

        val raw = storage.values.getValue("k")
        assertTrue(raw, raw.isNotBlank())
        assertTrue(raw, !raw.contains("sk-secret-value"))
        assertEquals("sk-secret-value", store.get("k"))
    }

    @Test
    fun `a wrong key cannot decrypt`() {
        val storage = MapStore()
        KeyStoreSecretStore(storage, crypto()).put("k", "sk-secret-value")

        assertNull(KeyStoreSecretStore(storage, crypto()).get("k"))
    }

    @Test
    fun `legacy plaintext store is migrated once`() {
        val storage = MapStore()
        val legacy = object : SecretStore {
            val values = mutableMapOf("k" to "old-key")
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String) {
                values[key] = value
            }

            override fun remove(key: String) {
                values.remove(key)
            }
        }
        val store = KeyStoreSecretStore(storage, crypto(), legacy)

        assertEquals("old-key", store.get("k"))
        assertNull(legacy.get("k"))
        // Now it is ciphertext in the new store and decrypts again.
        assertTrue(storage.values.getValue("k").isNotBlank())
        assertEquals("old-key", store.get("k"))
    }

    @Test
    fun `remove clears the value`() {
        val storage = MapStore()
        val store = KeyStoreSecretStore(storage, crypto())
        store.put("k", "v")
        store.remove("k")
        assertNull(store.get("k"))
    }
}
