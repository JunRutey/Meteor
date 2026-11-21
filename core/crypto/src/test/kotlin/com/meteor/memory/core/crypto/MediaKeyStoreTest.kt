package com.meteor.memory.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Stand-in for the Keystore-wrapped [FileCrypto]: the plaintext is reversed
     * behind a 29-byte AES-GCM-sized envelope, and the envelope carries a marker
     * so damaged/tampered bytes are rejected instead of being read as a key.
     */
    private class WrappingCrypto : FileCrypto {
        val encryptCalls = AtomicInteger()
        val decryptCalls = AtomicInteger()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            encryptCalls.incrementAndGet()
            return ByteArray(plaintext.size + ENVELOPE_SIZE).also { wrapped ->
                wrapped[0] = MARKER
                plaintext.reversedArray().copyInto(wrapped, destinationOffset = ENVELOPE_SIZE)
            }
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            decryptCalls.incrementAndGet()
            require(ciphertext.size >= ENVELOPE_SIZE && ciphertext[0] == MARKER) {
                "not a wrapped media key"
            }
            return ciphertext.copyOfRange(ENVELOPE_SIZE, ciphertext.size).reversedArray()
        }

        private companion object {
            const val ENVELOPE_SIZE = 29
            const val MARKER: Byte = 0x5A
        }
    }

    private fun keyFile(): File = File(tmp.root, "media_key.bin")

    @Test
    fun `first call generates one 32 byte key and writes only the wrapped form`() {
        val wrapping = WrappingCrypto()
        val nested = File(File(tmp.root, "keys"), "media_key.bin")
        val store = MediaKeyStore(nested, wrapping)

        assertFalse(nested.exists())
        val key = store.key()

        assertEquals(MediaKeyStore.KEY_SIZE_BYTES, key.encoded.size)
        assertTrue(nested.isFile)
        // Exactly one generation, and the file holds the wrapped (reversed) form,
        // not the raw key.
        assertEquals(1, wrapping.encryptCalls.get())
        assertEquals(0, wrapping.decryptCalls.get())
        val wrapped = nested.readBytes()
        assertEquals(MediaKeyStore.KEY_SIZE_BYTES + 29, wrapped.size)
        assertArrayEquals(key.encoded.reversedArray(), wrapped.copyOfRange(29, wrapped.size))
    }

    @Test
    fun `second call and a reopened store reuse the persisted key`() {
        val wrapping = WrappingCrypto()
        val store = MediaKeyStore(keyFile(), wrapping)

        val first = store.key()
        val second = store.key()
        // A fresh instance over the same file must unwrap the same key.
        val reopened = MediaKeyStore(keyFile(), WrappingCrypto()).key()

        assertArrayEquals(first.encoded, second.encoded)
        assertArrayEquals(first.encoded, reopened.encoded)
        assertEquals(1, wrapping.encryptCalls.get())
    }

    @Test
    fun `ensureGenerated is idempotent and keeps the wrapped bytes`() {
        val wrapping = WrappingCrypto()
        val store = MediaKeyStore(keyFile(), wrapping)

        store.ensureGenerated()
        val wrapped = keyFile().readBytes()
        store.ensureGenerated()
        store.ensureGenerated()

        assertArrayEquals(wrapped, keyFile().readBytes())
        assertEquals(1, wrapping.encryptCalls.get())

        val reopened = MediaKeyStore(keyFile(), wrapping)
        reopened.ensureGenerated()
        assertArrayEquals(store.key().encoded, reopened.key().encoded)
        assertEquals(1, wrapping.encryptCalls.get())
    }

    @Test
    fun `corrupted key file throws and is never regenerated or overwritten`() {
        val store = MediaKeyStore(keyFile(), WrappingCrypto())
        store.key()
        val wrapped = keyFile().readBytes()
        // Flip the envelope marker: the wrapping crypto (like AES-GCM) rejects it.
        val corrupted = wrapped.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        keyFile().writeBytes(corrupted)

        val reopened = MediaKeyStore(keyFile(), WrappingCrypto())
        val failure = assertThrows(IllegalStateException::class.java) { reopened.key() }
        assertEquals("media key cannot be unwrapped", failure.message)
        assertArrayEquals(corrupted, keyFile().readBytes())

        // Retrying must fail the same way instead of minting a new key.
        assertThrows(IllegalStateException::class.java) { reopened.ensureGenerated() }
        assertArrayEquals(corrupted, keyFile().readBytes())
    }

    @Test
    fun `truncated key file throws and is never overwritten`() {
        val truncated = byteArrayOf(1, 2, 3, 4, 5)
        keyFile().writeBytes(truncated)

        val store = MediaKeyStore(keyFile(), WrappingCrypto())
        assertThrows(IllegalStateException::class.java) { store.key() }
        assertArrayEquals(truncated, keyFile().readBytes())
    }

    @Test
    fun `concurrent first calls generate exactly one key`() {
        val wrapping = WrappingCrypto()
        val store = MediaKeyStore(keyFile(), wrapping)
        val threads = 8
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map {
                pool.submit(
                    Callable {
                        ready.countDown()
                        start.await()
                        store.key().encoded
                    },
                )
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val keys = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, wrapping.encryptCalls.get())
            keys.forEach { assertArrayEquals(keys.first(), it) }
        } finally {
            pool.shutdownNow()
        }
    }
}
