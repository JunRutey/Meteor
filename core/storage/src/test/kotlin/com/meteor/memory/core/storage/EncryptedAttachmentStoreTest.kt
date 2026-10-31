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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class EncryptedAttachmentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private fun store(): EncryptedAttachmentStore =
        EncryptedAttachmentStore(tmp.root, TestCrypto())

    @Test
    fun `attachment payload and extracted text are encrypted and round trip`() {
        val store = store()
        val personId = "0123456789abcdef0123456789abcdef"
        val payload = "private original payload".toByteArray()
        val indexedText = "private extracted text"

        val saved = store.save(
            input = ByteArrayInputStream(payload),
            fileName = "chat.txt",
            mimeType = "text/plain",
            personId = personId,
            indexedText = indexedText,
        )

        val files = allFiles(tmp.root)
        assertTrue(files.isNotEmpty())
        assertFalse(files.any { it.readBytes().contains(payload) })
        assertFalse(files.any { it.readBytes().contains(indexedText.toByteArray()) })
        assertEquals(personId, store.list(personId).single().personId)
        assertEquals("chat.txt", store.list(personId).single().fileName)
        assertEquals(indexedText, store.readIndexedText(saved.id))

        val output = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(saved.id, output))
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `global list includes attachments for all people and unassigned files`() {
        val store = store()
        val personId = "person_123"

        val personAttachment = store.save(
            ByteArrayInputStream(byteArrayOf(1)),
            "one.json",
            "application/json",
            personId,
        )
        val unassignedAttachment = store.save(
            ByteArrayInputStream(byteArrayOf(2)),
            "two.zip",
            "application/zip",
            null,
        )

        assertEquals(setOf(personAttachment.id, unassignedAttachment.id), store.list().map { it.id }.toSet())
        assertEquals(listOf(personAttachment.id), store.list(personId).map { it.id })
        assertNull(store.readIndexedText(unassignedAttachment.id))
    }

    @Test
    fun `corrupt metadata is skipped instead of failing the whole list`() {
        val store = store()
        val first = store.save(ByteArrayInputStream(byteArrayOf(1)), "one.txt", "text/plain", null)
        store.save(ByteArrayInputStream(byteArrayOf(2)), "two.txt", "text/plain", null)
        val metadataFile = allFiles(tmp.root).single { it.name == "${first.id}.meta.enc" }
        metadataFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        assertEquals(1, store.list().size)
    }

    @Test
    fun `unavailable indexed text returns null`() {
        val store = store()
        val saved = store.save(
            ByteArrayInputStream(byteArrayOf(1)),
            "indexed.txt",
            "text/plain",
            null,
            indexedText = "indexed text",
        )
        val textFile = allFiles(tmp.root).single { it.name == "${saved.id}.text.enc" }
        textFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        assertNull(store.readIndexedText(saved.id))
    }

    @Test
    fun `delete removes attachment payload metadata and indexed text`() {
        val store = store()
        val saved = store.save(
            ByteArrayInputStream(byteArrayOf(1)),
            "indexed.txt",
            "text/plain",
            "person_123",
            indexedText = "indexed text",
        )

        assertTrue(store.delete(saved.id))
        assertTrue(store.list().isEmpty())
        assertNull(store.readIndexedText(saved.id))
        assertFalse(allFiles(tmp.root).any { it.name.startsWith(saved.id) })
        assertFalse(store.delete(saved.id))
    }

    @Test
    fun `invalid person id and attachment id are rejected`() {
        val store = store()

        assertThrows(IllegalArgumentException::class.java) {
            store.save(ByteArrayInputStream(byteArrayOf(1)), "file.txt", "text/plain", "../outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.copyDecrypted("../outside", ByteArrayOutputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.delete("../outside")
        }
    }

    @Test
    fun `empty payload is supported`() {
        val store = store()
        val saved = store.save(
            ByteArrayInputStream(byteArrayOf()),
            "empty.txt",
            "text/plain",
            null,
        )

        val output = ByteArrayOutputStream()
        assertEquals(0, store.copyDecrypted(saved.id, output))
        assertArrayEquals(byteArrayOf(), output.toByteArray())
    }

    @Test
    fun `large payload round trips across authenticated chunks`() {
        val store = store()
        val payload = ByteArray(1024 * 1024 + 37) { (it % 251).toByte() }
        val saved = store.save(
            ByteArrayInputStream(payload),
            "video.mp4",
            "video/mp4",
            "person_123",
        )

        val output = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(saved.id, output))
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `restored backup payload retains chunked attachment format`() {
        val source = store()
        val personId = "person_123"
        val payload = ByteArray(1024 * 1024 + 19) { (it % 239).toByte() }
        val saved = source.save(
            ByteArrayInputStream(payload),
            "clip.mp4",
            "video/mp4",
            personId,
            "indexed video caption",
        )
        val plaintext = ByteArrayOutputStream()
        source.copyDecrypted(saved.id, plaintext)

        val destinationRoot = tmp.newFolder("restored")
        val destination = EncryptedAttachmentStore(destinationRoot, TestCrypto())
        val sourceDir = File(
            tmp.root,
            "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}/$personId",
        )
        val destinationDir = File(
            destinationRoot,
            "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}/$personId",
        ).apply { mkdirs() }
        sourceDir.listFiles()!!
            .filter { it.name.endsWith(".meta.enc") || it.name.endsWith(".text.enc") }
            .forEach { it.copyTo(File(destinationDir, it.name)) }

        destination.restorePayload(saved.id, personId, ByteArrayInputStream(plaintext.toByteArray()))

        val restoredAttachment = destination.list(personId).single()
        assertEquals(saved.id, restoredAttachment.id)
        assertEquals(saved.fileName, restoredAttachment.fileName)
        assertEquals(saved.mimeType, restoredAttachment.mimeType)
        assertEquals(saved.personId, restoredAttachment.personId)
        assertEquals(saved.sizeBytes, restoredAttachment.sizeBytes)
        assertEquals(saved.modifiedAt.toEpochMilli(), restoredAttachment.modifiedAt.toEpochMilli())
        assertEquals("indexed video caption", destination.readIndexedText(saved.id))
        val restored = ByteArrayOutputStream()
        destination.copyDecrypted(saved.id, restored)
        assertArrayEquals(payload, restored.toByteArray())
    }

    private fun allFiles(root: File): List<File> =
        root.walkTopDown().filter(File::isFile).toList()

    private fun ByteArray.contains(other: ByteArray): Boolean =
        indexOfSlice(other) >= 0

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
        if (slice.isEmpty()) return 0
        for (start in 0..size - slice.size) {
            if (slice.indices.all { this[start + it] == slice[it] }) return start
        }
        return -1
    }
}
