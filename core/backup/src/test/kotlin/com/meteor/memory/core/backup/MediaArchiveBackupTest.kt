package com.meteor.memory.core.backup

import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaMetaInput
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.StoredMedia
import com.meteor.memory.core.storage.StorageLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class MediaArchiveBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- in-memory store ------------------------------------------------------

    /**
     * Minimal [MediaStore] that keeps plaintext in memory and honours a fixed id in
     * [restore], mirroring `EncryptedMediaStore` while staying Android-free.
     */
    private class FakeMediaStore(
        private val pinIds: Boolean = true,
    ) : MediaStore {

        val payloads = LinkedHashMap<String, ByteArray>()
        val thumbnails = HashMap<String, ByteArray>()
        val metas = LinkedHashMap<String, MediaMetaInput>()

        private var counter = 0

        override fun save(input: InputStream, meta: MediaMetaInput, thumbnailPng: ByteArray?): StoredMedia {
            val id = (counter++).toString(16).padStart(32, '0')
            return store(id, meta, input, thumbnailPng)
        }

        override fun restore(
            id: String,
            meta: MediaMetaInput,
            input: InputStream,
            thumbnailPng: ByteArray?,
        ): StoredMedia? = if (pinIds) store(id, meta, input, thumbnailPng) else null

        private fun store(
            id: String,
            meta: MediaMetaInput,
            input: InputStream,
            thumbnailPng: ByteArray?,
        ): StoredMedia {
            val bytes = input.readBytes()
            payloads[id] = bytes
            metas[id] = meta
            if (thumbnailPng != null) thumbnails[id] = thumbnailPng
            return StoredMedia(
                id = id,
                kind = meta.kind,
                fileName = meta.fileName,
                mimeType = meta.mimeType,
                personId = meta.personId,
                sizeBytes = bytes.size.toLong(),
                importedAt = Instant.parse("2026-01-01T00:00:00Z"),
                sourceDate = meta.sourceDate,
                durationMs = meta.durationMs,
                width = meta.width,
                height = meta.height,
                hasThumbnail = thumbnailPng != null,
            )
        }

        override fun find(id: String): StoredMedia? = metas[id]?.let { meta ->
            StoredMedia(
                id = id,
                kind = meta.kind,
                fileName = meta.fileName,
                mimeType = meta.mimeType,
                personId = meta.personId,
                sizeBytes = payloads.getValue(id).size.toLong(),
                importedAt = Instant.parse("2026-01-01T00:00:00Z"),
                sourceDate = meta.sourceDate,
                durationMs = meta.durationMs,
                width = meta.width,
                height = meta.height,
                hasThumbnail = thumbnails.containsKey(id),
            )
        }

        override fun list(personId: String?): List<StoredMedia> =
            metas.keys.mapNotNull { find(it) }.filter { personId == null || it.personId == personId }

        override fun load(id: String): ByteArray? = payloads[id]

        override fun copyDecrypted(id: String, output: OutputStream): Long {
            val bytes = payloads.getValue(id)
            output.write(bytes)
            return bytes.size.toLong()
        }

        override fun loadThumbnail(id: String): ByteArray? = thumbnails[id]

        override fun delete(id: String): Boolean = payloads.remove(id) != null
    }

    // --- fixtures -------------------------------------------------------------

    private val sourceDate = Instant.parse("2019-08-07T06:05:04Z")
    private val photoBytes = ByteArray(64) { (it % 251).toByte() }

    private fun seedStore(store: FakeMediaStore): String {
        val id = "aabbccddeeff00112233445566778899"
        store.payloads[id] = photoBytes
        store.metas[id] = MediaMetaInput(
            kind = MediaKind.IMAGE,
            fileName = "beach.jpg",
            mimeType = "image/jpeg",
            personId = null,
            sourceDate = sourceDate,
            width = 640,
            height = 480,
        )
        store.thumbnails[id] = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        return id
    }

    /**
     * A router-style export: every file is ciphertext, including the media
     * sidecars that this feature has to replace.
     */
    private fun routerStyleArchive(mediaId: String?): File {
        val zip = tmp.newFile("source.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("${StorageLayout.ROOT_NAME}/${StorageLayout.ARCHIVE}/${StorageLayout.SELF}/profile.md"))
            out.write("--- plaintext ---".toByteArray())
            out.closeEntry()

            out.putNextEntry(ZipEntry("${StorageLayout.ROOT_NAME}/${StorageLayout.SETTINGS}/${StorageLayout.SETTINGS_SNAPSHOT}"))
            out.write("{\"version\":1}".toByteArray())
            out.closeEntry()

            if (mediaId != null) {
                val owner = "_self"
                listOf(".data.enc", ".meta.enc", ".thumb.enc").forEach { suffix ->
                    out.putNextEntry(
                        ZipEntry(
                            "${StorageLayout.ROOT_NAME}/${StorageLayout.LEGACY}/" +
                                "${StorageLayout.MEDIA}/$owner/$mediaId$suffix",
                        ),
                    )
                    out.write(byteArrayOf(1, 2, 3, 4))
                    out.closeEntry()
                }
            }
        }
        return zip
    }

    private fun entriesOf(zip: File): Map<String, ByteArray> = ZipFile(zip).use { archive ->
        archive.entries().asSequence()
            .filterNot { it.isDirectory }
            .associate { entry -> entry.name to archive.getInputStream(entry).use { it.readBytes() } }
    }

    private fun exportedWithMedia(store: FakeMediaStore): Pair<File, Map<String, ByteArray>> {
        val archive = routerStyleArchive(seedStore(store))
        val output = ByteArrayOutputStream()
        MediaArchiveExporter(store).export(archive, output)
        val exported = tmp.newFile("exported.zip").apply { writeBytes(output.toByteArray()) }
        return exported to entriesOf(exported)
    }

    // --- export ---------------------------------------------------------------

    @Test
    fun `export replaces media ciphertext with the decrypted original and a manifest`() {
        val store = FakeMediaStore()
        val id = seedStore(store)

        val (_, entries) = exportedWithMedia(store)

        val payloadEntry = "${StorageLayout.ROOT_NAME}/${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/_self/$id.jpg"
        assertArrayEquals(photoBytes, entries.getValue(payloadEntry))
        assertFalse(entries.keys.any { it.endsWith(".data.enc") })
        assertFalse(entries.keys.any { it.endsWith(".meta.enc") })
        assertFalse(entries.keys.any { it.endsWith(".thumb.enc") })
        assertTrue(entries.containsKey("${StorageLayout.ROOT_NAME}/${MediaManifest.PATH}"))
    }

    @Test
    fun `export keeps every pre-existing entry untouched`() {
        val store = FakeMediaStore()
        seedStore(store)

        val (_, entries) = exportedWithMedia(store)

        assertArrayEquals(
            "--- plaintext ---".toByteArray(),
            entries.getValue("${StorageLayout.ROOT_NAME}/${StorageLayout.ARCHIVE}/${StorageLayout.SELF}/profile.md"),
        )
        assertEquals(
            "{\"version\":1}",
            entries.getValue(
                "${StorageLayout.ROOT_NAME}/${StorageLayout.SETTINGS}/${StorageLayout.SETTINGS_SNAPSHOT}",
            ).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `manifest records id kind owner source date dimensions and path`() {
        val store = FakeMediaStore()
        val id = seedStore(store)

        val (exported, _) = exportedWithMedia(store)
        val json = ZipFile(exported).use { archive ->
            archive.getInputStream(archive.getEntry("${StorageLayout.ROOT_NAME}/${MediaManifest.PATH}"))
                .readBytes().toString(Charsets.UTF_8)
        }
        val entry = MediaManifest.decode(json).single()

        assertEquals(id, entry.id)
        assertEquals(MediaKind.IMAGE, entry.kind)
        assertEquals("beach.jpg", entry.fileName)
        assertEquals("image/jpeg", entry.mimeType)
        assertNull(entry.personId)
        assertEquals(sourceDate, entry.sourceDate)
        assertEquals(640, entry.width)
        assertEquals(480, entry.height)
        assertNull(entry.durationMs)
        assertEquals("${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/_self/$id.jpg", entry.path)
        assertEquals("${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/_self/$id.thumb.png", entry.thumbnailPath)
    }

    @Test
    fun `thumbnail is exported as a decrypted png next to the payload`() {
        val store = FakeMediaStore()
        val id = seedStore(store)

        val (_, entries) = exportedWithMedia(store)

        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            entries.getValue("${StorageLayout.ROOT_NAME}/${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/_self/$id.thumb.png"),
        )
    }

    @Test
    fun `person owned media lands under the person directory`() {
        val store = FakeMediaStore()
        val id = "00112233445566778899aabbccddeeff"
        store.payloads[id] = photoBytes
        store.metas[id] = MediaMetaInput(
            kind = MediaKind.VIDEO,
            fileName = "clip.mov",
            mimeType = "video/quicktime",
            personId = "person_123",
            sourceDate = null,
            durationMs = 9_000L,
        )

        val archive = routerStyleArchive(null)
        val output = ByteArrayOutputStream()
        MediaArchiveExporter(store).export(archive, output)
        val exported = tmp.newFile("person.zip").apply { writeBytes(output.toByteArray()) }
        val entries = entriesOf(exported)

        assertTrue(
            entries.containsKey(
                "${StorageLayout.ROOT_NAME}/${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/person_123/$id.mov",
            ),
        )
    }

    @Test
    fun `payload extension falls back to the mime type`() {
        val store = FakeMediaStore()
        val id = "11223344556677889900aabbccddeeff"
        store.payloads[id] = photoBytes
        store.metas[id] = MediaMetaInput(
            kind = MediaKind.AUDIO,
            fileName = "recording",
            mimeType = "audio/mpeg",
            personId = null,
            sourceDate = null,
        )

        val archive = routerStyleArchive(null)
        val output = ByteArrayOutputStream()
        MediaArchiveExporter(store).export(archive, output)
        val entries = entriesOf(tmp.newFile("ext.zip").apply { writeBytes(output.toByteArray()) })

        assertTrue(
            entries.containsKey(
                "${StorageLayout.ROOT_NAME}/${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/_self/$id.mp3",
            ),
        )
    }

    // --- import ---------------------------------------------------------------

    @Test
    fun `import restores media under the original id and keeps the source date`() {
        val origin = FakeMediaStore()
        val id = seedStore(origin)
        val (exported, _) = exportedWithMedia(origin)

        val restored = FakeMediaStore()
        val importer = MediaArchiveImporter(restored)
        val manifest = importer.readManifest(exported)!!
        val stripped = tmp.newFile("stripped.zip")
        val strip = importer.stripMedia(exported, stripped, manifest)
        val stats = importer.restore(exported, strip.entries)

        assertEquals(1, stats.restored)
        assertEquals(0, stats.renamed)
        assertArrayEquals(photoBytes, restored.payloads.getValue(id))
        val meta = restored.metas.getValue(id)
        assertEquals("beach.jpg", meta.fileName)
        assertEquals(MediaKind.IMAGE, meta.kind)
        assertEquals(sourceDate, meta.sourceDate)
        assertEquals(640, meta.width)
        assertEquals(480, meta.height)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), restored.loadThumbnail(id))
    }

    @Test
    fun `strip removes media entries and keeps every record entry`() {
        val store = FakeMediaStore()
        seedStore(store)
        val (exported, _) = exportedWithMedia(store)

        val stripped = tmp.newFile("stripped-only.zip")
        val result = MediaArchiveImporter(FakeMediaStore())
            .stripMedia(exported, stripped, MediaManifest.encode(emptyList()))
        val entries = entriesOf(stripped)

        assertFalse(entries.keys.any { it.contains(StorageLayout.MEDIA) })
        assertFalse(entries.keys.any { it.contains(MediaManifest.FILE_NAME) })
        assertTrue(
            entries.containsKey(
                "${StorageLayout.ROOT_NAME}/${StorageLayout.ARCHIVE}/${StorageLayout.SELF}/profile.md",
            ),
        )
        assertEquals(2, result.fileCount)
        // Payload, thumbnail and the manifest itself.
        assertEquals(3, result.droppedMediaEntries)
    }

    @Test
    fun `import skips media that is already present`() {
        val origin = FakeMediaStore()
        seedStore(origin)
        val (exported, _) = exportedWithMedia(origin)

        val importer = MediaArchiveImporter(origin)
        val stats = importer.restore(exported, MediaManifest.decode(importer.readManifest(exported)!!))

        assertEquals(0, stats.restored)
        assertEquals(1, stats.skipped)
    }

    @Test
    fun `store without a fixed id falls back to save and reports the rename`() {
        val origin = FakeMediaStore()
        val id = seedStore(origin)
        val (exported, _) = exportedWithMedia(origin)

        val unpinned = FakeMediaStore(pinIds = false)
        val importer = MediaArchiveImporter(unpinned)
        val stats = importer.restore(exported, MediaManifest.decode(importer.readManifest(exported)!!))

        assertEquals(1, stats.restored)
        assertEquals(1, stats.renamed)
        assertFalse(unpinned.payloads.containsKey(id))
        assertArrayEquals(photoBytes, unpinned.payloads.values.single())
    }

    @Test
    fun `missing payload is skipped instead of failing the whole restore`() {
        val store = FakeMediaStore()
        val id = seedStore(store)
        val (exported, _) = exportedWithMedia(store)
        val manifest = MediaArchiveImporter(store).readManifest(exported)!!

        val target = tmp.newFile("partial.zip")
        ZipOutputStream(target.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("${StorageLayout.ROOT_NAME}/${MediaManifest.PATH}"))
            out.write(manifest.toByteArray())
            out.closeEntry()
        }

        val stats = MediaArchiveImporter(FakeMediaStore()).restore(
            target,
            MediaManifest.decode(manifest),
        )

        assertEquals(0, stats.restored)
        assertEquals(1, stats.skipped)
        assertEquals(id, MediaManifest.decode(manifest).single().id)
    }

    @Test
    fun `archive without a manifest is not treated as a media backup`() {
        val archive = routerStyleArchive(mediaId = null)

        assertNull(MediaArchiveImporter(FakeMediaStore()).readManifest(archive))
        assertFalse(MediaArchiveImporter(FakeMediaStore()).hasManifest(archive))
    }

    @Test
    fun `empty media store still produces a readable manifest`() {
        val store = FakeMediaStore()
        val output = ByteArrayOutputStream()

        MediaArchiveExporter(store).export(routerStyleArchive(null), output)
        val exported = tmp.newFile("empty-media.zip").apply { writeBytes(output.toByteArray()) }
        val manifest = MediaArchiveImporter(store).readManifest(exported)

        assertTrue(MediaManifest.decode(manifest!!).isEmpty())
    }
}
