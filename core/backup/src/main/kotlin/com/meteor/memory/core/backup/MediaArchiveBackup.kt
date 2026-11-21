package com.meteor.memory.core.backup

import com.meteor.memory.core.storage.EncryptedMediaStore
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.StoredMedia
import com.meteor.memory.core.storage.StorageLayout
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rewrites a plaintext archive so it carries media in a form a new device can read
 * (ADR-052 §5 F, ADR-057).
 *
 * The [com.meteor.memory.core.storage.StorageRouter] exports `数字遗产/媒体/` as raw
 * ciphertext — the Keystore key cannot migrate, so those bytes are useless after a
 * device change. Both classes here work on files only (no Android types), so the
 * whole format is exercised by JVM unit tests.
 *
 * Shape of an archive that contains media:
 * ```
 * 我的人生/数字遗产/媒体/<owner>/<id>.<ext>        decrypted original payload
 * 我的人生/数字遗产/媒体/<owner>/<id>.thumb.png    decrypted PNG thumbnail (optional)
 * 我的人生/设置/media-manifest.json                id/kind/owner/date/... per entry
 * 我的人生/...                                     every pre-existing entry, untouched
 * ```
 * An archive without media never reaches these classes: the caller keeps using the
 * untouched router path.
 */
class MediaArchiveExporter(private val mediaStore: MediaStore) {

    /** How many entries the rewritten archive holds and how many plaintext bytes it carries. */
    data class Stats(val fileCount: Int, val bytes: Long)

    /**
     * Copies [sourceZip] to [target] with every media ciphertext file replaced by
     * its decrypted original, then appends the thumbnails and the manifest.
     *
     * [sourceZip] is a file rather than a stream because every entry is looked up
     * by name; [target] stays a stream so the archive can go straight to the
     * destination the user picked.
     */
    fun export(sourceZip: File, target: OutputStream): Stats {
        val media = mediaStore.list().sortedBy { it.importedAt }
        val manifest = ArrayList<MediaManifestEntry>(media.size)
        var fileCount = 0
        var bytes = 0L

        ZipOutputStream(BufferedOutputStream(target)).use { zip ->
            ZipFile(sourceZip).use { archive ->
                Collections.list(archive.entries())
                    .filterNot { it.isDirectory }
                    .filterNot { isMediaOwned(relativePathOf(it.name)) }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.name))
                        val counter = CountingOutputStream(zip)
                        archive.getInputStream(entry).use { it.copyTo(counter) }
                        zip.closeEntry()
                        fileCount++
                        bytes += counter.count
                    }
            }

            media.forEach { stored ->
                val owner = stored.personId ?: EncryptedMediaStore.SELF_DIR_NAME
                val payloadPath = "$MEDIA_PREFIX$owner/${stored.id}.${extensionOf(stored)}"
                val thumbnailPath = mediaStore.loadThumbnail(stored.id)?.let { thumbnail ->
                    val path = "$MEDIA_PREFIX$owner/${stored.id}$THUMBNAIL_SUFFIX"
                    zip.putNextEntry(ZipEntry(zipNameOf(path)))
                    zip.write(thumbnail)
                    zip.closeEntry()
                    fileCount++
                    bytes += thumbnail.size
                    path
                }

                zip.putNextEntry(ZipEntry(zipNameOf(payloadPath)))
                bytes += mediaStore.copyDecrypted(stored.id, zip)
                zip.closeEntry()
                fileCount++

                manifest += manifestEntryOf(stored, payloadPath, thumbnailPath)
            }

            val manifestBytes = MediaManifest.encode(manifest).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(zipNameOf(MediaManifest.PATH)))
            zip.write(manifestBytes)
            zip.closeEntry()
            fileCount++
            bytes += manifestBytes.size
        }

        return Stats(fileCount = fileCount, bytes = bytes)
    }

    private fun manifestEntryOf(
        stored: StoredMedia,
        payloadPath: String,
        thumbnailPath: String?,
    ): MediaManifestEntry = MediaManifestEntry(
        id = stored.id,
        kind = stored.kind,
        fileName = stored.fileName,
        mimeType = stored.mimeType,
        personId = stored.personId,
        sourceDate = stored.sourceDate,
        durationMs = stored.durationMs,
        width = stored.width,
        height = stored.height,
        path = payloadPath,
        thumbnailPath = thumbnailPath,
    )

    companion object {

        private const val MEDIA_PREFIX = "${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/"
        private const val THUMBNAIL_SUFFIX = ".thumb.png"

        /** Safe, lowercase, filesystem-friendly extension; anything else becomes `bin`. */
        private val EXTENSION_PATTERN = Regex("^[a-z0-9]{1,8}$")

        /**
         * Extension of the original file name, so the exported payload opens in the
         * right viewer. MIME is the fallback when the name has no usable suffix.
         */
        internal fun extensionOf(stored: StoredMedia): String {
            val name = stored.fileName
            val dot = name.lastIndexOf('.')
            if (dot in 1 until name.length - 1) {
                val suffix = name.substring(dot + 1).lowercase()
                if (EXTENSION_PATTERN.matches(suffix)) return suffix
            }
            return MIME_EXTENSIONS[stored.mimeType.lowercase()] ?: DEFAULT_EXTENSION
        }

        internal fun zipNameOf(archiveRelativePath: String): String =
            "${StorageLayout.ROOT_NAME}/$archiveRelativePath"

        /** Strips the leading archive root, mirroring how the router names entries. */
        internal fun relativePathOf(zipName: String): String {
            val prefix = "${StorageLayout.ROOT_NAME}/"
            return if (zipName.startsWith(prefix)) zipName.removePrefix(prefix) else zipName
        }

        /**
         * True for everything this class itself writes, so the raw ciphertext is
         * dropped instead of being copied into the new archive.
         */
        internal fun isMediaOwned(archiveRelativePath: String): Boolean =
            archiveRelativePath.startsWith(MEDIA_PREFIX) || archiveRelativePath == MediaManifest.PATH

        private const val DEFAULT_EXTENSION = "bin"

        private val MIME_EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/heic" to "heic",
            "video/mp4" to "mp4",
            "video/quicktime" to "mov",
            "video/webm" to "webm",
            "video/3gpp" to "3gp",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
        )
    }
}

/**
 * Restores media from an archive whose manifest was found (ADR-057).
 *
 * Import runs in two steps so records and media never fight over the same file:
 * [stripMedia] produces a copy without any `数字遗产/媒体/` entry for the frozen
 * router to import, and [restore] streams the decrypted payloads back into the
 * [MediaStore] under the id the manifest recorded, so `meteor://media/<id>`
 * references in record bodies keep working.
 */
class MediaArchiveImporter(private val mediaStore: MediaStore?) {

    /** Manifest entries read from the archive plus the size of the stripped copy. */
    data class StripResult(
        val entries: List<MediaManifestEntry>,
        val fileCount: Int,
        val bytes: Long,
        val droppedMediaEntries: Int,
    )

    /** Restored/skipped counts; [renamed] counts fallbacks that had to mint a new id. */
    data class RestoreStats(val restored: Int, val skipped: Int, val renamed: Int = 0)

    /** Raw manifest JSON, or null when the archive predates media support (ADR-040 style no-op). */
    fun readManifest(zip: File): String? = ZipFile(zip).use { archive ->
        val entry = archive.getEntry(MediaArchiveExporter.zipNameOf(MediaManifest.PATH))
        if (entry == null) {
            null
        } else {
            archive.getInputStream(entry).use { input ->
                readBounded(input, MAX_MANIFEST_BYTES)?.toString(Charsets.UTF_8)
            }
        }
    }

    /** True when the archive carries a media manifest; the import compatibility gate. */
    fun hasManifest(zip: File): Boolean = readManifest(zip) != null

    /**
     * Writes every non-media entry of [source] to [target] and drops the media
     * entries, so the router cannot turn decrypted payloads into bogus ciphertext.
     */
    fun stripMedia(source: File, target: File, manifestJson: String): StripResult {
        val entries = runCatching { MediaManifest.decodeLenient(manifestJson) }.getOrDefault(emptyList())
        var fileCount = 0
        var bytes = 0L
        var dropped = 0

        ZipInputStream(source.inputStream().buffered()).use { zip ->
            ZipOutputStream(BufferedOutputStream(target.outputStream())).use { out ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val relative = MediaArchiveExporter.relativePathOf(entry.name)
                    if (MediaArchiveExporter.isMediaOwned(relative)) {
                        dropped++
                        zip.closeEntry()
                        continue
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    val counter = CountingOutputStream(out)
                    zip.copyTo(counter)
                    out.closeEntry()
                    fileCount++
                    bytes += counter.count
                    zip.closeEntry()
                }
            }
        }
        return StripResult(
            entries = entries,
            fileCount = fileCount,
            bytes = bytes,
            droppedMediaEntries = dropped,
        )
    }

    /**
     * Streams each manifest payload from [source] into the store.
     *
     * Entries that are already present are skipped, a missing or unreadable payload
     * is skipped too (one damaged file must not cost the whole library), and an
     * implementation that cannot honour a fixed id falls back to `save`.
     */
    fun restore(source: File, entries: List<MediaManifestEntry>): RestoreStats {
        val store = mediaStore ?: return RestoreStats(restored = 0, skipped = entries.size)
        var restored = 0
        var skipped = 0
        var renamed = 0

        ZipFile(source).use { archive ->
            val thumbnails = readThumbnails(archive, entries)
            for (entry in entries) {
                if (runCatching { store.find(entry.id) }.getOrNull() != null) {
                    skipped++
                    continue
                }
                val payload = archive.getEntry(MediaArchiveExporter.zipNameOf(entry.path))
                if (payload == null) {
                    skipped++
                    continue
                }

                val restoredMedia = runCatching {
                    archive.getInputStream(payload).use { input ->
                        store.restore(entry.id, entry.toMetaInput(), input, thumbnails[entry.id])
                    }
                }.getOrNull()

                if (restoredMedia != null) {
                    restored++
                    if (restoredMedia.id != entry.id) renamed++
                    continue
                }

                // The store cannot pin an id (default interface method): keep the
                // media, accept that the Markdown reference now points at the old id.
                val saved = runCatching {
                    archive.getInputStream(payload).use { input ->
                        store.save(input, entry.toMetaInput(), thumbnails[entry.id])
                    }
                }.getOrNull()
                if (saved == null) {
                    skipped++
                } else {
                    restored++
                    if (saved.id != entry.id) renamed++
                }
            }
        }

        return RestoreStats(restored = restored, skipped = skipped, renamed = renamed)
    }

    /**
     * Thumbnails are small (PNG capped at 512px) but a corrupt archive could claim
     * anything, so every read is bounded and the total stays under a hard limit.
     */
    private fun readThumbnails(
        archive: ZipFile,
        entries: List<MediaManifestEntry>,
    ): Map<String, ByteArray> {
        var total = 0L
        val thumbnails = HashMap<String, ByteArray>()
        for (entry in entries) {
            val path = entry.thumbnailPath ?: continue
            val thumbnailEntry = archive.getEntry(MediaArchiveExporter.zipNameOf(path)) ?: continue
            val bytes = runCatching {
                archive.getInputStream(thumbnailEntry).use { readBounded(it, MAX_THUMBNAIL_BYTES) }
            }.getOrNull() ?: continue
            if (bytes.isEmpty() || total + bytes.size > MAX_THUMBNAIL_TOTAL_BYTES) continue
            total += bytes.size
            thumbnails[entry.id] = bytes
        }
        return thumbnails
    }

    companion object {

        private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024
        private const val MAX_THUMBNAIL_TOTAL_BYTES = 64L * 1024 * 1024

        private const val READ_BUFFER_BYTES = 8 * 1024

        /**
         * Reads at most [limit] bytes; null when the stream is larger than that.
         *
         * Grows the result instead of pre-allocating [limit]: a corrupt archive could
         * otherwise make every thumbnail read reserve its maximum size.
         */
        private fun readBounded(input: InputStream, limit: Int): ByteArray? {
            val output = java.io.ByteArrayOutputStream(READ_BUFFER_BYTES)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

/** Counts what actually reaches the archive stream without buffering it. */
private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(byte: Int) {
        delegate.write(byte)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        delegate.write(bytes, offset, length)
        count += length
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.flush()
}
