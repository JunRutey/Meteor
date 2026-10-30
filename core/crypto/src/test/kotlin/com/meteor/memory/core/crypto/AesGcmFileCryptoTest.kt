package com.meteor.memory.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class AesGcmFileCryptoTest {

    private val key = SecretKeySpec(ByteArray(32) { (it * 7 + 3).toByte() }, "AES")
    private val otherKey = SecretKeySpec(ByteArray(32) { (it * 11 + 5).toByte() }, "AES")

    private fun crypto(k: SecretKeySpec = key): FileCrypto = AesGcmFileCrypto { k }

    @Test
    fun `round trips arbitrary bytes`() {
        val plaintext = "记忆档案 Meteor ✨".toByteArray()
        assertArrayEquals(plaintext, crypto().decrypt(crypto().encrypt(plaintext)))
    }

    @Test
    fun `ciphertext never contains the plaintext`() {
        val plaintext = "PAYLOAD-MARKER-1234".toByteArray()
        val encrypted = crypto().encrypt(plaintext)
        assertTrue(String(encrypted, Charsets.ISO_8859_1).contains("PAYLOAD-MARKER").not())
    }

    @Test
    fun `nonce differs on every encryption`() {
        val a = crypto().encrypt("same input".toByteArray())
        val b = crypto().encrypt("same input".toByteArray())
        assertEquals(12, a[0].toInt())
        assertNotEquals(a.contentToString(), b.contentToString())
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val encrypted = crypto().encrypt("integrity matters".toByteArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()
        assertThrows(CryptoException::class.java) { crypto().decrypt(encrypted) }
    }

    @Test
    fun `wrong key is rejected`() {
        val encrypted = crypto().encrypt("secret".toByteArray())
        assertThrows(CryptoException::class.java) { crypto(otherKey).decrypt(encrypted) }
    }

    @Test
    fun `empty plaintext round trips`() {
        val encrypted = crypto().encrypt(ByteArray(0))
        assertEquals(0, crypto().decrypt(encrypted).size)
    }

    @Test
    fun `truncated input is rejected without reading past the buffer`() {
        assertThrows(IllegalArgumentException::class.java) { crypto().decrypt(ByteArray(3)) }
    }
}
