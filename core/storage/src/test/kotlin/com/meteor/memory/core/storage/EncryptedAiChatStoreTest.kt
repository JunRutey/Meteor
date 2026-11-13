package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.AesGcmFileCrypto
import com.meteor.memory.core.crypto.FileCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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

    @Test
    fun `version one turns migrate into an encrypted session`() {
        val root = temp.newFolder("root")
        val store = EncryptedAiChatStore(root, crypto())
        val created = listOf(turn("first question", true), turn("answer", false))
        store.save("skill__all", created)

        val conversation = store.listConversations("skill__all").single()

        assertEquals("first question", conversation.title)
        assertEquals(2, conversation.messages.size)
        assertEquals("answer", conversation.messages.last().text)
        assertFalse(
            File(root, "${StorageLayout.LEGACY}/${StorageLayout.AI_CHAT}/skill__all.bin").exists(),
        )
    }

    @Test
    fun `sessions round trip metadata and support rename and delete`() {
        val root = temp.newFolder("root")
        val store = EncryptedAiChatStore(root, crypto())
        val at = Instant.parse("2026-10-31T10:00:00Z")
        val session = StoredAiConversation(
            id = "session_1",
            scope = "skill__all",
            title = "A title",
            createdAt = at,
            updatedAt = at,
            messages = listOf(
                StoredChatMessage(
                    id = "message_1",
                    fromUser = true,
                    text = "read this",
                    at = at,
                    files = listOf(StoredChatFile("file-1", "report.pdf", "rich_layout_recognition")),
                ),
                StoredChatMessage(
                    id = "message_2",
                    fromUser = false,
                    text = "A cited answer.",
                    at = at,
                    serverToolCallsJson = """[{"id":"server-1"}]""",
                    citations = listOf(StoredChatCitation("https://example.com", "Example", 0, 8)),
                ),
            ),
        )
        store.saveConversation(session)

        val loaded = store.listConversations().single()

        assertEquals("A title", loaded.title)
        assertEquals(session.messages, loaded.messages)
        assertFalse(
            File(root, "${StorageLayout.LEGACY}/${StorageLayout.AI_CHAT}/sessions/session_1.bin")
                .readBytes()
                .toString(Charsets.ISO_8859_1)
                .contains("A cited answer"),
        )

        store.saveConversation(loaded.copy(title = "Renamed", updatedAt = at.plusSeconds(1)))
        assertEquals("Renamed", store.listConversations().single().title)
        store.deleteConversation("session_1")
        assertTrue(store.listConversations().isEmpty())
    }

    @Test
    fun `one unreadable session does not hide the healthy ones`() {
        val root = temp.newFolder("root")
        val store = EncryptedAiChatStore(root, crypto())
        val at = Instant.parse("2026-10-31T10:00:00Z")
        store.saveConversation(
            StoredAiConversation("healthy", "skill__all", "title", at, at, emptyList()),
        )
        File(
            root,
            "${StorageLayout.LEGACY}/${StorageLayout.AI_CHAT}/sessions/broken.bin",
        ).writeBytes(byteArrayOf(1, 2, 3, 4))

        val conversations = store.listConversations("skill__all")

        assertEquals(listOf("healthy"), conversations.map { it.id })
        assertTrue(
            File(root, "${StorageLayout.LEGACY}/${StorageLayout.AI_CHAT}/sessions/broken.bin").exists(),
        )
    }

    @Test
    fun `one unreadable legacy file does not block migration`() {
        val root = temp.newFolder("root")
        val store = EncryptedAiChatStore(root, crypto())
        val legacyDir = File(root, "${StorageLayout.LEGACY}/${StorageLayout.AI_CHAT}")
        legacyDir.mkdirs()
        File(legacyDir, "broken_legacy.bin").writeBytes(byteArrayOf(9, 9, 9))
        store.save("skill__all", listOf(turn("first question", true)))

        val conversations = store.listConversations("skill__all")

        assertEquals(1, conversations.size)
        assertEquals("first question", conversations.single().title)
        assertTrue(File(legacyDir, "broken_legacy.bin").exists())
    }
}
