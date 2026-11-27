package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File

class EncryptedImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Reverses the bytes behind a 29-byte envelope, mirroring the real AES-GCM
     * overhead (12-byte nonce + 16-byte tag + 1 byte plaintext). The chunk reader
     * validates encrypted sizes against that envelope, so a bare reversal is not
     * a faithful stand-in.
     */
    private class TestCrypto : FileCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size + ENVELOPE_SIZE).also { encrypted ->
                plaintext.reversedArray().copyInto(encrypted, destinationOffset = ENVELOPE_SIZE)
            }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size >= ENVELOPE_SIZE)
            return ciphertext.copyOfRange(ENVELOPE_SIZE, ciphertext.size).reversedArray()
        }

        private companion object {
            const val ENVELOPE_SIZE = 29
        }
    }

    /** Same envelope but keyed by a byte, so one key cannot read another's data. */
    private class MaskedCrypto(private val mask: Byte) : FileCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val masked = ByteArray(plaintext.size) { (plaintext[it].toInt() xor mask.toInt()).toByte() }
            return ByteArray(plaintext.size + 29).also { encrypted ->
                masked.reversedArray().copyInto(encrypted, destinationOffset = 29)
            }
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size >= 29)
            return ByteArray(ciphertext.size - 29) {
                (ciphertext[ciphertext.size - 1 - it].toInt() xor mask.toInt()).toByte()
            }
        }
    }

    private fun store(root: File = tmp.root): EncryptedImageStore =
        EncryptedImageStore(root, TestCrypto())

    private fun fileFor(token: String): File = File(tmp.root, token)

    @Test
    fun `saved bytes come back from the token`() {
        val store = store()
        val original = byteArrayOf(1, 2, 3, 4)

        val token = store.save(original, personId = null)

        assertTrue(token.endsWith(EncryptedImageStore.EXTENSION))
        assertTrue(token.startsWith("${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/"))
        assertArrayEquals(original, store.load(token))
    }

    @Test
    fun `images of one person stay in that person's folder`() {
        val personId = "0123456789abcdef0123456789abcdef"
        val token = store().save(byteArrayOf(9), personId)
        assertTrue(token.contains("/$personId/"))
    }

    @Test
    fun `delete removes the ciphertext and its token stops working`() {
        val store = store()
        val token = store.save(byteArrayOf(7, 7, 7), personId = null)
        assertEquals(listOf(token), store.list().map { it.token })

        assertTrue(store.delete(token))

        assertNull(store.load(token))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `deleting the same image twice reports the second one as missing`() {
        val store = store()
        val token = store.save(byteArrayOf(1), personId = null)

        assertTrue(store.delete(token))
        assertFalse(store.delete(token))
    }

    @Test
    fun `delete refuses an unknown token`() {
        assertFalse(store().delete("legacy/images/_self/missing.enc"))
    }

    @Test
    fun `delete refuses a path that escapes the root`() {
        val outside = File(tmp.root, "secret.enc").apply { writeBytes(byteArrayOf(5)) }

        assertFalse(store().delete("../secret.enc"))
        assertTrue(outside.exists())
    }

    @Test
    fun `tokens outside the image tree cannot be read or deleted`() {
        val record = File(tmp.root, "${StorageLayout.ARCHIVE}/${StorageLayout.SELF}/profile.md")
        record.parentFile?.mkdirs()
        record.writeBytes(byteArrayOf(1, 2, 3))
        val token = "${StorageLayout.ARCHIVE}/${StorageLayout.SELF}/profile.md"

        assertNull(store().load(token))
        assertFalse(store().delete(token))
        assertTrue(record.exists())
    }

    @Test
    fun `tokens deeper than one owner folder are rejected`() {
        val nested = File(
            tmp.root,
            "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/${EncryptedImageStore.SELF_DIR}/nested/deep.enc",
        )
        nested.parentFile?.mkdirs()
        nested.writeBytes(byteArrayOf(1))
        val token = nested.relativeTo(tmp.root).invariantSeparatorsPath

        assertNull(store().load(token))
        assertFalse(store().delete(token))
        assertTrue(nested.exists())
    }

    @Test
    fun `list only reports files at the owner level`() {
        val stray = File(
            tmp.root,
            "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/${EncryptedImageStore.SELF_DIR}/deep/deep.enc",
        )
        stray.parentFile?.mkdirs()
        stray.writeBytes(byteArrayOf(1))

        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `list is newest first`() {
        val store = store()
        val first = store.save(byteArrayOf(1), personId = null)
        Thread.sleep(20)
        val second = store.save(byteArrayOf(2), personId = null)

        assertEquals(listOf(second, first), store.list().map { it.token })
    }

    // --- unified payload format (ADR-067) -----------------------------------

    @Test
    fun `a new image is stored as a chunked frame with the media key`() {
        val keystoreCrypto = TestCrypto()
        val mediaKeyCrypto = MaskedCrypto(0x5A)
        val store = EncryptedImageStore(tmp.root, keystoreCrypto, mediaKeyCrypto)
        val payload = "image payload".toByteArray()

        val token = store.save(payload, personId = null)

        assertArrayEquals(payload, store.load(token))
        // The container is the shared chunk frame: magic `IMAG`, then version 3.
        val file = fileFor(token)
        DataInputStream(file.inputStream()).use { input ->
            assertEquals(0x494D4147, input.readInt())
            assertEquals(ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY, input.readInt())
        }

        // A store holding the wrong media key cannot read the payload, which
        // proves the frame is keyed by the media key rather than the Keystore one.
        val wrongKey = EncryptedImageStore(tmp.root, keystoreCrypto, MaskedCrypto(0x33))
        assertNull(wrongKey.load(token))
    }

    @Test
    fun `an image written before the unified format still loads`() {
        val store = store()
        val payload = "legacy whole-file image".toByteArray()
        val legacyFile = File(
            tmp.root,
            "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/${EncryptedImageStore.SELF_DIR}/legacy.enc",
        ).apply { parentFile?.mkdirs() }
        // Pre-ADR-067 shape: the whole payload behind one AES-GCM envelope.
        legacyFile.writeBytes(TestCrypto().encrypt(payload))
        val token = legacyFile.relativeTo(tmp.root).invariantSeparatorsPath

        assertArrayEquals(payload, store.load(token))

        val copied = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(token, copied))
        assertArrayEquals(payload, copied.toByteArray())
    }

    @Test
    fun `copyDecrypted streams a new frame and rejects a token that escapes`() {
        val store = store()
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val token = store.save(payload, personId = null)

        val output = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(token, output))
        assertArrayEquals(payload, output.toByteArray())

        assertThrows(IllegalStateException::class.java) {
            store.copyDecrypted("../outside.enc", ByteArrayOutputStream())
        }
    }

    @Test
    fun `restore keeps the token so markdown references survive a backup import`() {
        val store = store()
        val token = store.save(byteArrayOf(1, 2, 3), personId = null)
        val plaintext = "restored from a plaintext archive".toByteArray()

        assertEquals(token, store.restore(token, plaintext))

        assertArrayEquals(plaintext, store.load(token))
        assertEquals(1, store.list().size)
        assertArrayEquals(plaintext, store.load(token))
    }

    @Test
    fun `restore refuses a token that escapes the image tree`() {
        assertNull(store().restore("../outside.enc", byteArrayOf(1)))
    }

    @Test
    fun `an empty image round trips`() {
        val store = store()
        val token = store.save(byteArrayOf(), personId = null)

        assertArrayEquals(byteArrayOf(), store.load(token))
    }

    @Test
    fun `a large image round trips across chunk boundaries`() {
        val store = store()
        val payload = ByteArray(ChunkedEncryptedFile.LARGE_CHUNK_SIZE + 4096) { (it % 251).toByte() }
        val token = store.save(payload, personId = null)

        assertArrayEquals(payload, store.load(token))
        val copied = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(token, copied))
        assertArrayEquals(payload, copied.toByteArray())
    }
}
