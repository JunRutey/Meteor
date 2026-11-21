package com.meteor.memory.core.backup

import com.meteor.memory.core.storage.EncryptedImageStore
import com.meteor.memory.core.storage.MediaMetaInput
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.StoredMedia
import com.meteor.memory.core.storage.StorageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The compatibility contract of ADR-057, in the parts that do not need Android:
 * an archive written before media existed must import exactly as it used to, and
 * the manifest must be the single gate for the new path.
 */
class MediaBackupCompatibilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Older exports: records, images and attachments — but no media, no manifest. */
    private fun legacyArchive(): File {
        val zip = tmp.newFile("legacy.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            listOf(
                "${StorageLayout.ROOT_NAME}/${StorageLayout.TIMELINE}/${StorageLayout.MOOD}/2025-03-14.md",
                "${StorageLayout.ROOT_NAME}/${StorageLayout.LEGACY}/" +
                    "${StorageLayout.IMAGES}/${EncryptedImageStore.SELF_DIR}/photo.enc",
                "${StorageLayout.ROOT_NAME}/${StorageLayout.LEGACY}/" +
                    "${StorageLayout.ATTACHMENTS}/person_123/chat.txt.data.enc",
            ).forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write("ciphertext".toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `legacy archive has no manifest so import keeps the old path`() {
        val importer = MediaArchiveImporter(NoopMediaStore)

        assertNull(importer.readManifest(legacyArchive()))
    }

    @Test
    fun `legacy archive entries are all left untouched by the media gate`() {
        val entries = ZipFile(legacyArchive()).use { archive ->
            archive.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        }

        assertTrue(entries.none { MediaArchiveExporter.isMediaOwned(MediaArchiveExporter.relativePathOf(it)) })
        assertEquals(3, entries.size)
    }

    @Test
    fun `null media store means media never participates`() {
        val importer = MediaArchiveImporter(null)

        val stats = importer.restore(legacyArchive(), emptyList())

        assertEquals(0, stats.restored)
        assertEquals(0, stats.skipped)
    }

    @Test
    fun `manifest gate only matches the exact manifest entry name`() {
        val zip = tmp.newFile("near-miss.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            // Same file name, wrong directory: a nested copy must not open the gate.
            out.putNextEntry(ZipEntry("${StorageLayout.ROOT_NAME}/stray/${MediaManifest.FILE_NAME}"))
            out.write("{}".toByteArray())
            out.closeEntry()
        }

        assertFalse(MediaArchiveImporter(null).hasManifest(zip))
    }

    @Test
    fun `manifest path sits beside the settings snapshot`() {
        assertEquals("${StorageLayout.SETTINGS}/${MediaManifest.FILE_NAME}", MediaManifest.PATH)
        assertEquals(
            "${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/",
            MediaManifest.MEDIA_PREFIX,
        )
    }

    private companion object {

        /** Nothing is stored and nothing is written: the strictest "no media" case. */
        object NoopMediaStore : MediaStore {
            override fun save(
                input: InputStream,
                meta: MediaMetaInput,
                thumbnailPng: ByteArray?,
            ): StoredMedia = error("save must not be called")

            override fun find(id: String): StoredMedia? = null

            override fun list(personId: String?): List<StoredMedia> = emptyList()

            override fun load(id: String): ByteArray? = null

            override fun copyDecrypted(id: String, output: OutputStream): Long = 0L

            override fun loadThumbnail(id: String): ByteArray? = null

            override fun delete(id: String): Boolean = false
        }
    }
}
