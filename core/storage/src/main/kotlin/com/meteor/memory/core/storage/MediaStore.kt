package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/** What a stored media file depicts; the player and the album grid key off this. */
enum class MediaKind { IMAGE, VIDEO, AUDIO }

/**
 * Everything the caller knows about a media file at import time (ADR-052 §3).
 *
 * The display metadata never touches the filesystem in the clear: it lives in an
 * encrypted `.meta.enc` sidecar beside the payload.
 */
data class MediaMetaInput(
    val kind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val personId: String?,
    val sourceDate: Instant?,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** One encrypted media file after it was written, as read back from its sidecar. */
data class StoredMedia(
    val id: String,
    val kind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val personId: String?,
    val sizeBytes: Long,
    val importedAt: Instant,
    val sourceDate: Instant?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val hasThumbnail: Boolean,
)

/**
 * Encrypted storage for images, videos and audio files (ADR-052 §3).
 *
 * Ownership, kind, the original file name and the source date are all encrypted;
 * only the opaque owner directory and the random media id are visible on disk.
 */
interface MediaStore {

    /** Encrypts [input] into a new media entry, optionally with a PNG [thumbnailPng]. */
    fun save(input: InputStream, meta: MediaMetaInput, thumbnailPng: ByteArray? = null): StoredMedia

    fun find(id: String): StoredMedia?

    /** Lists media, newest first by `importedAt`; [personId] null means every owner. */
    fun list(personId: String? = null): List<StoredMedia>

    /** Decrypts a whole media file. Only for small payloads — use [copyDecrypted] otherwise. */
    fun load(id: String): ByteArray?

    /** Streams authenticated plaintext to [output] and returns its byte count. */
    fun copyDecrypted(id: String, output: OutputStream): Long

    /**
     * Opens a decrypted stream starting at [offsetBytes] (a plaintext offset),
     * so playback can begin without materialising the whole file first (ADR-058).
     *
     * Added under R2 as an additive, defaulted method: an implementation that
     * cannot stream keeps compiling, and callers fall back to [copyDecrypted].
     */
    fun openDecrypted(id: String, offsetBytes: Long = 0L): InputStream =
        throw UnsupportedOperationException("streaming decryption is not supported")

    /** Decrypted PNG thumbnail, or null when the entry has none. */
    fun loadThumbnail(id: String): ByteArray?

    /** Removes payload, metadata and thumbnail; true when at least one file was deleted. */
    fun delete(id: String): Boolean

    /**
     * Writes [input] under a caller-chosen [id], keeping existing Markdown
     * references intact across a backup restore (ADR-052 §5 F).
     *
     * `save` always mints a fresh id, so restoring through it would silently
     * break every `meteor://media/<id>` link in the archive. Implementations that
     * cannot honour a fixed id keep the default and return null; callers must
     * then fall back to [save] and rewrite references.
     *
     * Added under R2 as an additive, defaulted method: the rest of the contract
     * is unchanged and existing implementations keep compiling.
     */
    fun restore(
        id: String,
        meta: MediaMetaInput,
        input: InputStream,
        thumbnailPng: ByteArray? = null,
    ): StoredMedia? = null

    companion object {
        const val MEDIA_SCHEME = "meteor://media/"

        /** Media ids are a UUID without dashes: 32 lowercase hex characters. */
        val MEDIA_ID_PATTERN = Regex("^[a-f0-9]{32}$")

        fun markdownFor(id: String, alt: String = ""): String = "![$alt]($MEDIA_SCHEME$id)"
    }
}

/**
 * Chunked-encrypted media store under `数字遗产/媒体/<owner>/`.
 *
 * Layout mirrors [EncryptedAttachmentStore] — `数字遗产/媒体/<owner>/<id>.data.enc`
 * (chunk frames), `<id>.meta.enc` (encrypted metadata), `<id>.thumb.enc` (optional
 * encrypted PNG) — so a large video is streamed chunk by chunk and never held in
 * memory, while the attachment store's own format stays untouched.
 *
 * Media payloads are written as format version 3 with [mediaKeyCrypto], a random
 * data key wrapped by the Keystore key (ADR-063), so chunks can be encrypted in
 * parallel inside this process. The metadata sidecar and the thumbnail keep using
 * [crypto]. Reads pick the key from the payload's version, so v1/v2 media written
 * before ADR-063 stays readable.
 */
class EncryptedMediaStore(
    rootDir: File,
    private val crypto: FileCrypto,
    /**
     * Key for the media payload itself (ADR-063). Defaults to [crypto] so existing
     * callers and tests keep the pre-ADR-063 single-key behaviour.
     */
    private val mediaKeyCrypto: FileCrypto = crypto,
) : MediaStore {

    private val mediaDir = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.MEDIA}")

    /**
     * Reads pick the payload key from the format version: v3 files were written
     * with [mediaKeyCrypto], older v1/v2 files with [crypto].
     */
    private val cryptoFor: (Int) -> FileCrypto = { version ->
        if (version >= ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY) mediaKeyCrypto else crypto
    }

    override fun save(input: InputStream, meta: MediaMetaInput, thumbnailPng: ByteArray?): StoredMedia =
        writeEntry(id = null, meta = meta, input = input, thumbnailPng = thumbnailPng)

    override fun restore(
        id: String,
        meta: MediaMetaInput,
        input: InputStream,
        thumbnailPng: ByteArray?,
    ): StoredMedia = writeEntry(id = id, meta = meta, input = input, thumbnailPng = thumbnailPng)

    /**
     * Single write path for [save] and [restore]: all three sidecars are written
     * to `.part` files first and renamed only once each one is complete, so an
     * interrupted import never leaves a half-readable entry behind.
     */
    private fun writeEntry(
        id: String?,
        meta: MediaMetaInput,
        input: InputStream,
        thumbnailPng: ByteArray?,
    ): StoredMedia {
        require(meta.fileName.isNotBlank()) { "file name is required" }
        require(meta.personId == null || StorageLayout.PERSON_ID_PATTERN.matches(meta.personId)) {
            "illegal person_id: ${meta.personId}"
        }
        if (id != null) requireMediaId(id)

        val ownerDir = ownerDirectory(meta.personId)
        check(ownerDir.isDirectory || ownerDir.mkdirs()) { "cannot create media directory" }
        val mediaId = id ?: availableMediaId(ownerDir)

        val dataFile = File(ownerDir, "$mediaId$DATA_SUFFIX")
        val metadataFile = File(ownerDir, "$mediaId$METADATA_SUFFIX")
        val thumbnailFile = File(ownerDir, "$mediaId$THUMBNAIL_SUFFIX")
        val dataTemp = File(ownerDir, "$mediaId$DATA_TEMP_SUFFIX")
        val metadataTemp = File(ownerDir, "$mediaId$METADATA_TEMP_SUFFIX")
        val thumbnailTemp = File(ownerDir, "$mediaId$THUMBNAIL_TEMP_SUFFIX")
        var dataFinalized = false
        var metadataFinalized = false
        var thumbnailFinalized = false

        try {
            check(!dataFile.exists() && !metadataFile.exists() && !thumbnailFile.exists()) {
                "media id collision"
            }
            val payload = ChunkedEncryptedFile.write(
                input = input,
                target = dataTemp,
                magic = DATA_MAGIC,
                crypto = mediaKeyCrypto,
                // Media uses the media data key with 10 MiB chunks and two
                // encryption workers (ADR-062, ADR-063); attachments keep the
                // 1 MiB format and the Keystore key.
                version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
                parallelism = PARALLELISM,
            )
            if (thumbnailPng != null) thumbnailTemp.writeBytes(crypto.encrypt(thumbnailPng))

            val media = StoredMedia(
                id = mediaId,
                kind = meta.kind,
                fileName = meta.fileName,
                mimeType = meta.mimeType.ifBlank { MIME_OCTET_STREAM },
                personId = meta.personId,
                sizeBytes = payload.sizeBytes,
                // Truncated to the precision the sidecar stores, so a value read
                // back from disk equals the one returned here.
                importedAt = Instant.now().toStoragePrecision(),
                sourceDate = meta.sourceDate?.toStoragePrecision(),
                durationMs = meta.durationMs,
                width = meta.width,
                height = meta.height,
                hasThumbnail = thumbnailPng != null,
            )
            metadataTemp.writeBytes(crypto.encrypt(encodeMetadata(media, payload.chunkCount)))

            check(dataTemp.renameTo(dataFile)) { "cannot finalize encrypted media" }
            dataFinalized = true
            if (thumbnailPng != null) {
                check(thumbnailTemp.renameTo(thumbnailFile)) { "cannot finalize encrypted thumbnail" }
                thumbnailFinalized = true
            }
            check(metadataTemp.renameTo(metadataFile)) { "cannot finalize media metadata" }
            metadataFinalized = true
            return media
        } catch (failure: Exception) {
            dataTemp.delete()
            metadataTemp.delete()
            thumbnailTemp.delete()
            if (dataFinalized) dataFile.delete()
            if (metadataFinalized) metadataFile.delete()
            if (thumbnailFinalized) thumbnailFile.delete()
            throw failure
        }
    }

    override fun find(id: String): StoredMedia? {
        requireMediaId(id)
        val metadataFile = findMetadata(id) ?: return null
        return runCatching { decodeMetadata(metadataFile).media }.getOrNull()
    }

    override fun list(personId: String?): List<StoredMedia> {
        require(personId == null || StorageLayout.PERSON_ID_PATTERN.matches(personId)) {
            "illegal person_id: $personId"
        }
        val owners = if (personId == null) ownerDirectories() else listOf(ownerDirectory(personId))
        return owners.asSequence()
            .filter(File::isDirectory)
            .flatMap { ownerDir ->
                ownerDir.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.name.matches(METADATA_FILE_PATTERN) }
                    // One unreadable sidecar must not hide the rest of the album.
                    ?.mapNotNull { metadataFile -> runCatching { decodeMetadata(metadataFile).media }.getOrNull() }
                    .orEmpty()
            }
            .sortedByDescending { it.importedAt }
            .toList()
    }

    override fun load(id: String): ByteArray? {
        requireMediaId(id)
        val metadataFile = findMetadata(id) ?: return null
        val metadata = runCatching { decodeMetadata(metadataFile) }.getOrNull() ?: return null
        val dataFile = File(metadataFile.parentFile, "$id$DATA_SUFFIX")
        if (!dataFile.isFile) return null
        return runCatching {
            ByteArrayOutputStream().use { output ->
                copyChunks(dataFile, metadata, output)
                output.toByteArray()
            }
        }.getOrNull()
    }

    override fun copyDecrypted(id: String, output: OutputStream): Long {
        requireMediaId(id)
        val metadataFile = findMetadata(id) ?: error("media not found")
        val metadata = decodeMetadata(metadataFile)
        val dataFile = File(metadataFile.parentFile, "$id$DATA_SUFFIX")
        check(dataFile.isFile) { "encrypted media is missing" }
        return copyChunks(dataFile, metadata, output)
    }

    override fun openDecrypted(id: String, offsetBytes: Long): InputStream {
        requireMediaId(id)
        val metadataFile = findMetadata(id) ?: error("media not found")
        val metadata = decodeMetadata(metadataFile)
        require(offsetBytes in 0..metadata.media.sizeBytes) { "offset outside media" }
        val dataFile = File(metadataFile.parentFile, "$id$DATA_SUFFIX")
        check(dataFile.isFile) { "encrypted media is missing" }
        return ChunkedEncryptedFile.openDecrypted(dataFile, DATA_MAGIC, cryptoFor, offsetBytes)
    }

    override fun loadThumbnail(id: String): ByteArray? {
        requireMediaId(id)
        val metadataFile = findMetadata(id) ?: return null
        val metadata = runCatching { decodeMetadata(metadataFile) }.getOrNull() ?: return null
        if (!metadata.media.hasThumbnail) return null
        val thumbnailFile = File(metadataFile.parentFile, "$id$THUMBNAIL_SUFFIX")
        if (!thumbnailFile.isFile) return null
        return runCatching { crypto.decrypt(thumbnailFile.readBytes()) }.getOrNull()
    }

    override fun delete(id: String): Boolean {
        requireMediaId(id)
        var removed = false
        ownerDirectories().forEach { dir ->
            listOf(DATA_SUFFIX, METADATA_SUFFIX, THUMBNAIL_SUFFIX).forEach { suffix ->
                if (File(dir, "$id$suffix").delete()) removed = true
            }
        }
        return removed
    }

    private fun copyChunks(dataFile: File, metadata: MediaMetadata, output: OutputStream): Long {
        val stats = ChunkedEncryptedFile.copyDecrypted(
            source = dataFile,
            magic = DATA_MAGIC,
            cryptoFor = cryptoFor,
            output = output,
            parallelism = PARALLELISM,
        )
        check(stats.chunkCount == metadata.chunkCount && stats.sizeBytes == metadata.media.sizeBytes) {
            "media size does not match metadata"
        }
        return stats.sizeBytes
    }

    private fun encodeMetadata(media: StoredMedia, chunkCount: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeMetadataHeader(METADATA_MAGIC, FORMAT_VERSION)
                output.writeString(media.id)
                output.writeString(media.kind.name)
                output.writeString(media.fileName)
                output.writeString(media.mimeType)
                output.writeNullableString(media.personId)
                output.writeLong(media.sizeBytes)
                output.writeLong(media.importedAt.toEpochMilli())
                output.writeNullableLong(media.sourceDate?.toEpochMilli())
                output.writeNullableLong(media.durationMs)
                output.writeNullableInt(media.width)
                output.writeNullableInt(media.height)
                output.writeBoolean(media.hasThumbnail)
                output.writeInt(chunkCount)
            }
            bytes.toByteArray()
        }

    private fun decodeMetadata(file: File): MediaMetadata =
        try {
            val id = file.name.removeSuffix(METADATA_SUFFIX)
            check(MediaStore.MEDIA_ID_PATTERN.matches(id)) { "invalid media id" }
            val decoded = DataInputStream(ByteArrayInputStream(crypto.decrypt(file.readBytes()))).use { input ->
                input.readMetadataHeader(METADATA_MAGIC, FORMAT_VERSION)
                val mediaId = input.readString()
                check(mediaId == id) { "invalid media id" }
                val kind = runCatching { MediaKind.valueOf(input.readString()) }.getOrNull()
                    ?: throw IllegalArgumentException("invalid media kind")
                val fileName = input.readString()
                val mimeType = input.readString()
                val personId = input.readNullableString()
                check(personId == null || StorageLayout.PERSON_ID_PATTERN.matches(personId)) {
                    "invalid media owner"
                }
                val sizeBytes = input.readLong()
                val importedAt = Instant.ofEpochMilli(input.readLong())
                val sourceDate = input.readNullableLong()?.let { Instant.ofEpochMilli(it) }
                val durationMs = input.readNullableLong()
                val width = input.readNullableInt()
                val height = input.readNullableInt()
                val hasThumbnail = input.readBoolean()
                val chunkCount = input.readInt()
                check(
                    sizeBytes >= 0 && chunkCount >= 0 &&
                        (durationMs == null || durationMs >= 0) &&
                        (width == null || width >= 0) &&
                        (height == null || height >= 0) &&
                        input.read() == -1,
                ) { "invalid media metadata values" }
                MediaMetadata(
                    media = StoredMedia(
                        id = id,
                        kind = kind,
                        fileName = fileName,
                        mimeType = mimeType,
                        personId = personId,
                        sizeBytes = sizeBytes,
                        importedAt = importedAt,
                        sourceDate = sourceDate,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        hasThumbnail = hasThumbnail,
                    ),
                    chunkCount = chunkCount,
                )
            }
            check(ownerId(file.parentFile) == decoded.media.personId) { "media owner path mismatch" }
            decoded
        } catch (failure: Exception) {
            throw IllegalStateException("cannot read encrypted media metadata", failure)
        }

    private fun findMetadata(id: String): File? =
        ownerDirectories()
            .asSequence()
            .map { File(it, "$id$METADATA_SUFFIX") }
            .firstOrNull(File::isFile)

    private fun ownerDirectories(): List<File> {
        if (!mediaDir.isDirectory) return emptyList()
        return mediaDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory && (dir.name == SELF_DIR_NAME || StorageLayout.PERSON_ID_PATTERN.matches(dir.name))
            }
            .orEmpty()
    }

    /** A leftover file from an interrupted save must not fail the whole import. */
    private fun availableMediaId(dir: File): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = UUID.randomUUID().toString().replace("-", "")
            val taken = File(dir, "$candidate$DATA_SUFFIX").exists() ||
                File(dir, "$candidate$METADATA_SUFFIX").exists() ||
                File(dir, "$candidate$THUMBNAIL_SUFFIX").exists()
            if (!taken) return candidate
        }
        throw IllegalStateException("media id collision after $MAX_ID_ATTEMPTS attempts")
    }

    private fun ownerDirectory(personId: String?): File = File(mediaDir, personId ?: SELF_DIR_NAME)

    private fun ownerId(directory: File?): String? =
        directory?.name?.takeUnless { it == SELF_DIR_NAME }

    /**
     * Media ids are generated here, so anything that is not exactly 32 lowercase
     * hex characters is rejected before it can become a path segment.
     */
    private fun requireMediaId(id: String) {
        require(MediaStore.MEDIA_ID_PATTERN.matches(id)) { "illegal media id" }
    }

    private data class MediaMetadata(val media: StoredMedia, val chunkCount: Int)

    companion object {
        const val MIME_OCTET_STREAM = "application/octet-stream"

        const val SELF_DIR_NAME = EncryptedAttachmentStore.SELF_DIR_NAME
        const val DATA_SUFFIX = ".data.enc"
        const val METADATA_SUFFIX = ".meta.enc"
        const val THUMBNAIL_SUFFIX = ".thumb.enc"

        private const val MAX_ID_ATTEMPTS = 3

        /**
         * Payload chunks encrypted/decrypted concurrently (ADR-063). Two workers
         * hide the per-chunk AES latency without doubling the ~10 MiB block
         * buffers beyond what a phone comfortably keeps in memory.
         */
        private const val PARALLELISM = 2
        private const val DATA_MAGIC = 0x4D454449
        private const val METADATA_MAGIC = 0x4D4D4554
        private const val FORMAT_VERSION = ChunkedEncryptedFile.FORMAT_VERSION
        private const val DATA_TEMP_SUFFIX = ".data.part"
        private const val METADATA_TEMP_SUFFIX = ".meta.part"
        private const val THUMBNAIL_TEMP_SUFFIX = ".thumb.part"
        private val METADATA_FILE_PATTERN = Regex("^[a-f0-9]{32}\\.meta\\.enc$")
    }
}

/**
 * Both timestamps are persisted as epoch milliseconds, so a nanosecond-precision
 * [Instant] would not survive a read back. Truncating on the way in keeps the
 * value returned by `save` identical to the one [MediaStore.find] reports later.
 */
private fun Instant.toStoragePrecision(): Instant = Instant.ofEpochMilli(toEpochMilli())
