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

class AlbumRepositoryTest {

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

    private val personId = "person_123"

    // Lazy so the rule's TemporaryFolder exists before the stores are built.
    private val media by lazy { EncryptedMediaStore(tmp.root, TestCrypto()) }
    private val images by lazy { EncryptedImageStore(tmp.root, TestCrypto()) }
    private val repository by lazy { AlbumRepository(media, images) }

    private fun saveMedia(
        kind: MediaKind = MediaKind.IMAGE,
        owner: String? = null,
        sourceDate: Instant? = null,
        fileName: String = "photo.jpg",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ): StoredMedia = media.save(
        ByteArrayInputStream(bytes),
        MediaMetaInput(
            kind = kind,
            fileName = fileName,
            mimeType = "application/octet-stream",
            personId = owner,
            sourceDate = sourceDate,
        ),
    )

    /**
     * The image store takes its date from the file mtime on read, not from the
     * `at` argument, so the mtime is set explicitly here.
     */
    private fun saveLegacyImage(
        bytes: ByteArray = byteArrayOf(7, 8),
        owner: String? = null,
        mtime: Instant? = null,
    ): String {
        val token = images.save(bytes, owner)
        if (mtime != null) {
            val file = File(tmp.root, token)
            assertTrue("cannot set image mtime", file.setLastModified(mtime.toEpochMilli()))
        }
        return token
    }

    private fun mediaRef(saved: StoredMedia): String = "${MediaStore.MEDIA_SCHEME}${saved.id}"

    private fun imageRef(token: String): String = "${ImageStore.IMAGE_SCHEME}$token"

    @Test
    fun `new media and legacy images merge into one list`() {
        val saved = saveMedia(fileName = "new.jpg")
        val token = saveLegacyImage()

        val entries = repository.entries()

        assertEquals(2, entries.size)
        val newEntry = entries.single { it.ref == mediaRef(saved) }
        assertEquals(MediaKind.IMAGE, newEntry.kind)
        assertEquals("new.jpg", newEntry.fileName)
        assertNull(newEntry.personId)

        val legacyEntry = entries.single { it.ref == imageRef(token) }
        assertEquals(MediaKind.IMAGE, legacyEntry.kind)
        assertNull(legacyEntry.personId)
        assertNull(legacyEntry.fileName)
        assertNull(legacyEntry.mimeType)
        assertNull(legacyEntry.sourceDate)
        assertNull(legacyEntry.width)
        assertNull(legacyEntry.height)
        assertNull(legacyEntry.durationMs)
        assertFalse(legacyEntry.hasThumbnail)
    }

    @Test
    fun `legacy ref keeps the full image scheme`() {
        val token = saveLegacyImage()
        assertTrue(token.contains('/'))
        assertEquals(imageRef(token), repository.entries().single().ref)
    }

    @Test
    fun `legacy importedAt falls back to the file mtime`() {
        val mtime = Instant.parse("2021-03-04T05:06:07Z")
        saveLegacyImage(mtime = mtime)

        val legacy = repository.entries().single()

        assertEquals(mtime.toEpochMilli(), legacy.importedAt.toEpochMilli())
    }

    @Test
    fun `kinds filter keeps only the requested kinds`() {
        saveMedia(kind = MediaKind.IMAGE, fileName = "a.jpg")
        saveMedia(kind = MediaKind.VIDEO, fileName = "b.mp4")
        saveMedia(kind = MediaKind.AUDIO, fileName = "c.mp3")
        saveLegacyImage()

        assertEquals(
            setOf(MediaKind.IMAGE),
            repository.entries(kinds = setOf(MediaKind.IMAGE)).map { it.kind }.toSet(),
        )
        assertEquals(2, repository.entries(kinds = setOf(MediaKind.VIDEO, MediaKind.AUDIO)).size)
        assertEquals(4, repository.entries(kinds = null).size)
        assertTrue(repository.entries(kinds = emptySet()).isEmpty())
    }

    @Test
    fun `owner filter separates self from a person`() {
        val self = saveMedia(owner = null)
        val person = saveMedia(owner = personId)
        val legacySelf = saveLegacyImage(owner = null)
        val legacyPerson = saveLegacyImage(owner = personId)

        assertEquals(
            setOf(mediaRef(self), imageRef(legacySelf)),
            repository.entries(MediaOwner.Self).map { it.ref }.toSet(),
        )
        assertEquals(
            setOf(mediaRef(person), imageRef(legacyPerson)),
            repository.entries(MediaOwner.Person(personId)).map { it.ref }.toSet(),
        )
        assertEquals(4, repository.entries(MediaOwner.All).size)
    }

    @Test
    fun `owner person id must be legal`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.entries(MediaOwner.Person("../outside"))
        }
    }

    @Test
    fun `entries are listed newest import first`() {
        val first = saveMedia(fileName = "a.jpg")
        Thread.sleep(10)
        val second = saveMedia(fileName = "b.jpg")

        assertEquals(listOf(mediaRef(second), mediaRef(first)), repository.entries().map { it.ref })
    }

    @Test
    fun `source date sort falls back to the import date`() {
        val older = saveMedia(sourceDate = Instant.parse("2001-01-01T00:00:00Z"), fileName = "old.jpg")
        val newer = saveMedia(sourceDate = Instant.parse("2024-01-01T00:00:00Z"), fileName = "new.jpg")
        Thread.sleep(10)
        // Undated media sorts by its (newest) import timestamp.
        val undated = saveMedia(sourceDate = null, fileName = "undated.jpg")

        val ordered = repository.sorted(repository.entries(), AlbumSortMode.SOURCE_DATE).map { it.ref }

        assertEquals(listOf(mediaRef(undated), mediaRef(newer), mediaRef(older)), ordered)
    }

    @Test
    fun `legacy image mtime is the source date fallback`() {
        val ancient = saveLegacyImage(mtime = Instant.parse("1999-01-01T00:00:00Z"))
        val modern = saveMedia(sourceDate = Instant.parse("2010-01-01T00:00:00Z"), fileName = "modern.jpg")

        val ordered = repository.sorted(repository.entries(), AlbumSortMode.SOURCE_DATE).map { it.ref }

        assertEquals(listOf(mediaRef(modern), imageRef(ancient)), ordered)
    }

    @Test
    fun `import date sort ignores the source date`() {
        val recentSource = saveMedia(
            sourceDate = Instant.parse("2030-01-01T00:00:00Z"),
            fileName = "recent-source.jpg",
        )
        Thread.sleep(10)
        val oldSource = saveMedia(
            sourceDate = Instant.parse("1990-01-01T00:00:00Z"),
            fileName = "old-source.jpg",
        )

        val ordered = repository.sorted(repository.entries(), AlbumSortMode.IMPORT_DATE).map { it.ref }

        assertEquals(listOf(mediaRef(oldSource), mediaRef(recentSource)), ordered)
    }

    @Test
    fun `legacy image sorts by mtime in import date mode`() {
        saveLegacyImage(mtime = Instant.parse("2005-01-01T00:00:00Z"))
        val recent = saveMedia(fileName = "recent.jpg")

        val ordered = repository.sorted(repository.entries(), AlbumSortMode.IMPORT_DATE).map { it.ref }

        assertEquals(mediaRef(recent), ordered.first())
    }

    @Test
    fun `sorted does not mutate the input list`() {
        val old = saveMedia(sourceDate = Instant.parse("2001-01-01T00:00:00Z"))
        Thread.sleep(10)
        val recent = saveMedia(sourceDate = Instant.parse("2024-01-01T00:00:00Z"))
        val entries = repository.entries()

        repository.sorted(entries, AlbumSortMode.SOURCE_DATE)

        assertEquals(listOf(mediaRef(recent), mediaRef(old)), entries.map { it.ref })
    }

    @Test
    fun `entry load and copy work for new media`() {
        val saved = saveMedia(kind = MediaKind.VIDEO, fileName = "clip.mp4")

        assertEquals(mediaRef(saved), repository.entry(mediaRef(saved))?.ref)
        assertArrayEquals(byteArrayOf(1, 2, 3), repository.loadBytes(mediaRef(saved)))
        val output = ByteArrayOutputStream()
        assertEquals(3L, repository.copyDecrypted(mediaRef(saved), output))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test
    fun `entry load and copy work for legacy images`() {
        val token = saveLegacyImage(bytes = byteArrayOf(4, 5, 6))

        assertEquals(imageRef(token), repository.entry(imageRef(token))?.ref)
        assertArrayEquals(byteArrayOf(4, 5, 6), repository.loadBytes(imageRef(token)))
        val output = ByteArrayOutputStream()
        assertEquals(3L, repository.copyDecrypted(imageRef(token), output))
        assertArrayEquals(byteArrayOf(4, 5, 6), output.toByteArray())
    }

    @Test
    fun `unknown and malformed refs resolve to nothing`() {
        val missingId = "0123456789abcdef0123456789abcdef"
        for (bad in listOf(
            "",
            "https://example.com/a.png",
            "meteor://media/abc",
            "meteor://person/p1",
            "${MediaStore.MEDIA_SCHEME}$missingId",
        )) {
            assertNull(bad, repository.entry(bad))
            assertNull(bad, repository.loadBytes(bad))
            assertNull(bad, repository.copyDecrypted(bad, ByteArrayOutputStream()))
            assertFalse(bad, repository.delete(bad))
        }
    }

    @Test
    fun `delete removes new media and legacy images`() {
        val saved = saveMedia()
        val token = saveLegacyImage()

        assertTrue(repository.delete(mediaRef(saved)))
        assertTrue(repository.delete(imageRef(token)))

        assertTrue(repository.entries().isEmpty())
        assertTrue(images.list().isEmpty())
        assertFalse(repository.delete(mediaRef(saved)))
        assertFalse(repository.delete(imageRef(token)))
    }

    @Test
    fun `legacy tokens outside the image tree are not listed`() {
        val stray = File(
            tmp.root,
            "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/${EncryptedImageStore.SELF_DIR}/nested/deep.enc",
        )
        stray.parentFile?.mkdirs()
        stray.writeBytes(byteArrayOf(1))

        assertTrue(repository.entries().isEmpty())
    }

    @Test
    fun `thumbnails are reported for new media only`() {
        val withThumb = media.save(
            ByteArrayInputStream(byteArrayOf(1)),
            MediaMetaInput(
                kind = MediaKind.IMAGE,
                fileName = "thumb.png",
                mimeType = "image/png",
                personId = null,
                sourceDate = null,
            ),
            byteArrayOf(9, 9),
        )
        saveLegacyImage()

        val entries = repository.entries()
        assertTrue(entries.single { it.ref == mediaRef(withThumb) }.hasThumbnail)
        assertFalse(entries.single { it.ref.startsWith(ImageStore.IMAGE_SCHEME) }.hasThumbnail)
    }
}
