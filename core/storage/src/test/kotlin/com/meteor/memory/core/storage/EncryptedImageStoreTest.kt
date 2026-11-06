package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncryptedImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Reversing the bytes is enough to prove load goes through [FileCrypto]. */
    private class ReversingCrypto : FileCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.reversedArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.reversedArray()
    }

    private fun store(root: File = tmp.root): EncryptedImageStore =
        EncryptedImageStore(root, ReversingCrypto())

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
}
