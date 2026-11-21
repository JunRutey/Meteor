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
import java.time.Instant

class EncryptedMediaStoreTest {

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

    private fun store(root: File = tmp.root): EncryptedMediaStore =
        EncryptedMediaStore(root, TestCrypto())

    private fun meta(
        kind: MediaKind = MediaKind.IMAGE,
        personId: String? = null,
        fileName: String = "photo.jpg",
        mimeType: String = "image/jpeg",
        sourceDate: Instant? = Instant.parse("2020-05-01T10:00:00Z"),
        durationMs: Long? = null,
        width: Int? = 64,
        height: Int? = 48,
    ) = MediaMetaInput(
        kind = kind,
        fileName = fileName,
        mimeType = mimeType,
        personId = personId,
        sourceDate = sourceDate,
        durationMs = durationMs,
        width = width,
        height = height,
    )

    @Test
    fun `saved media comes back with its metadata`() {
        val store = store()
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        val saved = store.save(ByteArrayInputStream(payload), meta())

        assertTrue(saved.id.matches(Regex("^[a-f0-9]{32}$")))
        assertEquals(MediaKind.IMAGE, saved.kind)
        assertEquals("photo.jpg", saved.fileName)
        assertEquals("image/jpeg", saved.mimeType)
        assertNull(saved.personId)
        assertEquals(payload.size.toLong(), saved.sizeBytes)
        assertEquals(Instant.parse("2020-05-01T10:00:00Z"), saved.sourceDate)
        assertEquals(64, saved.width)
        assertEquals(48, saved.height)
        assertFalse(saved.hasThumbnail)

        val found = requireNotNull(store.find(saved.id))
        assertEquals(saved.id, found.id)
        assertEquals(saved.kind, found.kind)
        assertEquals(saved.fileName, found.fileName)
        assertEquals(saved.mimeType, found.mimeType)
        assertEquals(saved.sizeBytes, found.sizeBytes)
        assertEquals(saved.importedAt, found.importedAt)
        assertEquals(saved.sourceDate, found.sourceDate)
        assertEquals(saved.durationMs, found.durationMs)
        assertEquals(saved.width, found.width)
        assertEquals(saved.height, found.height)
        assertArrayEquals(payload, requireNotNull(store.load(saved.id)))
    }

    @Test
    fun `metadata round trips for every nullable field`() {
        val store = store()
        val personId = "person_123"
        val saved = store.save(
            ByteArrayInputStream(byteArrayOf(9)),
            MediaMetaInput(
                kind = MediaKind.VIDEO,
                fileName = "clip.mp4",
                mimeType = "video/mp4",
                personId = personId,
                sourceDate = null,
                durationMs = 12_345L,
                width = null,
                height = null,
            ),
        )

        assertEquals(listOf(saved), store.list(personId))
        val found = requireNotNull(store.find(saved.id))
        assertNull(found.sourceDate)
        assertEquals(12_345L, found.durationMs)
        assertNull(found.width)
        assertNull(found.height)
    }

    @Test
    fun `media is stored under the media tree with encrypted sidecars`() {
        val store = store()
        val payload = "private payload".toByteArray()
        val saved = store.save(ByteArrayInputStream(payload), meta(personId = "person_123"))

        val ownerDir = File(
            tmp.root,
            "${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/person_123",
        )
        assertEquals(
            setOf(
                "${saved.id}${EncryptedMediaStore.DATA_SUFFIX}",
                "${saved.id}${EncryptedMediaStore.METADATA_SUFFIX}",
            ),
            ownerDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
        // Nothing sensitive may be readable on disk.
        val files = tmp.root.walkTopDown().filter(File::isFile).toList()
        assertFalse(files.any { it.readBytes().containsSlice(payload) })
        assertFalse(files.any { it.readBytes().containsSlice("photo.jpg".toByteArray()) })
    }

    @Test
    fun `unassigned media lives in the self directory`() {
        val saved = store().save(ByteArrayInputStream(byteArrayOf(1)), meta())
        val selfDir = File(tmp.root, "${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/_self")
        assertTrue(File(selfDir, "${saved.id}${EncryptedMediaStore.DATA_SUFFIX}").isFile)
    }

    @Test
    fun `thumbnail is optional and round trips`() {
        val store = store()
        val thumbnail = byteArrayOf(11, 22, 33)

        val withThumb = store.save(ByteArrayInputStream(byteArrayOf(1)), meta(), thumbnail)
        assertTrue(withThumb.hasThumbnail)
        assertTrue(store.find(withThumb.id)!!.hasThumbnail)
        assertArrayEquals(thumbnail, requireNotNull(store.loadThumbnail(withThumb.id)))

        val withoutThumb = store.save(ByteArrayInputStream(byteArrayOf(2)), meta())
        assertFalse(withoutThumb.hasThumbnail)
        assertNull(store.loadThumbnail(withoutThumb.id))
    }

    @Test
    fun `list is newest first and honours the owner filter`() {
        val store = store()
        val selfMedia = store.save(ByteArrayInputStream(byteArrayOf(1)), meta())
        Thread.sleep(10)
        val personMedia = store.save(ByteArrayInputStream(byteArrayOf(2)), meta(personId = "person_123"))

        assertEquals(listOf(personMedia.id, selfMedia.id), store.list().map { it.id })
        assertEquals(listOf(personMedia.id), store.list("person_123").map { it.id })
        assertTrue(store.list("person_456").isEmpty())
    }

    @Test
    fun `delete removes payload metadata and thumbnail`() {
        val store = store()
        val saved = store.save(ByteArrayInputStream(byteArrayOf(1)), meta(personId = "person_123"), byteArrayOf(7))

        assertTrue(store.delete(saved.id))

        assertNull(store.find(saved.id))
        assertNull(store.load(saved.id))
        assertNull(store.loadThumbnail(saved.id))
        assertTrue(store.list().isEmpty())
        assertFalse(tmp.root.walkTopDown().any { it.isFile && it.name.startsWith(saved.id) })
        assertFalse(store.delete(saved.id))
    }

    @Test
    fun `large payload round trips across chunks`() {
        val store = store()
        val payload = ByteArray(ChunkedEncryptedFile.CHUNK_SIZE + 37) { (it % 251).toByte() }
        val saved = store.save(
            ByteArrayInputStream(payload),
            meta(kind = MediaKind.VIDEO, fileName = "clip.mp4", mimeType = "video/mp4"),
        )

        assertEquals(payload.size.toLong(), saved.sizeBytes)
        val copy = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), store.copyDecrypted(saved.id, copy))
        assertArrayEquals(payload, copy.toByteArray())
        assertArrayEquals(payload, requireNotNull(store.load(saved.id)))
    }

    @Test
    fun `empty payload is supported`() {
        val store = store()
        val saved = store.save(ByteArrayInputStream(byteArrayOf()), meta())

        assertEquals(0L, saved.sizeBytes)
        val copy = ByteArrayOutputStream()
        assertEquals(0L, store.copyDecrypted(saved.id, copy))
        assertArrayEquals(byteArrayOf(), requireNotNull(store.load(saved.id)))
    }

    @Test
    fun `corrupt metadata is skipped instead of failing the whole list`() {
        val store = store()
        val first = store.save(ByteArrayInputStream(byteArrayOf(1)), meta(fileName = "one.jpg"))
        store.save(ByteArrayInputStream(byteArrayOf(2)), meta(fileName = "two.jpg"))
        val metadata = tmp.root.walkTopDown()
            .single { it.isFile && it.name == "${first.id}${EncryptedMediaStore.METADATA_SUFFIX}" }
        metadata.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(1, store.list().size)
        assertNull(store.find(first.id))
    }

    @Test
    fun `illegal ids and person ids are rejected`() {
        val store = store()
        for (evil in listOf("../outside", "ABCDEF0123456789ABCDEF0123456789", "abc", "")) {
            assertThrows(IllegalArgumentException::class.java) { store.find(evil) }
            assertThrows(IllegalArgumentException::class.java) { store.load(evil) }
            assertThrows(IllegalArgumentException::class.java) { store.copyDecrypted(evil, ByteArrayOutputStream()) }
            assertThrows(IllegalArgumentException::class.java) { store.loadThumbnail(evil) }
            assertThrows(IllegalArgumentException::class.java) { store.delete(evil) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(ByteArrayInputStream(byteArrayOf(1)), meta(personId = "../outside"))
        }
        assertThrows(IllegalArgumentException::class.java) { store.list("../outside") }
    }

    @Test
    fun `media outside the media tree cannot be read or deleted`() {
        val record = File(tmp.root, "${StorageLayout.ARCHIVE}/${StorageLayout.SELF}/profile.md")
        record.parentFile?.mkdirs()
        record.writeBytes(byteArrayOf(1, 2, 3))
        val id = "0123456789abcdef0123456789abcdef"

        assertNull(store().find(id))
        assertNull(store().load(id))
        assertNull(store().loadThumbnail(id))
        assertFalse(store().delete(id))
        assertTrue(record.exists())
    }

    @Test
    fun `restore keeps the caller supplied id and source date`() {
        val store = store()
        val id = "0123456789abcdef0123456789abcdef"
        val payload = ByteArray(ChunkedEncryptedFile.CHUNK_SIZE + 5) { it.toByte() }

        val restored = requireNotNull(
            store.restore(id, meta(personId = "person_123"), ByteArrayInputStream(payload), byteArrayOf(4)),
        )

        assertEquals(id, restored.id)
        assertEquals(Instant.parse("2020-05-01T10:00:00Z"), restored.sourceDate)
        assertEquals(payload.size.toLong(), restored.sizeBytes)
        assertEquals(listOf(restored), store.list("person_123"))
        assertArrayEquals(payload, requireNotNull(store.load(id)))
        assertArrayEquals(byteArrayOf(4), requireNotNull(store.loadThumbnail(id)))
    }

    @Test
    fun `restore refuses an id that is not 32 lowercase hex`() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) {
            store.restore("../escape", meta(), ByteArrayInputStream(byteArrayOf(1)))
        }
    }

    @Test
    fun `restore refuses to overwrite an existing entry`() {
        val store = store()
        val saved = store.save(ByteArrayInputStream(byteArrayOf(1)), meta())

        assertThrows(IllegalStateException::class.java) {
            store.restore(saved.id, meta(), ByteArrayInputStream(byteArrayOf(2)))
        }
        assertArrayEquals(byteArrayOf(1), requireNotNull(store.load(saved.id)))
    }

    @Test
    fun `restored media survives a store re-read from disk`() {
        val id = "0123456789abcdef0123456789abcdef"
        val first = store()
        first.save(ByteArrayInputStream(byteArrayOf(1)), meta(personId = "person_123"))

        val second = store()
        val restored = requireNotNull(
            second.restore(id, meta(personId = "person_123"), ByteArrayInputStream(byteArrayOf(3))),
        )
        assertEquals(id, restored.id)
        assertArrayEquals(byteArrayOf(3), requireNotNull(store().load(id)))
        assertEquals(2, store().list("person_123").size)
    }

    /** Plain `contains` does not exist for [ByteArray], so the search is explicit. */
    private fun ByteArray.containsSlice(slice: ByteArray): Boolean {
        if (slice.isEmpty()) return true
        for (start in 0..size - slice.size) {
            if (slice.indices.all { this[start + it] == slice[it] }) return true
        }
        return false
    }
}
