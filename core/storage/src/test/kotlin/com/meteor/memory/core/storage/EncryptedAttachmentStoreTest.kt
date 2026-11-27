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
import java.io.DataInputStream
import java.io.DataOutputStream
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

        val chunkCount = destination.restorePayload(
            saved.id,
            personId,
            ByteArrayInputStream(plaintext.toByteArray()),
        )
        destination.rewriteChunkCount(saved.id, chunkCount)

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

    /**
     * A backup taken before ADR-067 describes 1 MiB chunks while this build
     * writes 10 MiB ones. Re-stamping the sidecar is what keeps such an
     * attachment readable instead of permanently failing the size check.
     */
    @Test
    fun `restoring a pre-unified backup keeps the attachment readable`() {
        val personId = "person_123"
        // 3 MiB + 11 bytes: four 1 MiB chunks in the old framing, one 10 MiB
        // chunk now. That difference is exactly what used to break the read.
        val payload = ByteArray(3 * 1024 * 1024 + 11) { (it % 233).toByte() }
        val keystoreCrypto = TestCrypto()

        val root = tmp.newFolder("target")
        val store = EncryptedAttachmentStore(root, keystoreCrypto, MaskedCrypto(0x5A))
        val ownerDir = File(
            root,
            "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}/$personId",
        ).apply { mkdirs() }
        val id = "0123456789abcdef0123456789abcdef"

        // Old backup shape: a v1 payload (1 MiB chunks) plus a sidecar that
        // records those four chunks.
        val legacyChunks = ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(payload),
            target = File(ownerDir, "$id${EncryptedAttachmentStore.ATTACHMENT_DATA_SUFFIX}"),
            magic = 0x4D415454,
            crypto = keystoreCrypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION,
        ).chunkCount
        assertEquals(4, legacyChunks)
        File(ownerDir, "$id.meta.enc").writeBytes(
            keystoreCrypto.encrypt(
                legacyMetadata(
                    id = id,
                    fileName = "old.bin",
                    personId = personId,
                    sizeBytes = payload.size.toLong(),
                    chunkCount = legacyChunks,
                ),
            ),
        )

        // The restore re-frames the payload but leaves the sidecar alone, so the
        // two disagree — exactly the failure the re-stamp exists to prevent.
        val chunkCount = store.restorePayload(id, personId, ByteArrayInputStream(payload))
        assertEquals(1, chunkCount)
        assertThrows(IllegalStateException::class.java) {
            store.copyDecrypted(id, ByteArrayOutputStream())
        }

        store.rewriteChunkCount(id, chunkCount)

        val output = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(id, output))
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `rewriting the chunk count of an unknown or corrupt sidecar is a no-op`() {
        val store = store()
        // Unknown id: nothing to rewrite and nothing created.
        store.rewriteChunkCount("0123456789abcdef0123456789abcdef", 3)
        assertTrue(store.list().isEmpty())

        // Corrupt sidecar: the payload must survive a failed re-stamp.
        val saved = store.save(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "a.txt", "text/plain", null)
        val metadataFile = allFiles(tmp.root).single { it.name == "${saved.id}.meta.enc" }
        metadataFile.writeBytes(byteArrayOf(9, 9, 9))

        store.rewriteChunkCount(saved.id, 3)

        assertEquals(byteArrayOf(9, 9, 9).toList(), metadataFile.readBytes().toList())
    }

    /**
     * Metadata sidecar in the pre-ADR-067 shape.
     *
     * Written by hand because the store itself can no longer produce a 1 MiB
     * chunk count; the layout is fixed by [com.meteor.memory.core.storage.EncryptedMetadataIo].
     */
    private fun legacyMetadata(
        id: String,
        fileName: String,
        personId: String?,
        sizeBytes: Long,
        chunkCount: Int,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x4D414D44) // METADATA_MAGIC
            output.writeInt(ChunkedEncryptedFile.FORMAT_VERSION)
            output.writeString(id)
            output.writeString(fileName)
            output.writeString("application/octet-stream")
            output.writeBoolean(personId != null)
            if (personId != null) output.writeString(personId)
            output.writeLong(sizeBytes)
            output.writeLong(0L)
            output.writeInt(chunkCount)
            output.writeBoolean(false)
        }
        bytes.toByteArray()
    }

    // --- unified payload format (ADR-067) -----------------------------------

    private fun dataFile(id: String, personId: String? = null): File = File(
        tmp.root,
        "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}/" +
            "${personId ?: EncryptedAttachmentStore.SELF_DIR_NAME}/" +
            "$id${EncryptedAttachmentStore.ATTACHMENT_DATA_SUFFIX}",
    )

    @Test
    fun `new attachment payloads use the media key while sidecars keep the keystore key`() {
        val keystoreCrypto = TestCrypto()
        val mediaKeyCrypto = MaskedCrypto(0x5A)
        val store = EncryptedAttachmentStore(tmp.root, keystoreCrypto, mediaKeyCrypto)
        val payload = "attachment payload".toByteArray()

        val saved = store.save(
            ByteArrayInputStream(payload),
            "chat.txt",
            "text/plain",
            "person_123",
            indexedText = "extracted text",
        )

        // The payload is a v3 chunked frame on the media key.
        DataInputStream(dataFile(saved.id, "person_123").inputStream()).use { input ->
            input.readInt() // magic
            assertEquals(ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY, input.readInt())
        }
        // A store holding the wrong media key can still list the entry (the
        // sidecar is Keystore-encrypted) but cannot read the payload.
        val wrongKey = EncryptedAttachmentStore(tmp.root, keystoreCrypto, MaskedCrypto(0x33))
        assertEquals(1, wrongKey.list("person_123").size)
        assertEquals("extracted text", wrongKey.readIndexedText(saved.id))
        assertThrows(IllegalStateException::class.java) {
            wrongKey.copyDecrypted(saved.id, ByteArrayOutputStream())
        }

        val output = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(saved.id, output))
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `an attachment written before the unified format still copies out`() {
        val keystoreCrypto = TestCrypto()
        val store = EncryptedAttachmentStore(tmp.root, keystoreCrypto, MaskedCrypto(0x5A))
        val payload = "legacy attachment".toByteArray()
        val saved = store.save(ByteArrayInputStream(payload), "old.txt", "text/plain", null)
        val file = dataFile(saved.id)

        // Rewrite the payload in the pre-ADR-067 v1 format (1 MiB, Keystore key)
        // and keep the sidecar: the reader must pick the Keystore crypto from the
        // header version rather than the store's media key.
        ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(payload),
            target = file,
            magic = DataInputStream(file.inputStream()).use { it.readInt() },
            crypto = keystoreCrypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION,
        )

        val output = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(saved.id, output))
        assertArrayEquals(payload, output.toByteArray())
        assertArrayEquals(payload, store.openDecrypted(saved.id).use { it.readBytes() })
    }

    @Test
    fun `openDecrypted streams from an offset`() {
        val store = store()
        // One full 10 MiB chunk plus a tail, so the offset crosses a boundary.
        val chunk = ChunkedEncryptedFile.LARGE_CHUNK_SIZE
        val payload = ByteArray(chunk + 1024) { (it % 251).toByte() }
        val saved = store.save(ByteArrayInputStream(payload), "big.bin", "application/octet-stream", null)

        assertArrayEquals(payload, store.openDecrypted(saved.id).use { it.readBytes() })
        assertArrayEquals(
            payload.copyOfRange(100, payload.size),
            store.openDecrypted(saved.id, 100L).use { it.readBytes() },
        )
        assertArrayEquals(
            payload.copyOfRange(chunk - 5, payload.size),
            store.openDecrypted(saved.id, (chunk - 5).toLong()).use { it.readBytes() },
        )
        assertEquals(0, store.openDecrypted(saved.id, payload.size.toLong()).use { it.readBytes() }.size)
        assertThrows(IllegalArgumentException::class.java) {
            store.openDecrypted(saved.id, payload.size + 1L)
        }
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
