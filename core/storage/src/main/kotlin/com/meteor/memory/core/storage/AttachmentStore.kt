package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class StoredAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val personId: String?,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

/**
 * Encrypted arbitrary-file storage. Ownership and the display metadata are
 * encrypted; only the opaque person directory and random attachment id remain
 * visible on disk.
 */
interface AttachmentStore {
    fun save(
        input: InputStream,
        fileName: String,
        mimeType: String,
        personId: String?,
        indexedText: String? = null,
    ): StoredAttachment

    /** Lists all attachments, or only those owned by [personId]. */
    fun list(personId: String? = null): List<StoredAttachment>

    /** Decrypted text extracted at import, or null when the attachment is not text-indexable. */
    fun readIndexedText(id: String): String?

    /** Copies authenticated plaintext to [output] and returns its byte count. */
    fun copyDecrypted(id: String, output: OutputStream): Long

    /**
     * Streams authenticated plaintext from [offsetBytes] (a plaintext offset).
     *
     * Added under R2 as an additive, defaulted method: an implementation that
     * cannot stream keeps compiling, and callers fall back to [copyDecrypted].
     */
    fun openDecrypted(id: String, offsetBytes: Long = 0L): InputStream =
        throw UnsupportedOperationException("streaming decryption is not supported")

    /**
     * Deletes the attachment data and sidecars.
     *
     * This is irreversible; callers should confirm the user's intent first.
     *
     * @return true when at least one file was deleted.
     */
    fun delete(id: String): Boolean
}

/**
 * Chunked-encrypted attachment store under `数字遗产/附件/<owner>/`.
 *
 * Payloads are written with the **same** framing and the **same** key as media
 * (ADR-067, extending ADR-063): format version 3, 10 MiB chunks, encrypted with
 * the in-process media data key, so a large attachment is encrypted and
 * decrypted by the same code path and the same parallel workers a video uses.
 * The metadata sidecar and the extracted text stay on the Keystore key, exactly
 * like the media sidecars.
 *
 * Reads pick the key from the payload's header version, so attachments written
 * before ADR-067 (v1, 1 MiB, Keystore key) remain readable with no migration.
 */
class EncryptedAttachmentStore(
    rootDir: File,
    private val crypto: FileCrypto,
    /**
     * Key for the attachment payload itself (ADR-067). Defaults to [crypto] so
     * existing callers and tests keep the pre-ADR-067 single-key behaviour.
     */
    private val mediaKeyCrypto: FileCrypto = crypto,
) : AttachmentStore {

    private val attachmentsDir = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}")

    /**
     * Reads pick the payload key from the format version: v3 files were written
     * with [mediaKeyCrypto], older v1/v2 files with [crypto].
     */
    private val cryptoFor: (Int) -> FileCrypto = { version ->
        if (version >= ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY) mediaKeyCrypto else crypto
    }

    override fun save(
        input: InputStream,
        fileName: String,
        mimeType: String,
        personId: String?,
        indexedText: String?,
    ): StoredAttachment {
        require(fileName.isNotBlank()) { "file name is required" }
        require(personId == null || StorageLayout.PERSON_ID_PATTERN.matches(personId)) {
            "illegal person_id: $personId"
        }

        val textBytes = indexedText
            ?.takeIf(String::isNotBlank)
            ?.toByteArray(Charsets.UTF_8)
            ?.also { require(it.size <= MAX_INDEXED_TEXT_BYTES) { "indexed text is too large" } }

        val ownerDir = ownerDirectory(personId)
        check(ownerDir.isDirectory || ownerDir.mkdirs()) { "cannot create attachment directory" }
        val id = availableAttachmentId(ownerDir)

        val dataFile = File(ownerDir, "$id$ATTACHMENT_DATA_SUFFIX")
        val metadataFile = File(ownerDir, "$id$METADATA_SUFFIX")
        val textFile = File(ownerDir, "$id$TEXT_SUFFIX")
        val dataTemp = File(ownerDir, "$id$DATA_TEMP_SUFFIX")
        val metadataTemp = File(ownerDir, "$id$METADATA_TEMP_SUFFIX")
        val textTemp = File(ownerDir, "$id$TEXT_TEMP_SUFFIX")
        var dataFinalized = false
        var metadataFinalized = false
        var textFinalized = false

        try {
            check(!dataFile.exists() && !metadataFile.exists() && !textFile.exists()) {
                "attachment id collision"
            }
            val payload = writeEncryptedChunks(input, dataTemp)
            if (textBytes != null) textTemp.writeBytes(crypto.encrypt(textBytes))

            val attachment = StoredAttachment(
                id = id,
                fileName = fileName,
                mimeType = mimeType.ifBlank { MIME_OCTET_STREAM },
                personId = personId,
                sizeBytes = payload.sizeBytes,
                modifiedAt = Instant.now(),
            )
            metadataTemp.writeBytes(crypto.encrypt(encodeMetadata(attachment, payload.chunkCount, textBytes != null)))

            check(dataTemp.renameTo(dataFile)) { "cannot finalize encrypted attachment" }
            dataFinalized = true
            if (textBytes != null) {
                check(textTemp.renameTo(textFile)) { "cannot finalize encrypted text" }
                textFinalized = true
            }
            check(metadataTemp.renameTo(metadataFile)) { "cannot finalize attachment metadata" }
            metadataFinalized = true
            return attachment
        } catch (failure: Exception) {
            dataTemp.delete()
            metadataTemp.delete()
            textTemp.delete()
            if (dataFinalized) dataFile.delete()
            if (metadataFinalized) metadataFile.delete()
            if (textFinalized) textFile.delete()
            throw failure
        }
    }

    override fun list(personId: String?): List<StoredAttachment> {
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
                    ?.mapNotNull { metadataFile -> runCatching { readMetadata(metadataFile) }.getOrNull() }
                    .orEmpty()
            }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    override fun readIndexedText(id: String): String? {
        val metadata = findMetadata(id) ?: return null
        val details = runCatching { decodeMetadata(metadata) }.getOrNull() ?: return null
        if (!details.hasIndexedText) return null
        val textFile = File(metadata.parentFile, "$id$TEXT_SUFFIX")
        if (!textFile.isFile) return null
        return runCatching {
            crypto.decrypt(textFile.readBytes()).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override fun copyDecrypted(id: String, output: OutputStream): Long {
        require(ATTACHMENT_ID_PATTERN.matches(id)) { "illegal attachment id" }
        val metadataFile = findMetadata(id) ?: error("attachment not found")
        val metadata = decodeMetadata(metadataFile)
        val dataFile = File(metadataFile.parentFile, "$id$ATTACHMENT_DATA_SUFFIX")
        check(dataFile.isFile) { "encrypted attachment is missing" }

        // Same framing and key as media (ADR-067); the version dispatch keeps
        // pre-ADR-067 attachments (v1, Keystore key) readable.
        val stats = ChunkedEncryptedFile.copyDecrypted(
            source = dataFile,
            magic = DATA_MAGIC,
            cryptoFor = cryptoFor,
            output = output,
            parallelism = PARALLELISM,
        )
        check(stats.chunkCount == metadata.chunkCount && stats.sizeBytes == metadata.attachment.sizeBytes) {
            "attachment size does not match metadata"
        }
        return stats.sizeBytes
    }

    override fun openDecrypted(id: String, offsetBytes: Long): InputStream {
        require(ATTACHMENT_ID_PATTERN.matches(id)) { "illegal attachment id" }
        val metadataFile = findMetadata(id) ?: error("attachment not found")
        val metadata = decodeMetadata(metadataFile)
        require(offsetBytes in 0..metadata.attachment.sizeBytes) { "offset outside attachment" }
        val dataFile = File(metadataFile.parentFile, "$id$ATTACHMENT_DATA_SUFFIX")
        check(dataFile.isFile) { "encrypted attachment is missing" }
        return ChunkedEncryptedFile.openDecrypted(dataFile, DATA_MAGIC, cryptoFor, offsetBytes)
    }

    override fun delete(id: String): Boolean {
        require(ATTACHMENT_ID_PATTERN.matches(id)) { "illegal attachment id" }
        var removed = false
        ownerDirectories().forEach { dir ->
            listOf(ATTACHMENT_DATA_SUFFIX, METADATA_SUFFIX, TEXT_SUFFIX).forEach { suffix ->
                if (File(dir, "$id$suffix").delete()) removed = true
            }
        }
        return removed
    }

    /**
     * Rewrites only the payload, returning the chunk count it was written with.
     *
     * The caller must persist that count into the metadata sidecar once the whole
     * archive is imported ([rewriteChunkCount]): a pre-ADR-067 backup carries a
     * sidecar describing 1 MiB chunks, while this build writes 10 MiB ones, and
     * [copyDecrypted] refuses a payload whose chunk count disagrees with the
     * sidecar. The rewrite cannot happen here because the sidecar entry of the
     * archive is imported by the caller as a separate, independent step.
     */
    internal fun restorePayload(id: String, personId: String?, input: InputStream): Int {
        require(ATTACHMENT_ID_PATTERN.matches(id)) { "illegal attachment id" }
        require(personId == null || StorageLayout.PERSON_ID_PATTERN.matches(personId)) {
            "illegal person_id: $personId"
        }

        val ownerDir = ownerDirectory(personId)
        check(ownerDir.isDirectory || ownerDir.mkdirs()) { "cannot create attachment directory" }
        val target = File(ownerDir, "$id$ATTACHMENT_DATA_SUFFIX")
        val temporary = File(ownerDir, "$id$DATA_TEMP_SUFFIX")
        val previous = File(ownerDir, "$id$DATA_BACKUP_SUFFIX")
        var previousMoved = false
        try {
            val payload = writeEncryptedChunks(input, temporary)
            if (target.exists()) {
                check(!previous.exists() && target.renameTo(previous)) {
                    "cannot preserve existing attachment"
                }
                previousMoved = true
            }
            check(temporary.renameTo(target)) { "cannot restore encrypted attachment" }
            if (previousMoved) previous.delete()
            return payload.chunkCount
        } catch (failure: Exception) {
            temporary.delete()
            if (previousMoved && previous.exists()) previous.renameTo(target)
            throw failure
        }
    }

    /**
     * Re-stamps the sidecar's chunk count after a restore rewrote the payload.
     *
     * Without this, a backup taken before ADR-067 would restore into an
     * attachment whose sidecar still claims the old 1 MiB framing and whose
     * payload is 10 MiB framed — [copyDecrypted] rejects that, so the file would
     * be permanently unreadable. A missing or unreadable sidecar is left alone:
     * the payload is already on disk and must not be dropped for it.
     */
    internal fun rewriteChunkCount(id: String, chunkCount: Int) {
        if (!ATTACHMENT_ID_PATTERN.matches(id) || chunkCount < 0) return
        val metadataFile = findMetadata(id) ?: return
        val details = runCatching { decodeMetadata(metadataFile) }.getOrNull() ?: return
        if (details.chunkCount == chunkCount) return
        val updated = encodeMetadata(
            attachment = details.attachment,
            chunkCount = chunkCount,
            hasIndexedText = details.hasIndexedText,
        )
        val temporary = File(metadataFile.parentFile, "$id$METADATA_TEMP_SUFFIX")
        try {
            temporary.writeBytes(crypto.encrypt(updated))
            // The sidecar already exists, so the replace must overwrite it
            // atomically rather than rely on rename semantics per platform.
            replaceAtomically(temporary, metadataFile)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Atomic on the same filesystem; the non-atomic move is the fallback. */
    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeEncryptedChunks(input: InputStream, target: File): PayloadStats {
        // Media framing and media key (ADR-067): 10 MiB chunks, two encryption
        // workers. A large imported file now costs what a large video costs.
        val stats = ChunkedEncryptedFile.write(
            input = input,
            target = target,
            magic = DATA_MAGIC,
            crypto = mediaKeyCrypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
            parallelism = PARALLELISM,
        )
        return PayloadStats(sizeBytes = stats.sizeBytes, chunkCount = stats.chunkCount)
    }

    private fun encodeMetadata(
        attachment: StoredAttachment,
        chunkCount: Int,
        hasIndexedText: Boolean,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeMetadataHeader(METADATA_MAGIC, FORMAT_VERSION)
            output.writeString(attachment.id)
            output.writeString(attachment.fileName)
            output.writeString(attachment.mimeType)
            output.writeNullableString(attachment.personId)
            output.writeLong(attachment.sizeBytes)
            output.writeLong(attachment.modifiedAt.toEpochMilli())
            output.writeInt(chunkCount)
            output.writeBoolean(hasIndexedText)
        }
        bytes.toByteArray()
    }

    private fun readMetadata(file: File): StoredAttachment = decodeMetadata(file).attachment

    private fun decodeMetadata(file: File): AttachmentMetadata =
        try {
            val id = file.name.removeSuffix(METADATA_SUFFIX)
            val decoded = DataInputStream(ByteArrayInputStream(crypto.decrypt(file.readBytes()))).use { input ->
                input.readMetadataHeader(METADATA_MAGIC, FORMAT_VERSION)
                val attachmentId = input.readString()
                check(attachmentId == id && ATTACHMENT_ID_PATTERN.matches(id)) { "invalid attachment id" }
                val name = input.readString()
                val mimeType = input.readString()
                val personId = input.readNullableString()
                check(personId == null || StorageLayout.PERSON_ID_PATTERN.matches(personId)) {
                    "invalid attachment owner"
                }
                val sizeBytes = input.readLong()
                val modifiedAt = Instant.ofEpochMilli(input.readLong())
                val chunkCount = input.readInt()
                val hasIndexedText = input.readBoolean()
                check(sizeBytes >= 0 && chunkCount >= 0 && input.read() == -1) {
                    "invalid attachment metadata values"
                }
                AttachmentMetadata(
                    attachment = StoredAttachment(
                        id = id,
                        fileName = name,
                        mimeType = mimeType,
                        personId = personId,
                        sizeBytes = sizeBytes,
                        modifiedAt = modifiedAt,
                    ),
                    chunkCount = chunkCount,
                    hasIndexedText = hasIndexedText,
                )
            }
            check(ownerId(file.parentFile) == decoded.attachment.personId) { "attachment owner path mismatch" }
            decoded
        } catch (failure: Exception) {
            throw IllegalStateException("cannot read encrypted attachment metadata", failure)
        }

    private fun findMetadata(id: String): File? {
        require(ATTACHMENT_ID_PATTERN.matches(id)) { "illegal attachment id" }
        return ownerDirectories()
            .asSequence()
            .map { File(it, "$id$METADATA_SUFFIX") }
            .firstOrNull(File::isFile)
    }

    private fun ownerDirectories(): List<File> {
        if (!attachmentsDir.isDirectory) return emptyList()
        return attachmentsDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory && (dir.name == SELF_DIR_NAME || StorageLayout.PERSON_ID_PATTERN.matches(dir.name))
            }
            .orEmpty()
    }

    /**
     * A UUID collision is practically impossible, but a file left behind by an
     * interrupted save would otherwise fail the whole import; try a few ids
     * before giving up instead of throwing on the first clash.
     */
    private fun availableAttachmentId(dir: File): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = UUID.randomUUID().toString().replace("-", "")
            val taken = File(dir, "$candidate$ATTACHMENT_DATA_SUFFIX").exists() ||
                File(dir, "$candidate$METADATA_SUFFIX").exists() ||
                File(dir, "$candidate$TEXT_SUFFIX").exists()
            if (!taken) return candidate
        }
        throw IllegalStateException("attachment id collision after $MAX_ID_ATTEMPTS attempts")
    }

    private fun ownerDirectory(personId: String?): File =
        File(attachmentsDir, personId ?: SELF_DIR_NAME)

    private fun ownerId(directory: File?): String? =
        directory?.name?.takeUnless { it == SELF_DIR_NAME }

    private data class PayloadStats(val sizeBytes: Long, val chunkCount: Int)

    private data class AttachmentMetadata(
        val attachment: StoredAttachment,
        val chunkCount: Int,
        val hasIndexedText: Boolean,
    )

    companion object {
        const val MIME_OCTET_STREAM = "application/octet-stream"

        const val SELF_DIR_NAME = "_self"
        const val ATTACHMENT_DATA_SUFFIX = ".data.enc"
        private const val MAX_ID_ATTEMPTS = 3
        private const val DATA_MAGIC = 0x4D415454
        private const val METADATA_MAGIC = 0x4D414D44
        private const val FORMAT_VERSION = ChunkedEncryptedFile.FORMAT_VERSION

        /**
         * Payload chunks encrypted/decrypted concurrently (ADR-067), the same
         * value media uses (ADR-063): two workers hide the per-chunk AES latency
         * without doubling the ~10 MiB block buffers in memory.
         */
        private const val PARALLELISM = 2

        private const val MAX_INDEXED_TEXT_BYTES = 2 * 1024 * 1024
        private const val METADATA_SUFFIX = ".meta.enc"
        private const val TEXT_SUFFIX = ".text.enc"
        private const val DATA_TEMP_SUFFIX = ".data.part"
        private const val DATA_BACKUP_SUFFIX = ".data.backup"
        private const val METADATA_TEMP_SUFFIX = ".meta.part"
        private const val TEXT_TEMP_SUFFIX = ".text.part"
        private val ATTACHMENT_ID_PATTERN = Regex("^[a-f0-9]{32}$")
        private val METADATA_FILE_PATTERN = Regex("^[a-f0-9]{32}\\.meta\\.enc$")
    }
}
