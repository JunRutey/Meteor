package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.AesGcmFileCrypto
import com.meteor.memory.core.crypto.FileCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class EncryptedAiChatStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun crypto(): FileCrypto {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return AesGcmFileCrypto { SecretKeySpec(key, "AES") }
    }

    private fun turn(text: String, fromUser: Boolean) =
        StoredChatTurn(fromUser = fromUser, text = text, at = Instant.parse("2026-10-31T10:00:00Z"))

    @Test
    fun `turns round-trip and the file is ciphertext`() {
        val root = temp.newFolder("root")
        val store = EncryptedAiChatStore(root, crypto())

        store.save("skill__all", listOf(turn("你好", true), turn("你好呀", false)))

        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        val raw = files.first().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("你好"))

        val loaded = store.load("skill__all")
        assertEquals(2, loaded.size)
        assertEquals("你好", loaded[0].text)
        assertTrue(loaded[0].fromUser)
        assertEquals("你好呀", loaded[1].text)
        assertFalse(loaded[1].fromUser)
    }

    @Test
    fun `scopes do not leak into each other`() {
        val root = temp.newFolder("root")
        val store = EncryptedAiChatStore(root, crypto())
        store.save("a__all", listOf(turn("A", true)))
        store.save("b__all", listOf(turn("B", true)))

        assertEquals("A", store.load("a__all").single().text)
        assertEquals("B", store.load("b__all").single().text)
    }

    @Test
    fun `clear removes only one scope`() {
        val root = temp.newFolder("root")
        val store = EncryptedAiChatStore(root, crypto())
        store.save("a", listOf(turn("A", true)))
        store.save("b", listOf(turn("B", true)))

        store.clear("a")

        assertTrue(store.load("a").isEmpty())
        assertEquals(1, store.load("b").size)
    }

    @Test
    fun `a wrong key reads as empty rather than crashing`() {
        val root = temp.newFolder("root")
        EncryptedAiChatStore(root, crypto()).save("a", listOf(turn("secret", true)))

        assertTrue(EncryptedAiChatStore(root, crypto()).load("a").isEmpty())
    }
}
