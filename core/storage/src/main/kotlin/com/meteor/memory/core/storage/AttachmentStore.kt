package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
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
}

class EncryptedAttachmentStore(
    rootDir: File,
    private val crypto: FileCrypto,
) : AttachmentStore {

    private val attachmentsDir = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}")

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

        val id = UUID.randomUUID().toString().replace("-", "")
        val ownerDir = ownerDirectory(personId)
        check(ownerDir.isDirectory || ownerDir.mkdirs()) { "cannot create attachment directory" }

        val dataFile = File(ownerDir, "$id$DATA_SUFFIX")
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
                    ?.map { metadataFile -> readMetadata(metadataFile) }
                    .orEmpty()
            }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    override fun readIndexedText(id: String): String? {
        val metadata = findMetadata(id) ?: return null
        val details = decodeMetadata(metadata)
        if (!details.hasIndexedText) return null
        val textFile = File(metadata.parentFile, "$id$TEXT_SUFFIX")
        check(textFile.isFile) { "indexed text is missing" }
        return crypto.decrypt(textFile.readBytes()).toString(Charsets.UTF_8)
    }

    override fun copyDecrypted(id: String, output: OutputStream): Long {
        require(ATTACHMENT_ID_PATTERN.matches(id)) { "illegal attachment id" }
        val metadataFile = findMetadata(id) ?: error("attachment not found")
        val metadata = decodeMetadata(metadataFile)
        val dataFile = File(metadataFile.parentFile, "$id$DATA_SUFFIX")
        check(dataFile.isFile) { "encrypted attachment is missing" }

        DataInputStream(dataFile.inputStream().buffered()).use { input ->
            check(input.readInt() == DATA_MAGIC) { "invalid attachment header" }
            check(input.readInt() == FORMAT_VERSION) { "unsupported attachment format" }

            var chunkIndex = 0
            var bytesCopied = 0L
            while (true) {
                val encryptedSize = input.readInt()
                if (encryptedSize == END_OF_DATA) break
                check(encryptedSize in MIN_ENCRYPTED_CHUNK_SIZE..MAX_ENCRYPTED_CHUNK_SIZE) {
                    "invalid encrypted chunk size"
                }

                val encrypted = ByteArray(encryptedSize)
                input.readFully(encrypted)
                val chunk = crypto.decrypt(encrypted)
                val chunkInput = DataInputStream(ByteArrayInputStream(chunk))
                check(chunkInput.readInt() == chunkIndex) { "attachment chunk order is invalid" }
                val content = chunk.copyOfRange(Int.SIZE_BYTES, chunk.size)
                check(content.isNotEmpty() && content.size <= CHUNK_SIZE) { "invalid attachment chunk" }
                output.write(content)
                bytesCopied += content.size
                chunkIndex++
            }

            check(input.read() == -1) { "unexpected bytes after attachment end" }
            check(chunkIndex == metadata.chunkCount && bytesCopied == metadata.attachment.sizeBytes) {
                "attachment size does not match metadata"
            }
            return bytesCopied
        }
    }

    internal fun restorePayload(id: String, personId: String?, input: InputStream) {
        require(ATTACHMENT_ID_PATTERN.matches(id)) { "illegal attachment id" }
        require(personId == null || StorageLayout.PERSON_ID_PATTERN.matches(personId)) {
            "illegal person_id: $personId"
        }

        val ownerDir = ownerDirectory(personId)
        check(ownerDir.isDirectory || ownerDir.mkdirs()) { "cannot create attachment directory" }
        val target = File(ownerDir, "$id$DATA_SUFFIX")
        val temporary = File(ownerDir, "$id$DATA_TEMP_SUFFIX")
        val previous = File(ownerDir, "$id$DATA_BACKUP_SUFFIX")
        var previousMoved = false
        try {
            writeEncryptedChunks(input, temporary)
            if (target.exists()) {
                check(!previous.exists() && target.renameTo(previous)) {
                    "cannot preserve existing attachment"
                }
                previousMoved = true
            }
            check(temporary.renameTo(target)) { "cannot restore encrypted attachment" }
            if (previousMoved) previous.delete()
        } catch (failure: Exception) {
            temporary.delete()
            if (previousMoved && previous.exists()) previous.renameTo(target)
            throw failure
        }
    }

    private fun writeEncryptedChunks(input: InputStream, target: File): PayloadStats {
        var index = 0
        var totalBytes = 0L
        DataOutputStream(target.outputStream().buffered()).use { output ->
            output.writeInt(DATA_MAGIC)
            output.writeInt(FORMAT_VERSION)
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val count = readChunk(input, buffer)
                if (count == 0) break

                val frame = ByteBuffer.allocate(Int.SIZE_BYTES + count)
                    .putInt(index)
                    .put(buffer, 0, count)
                    .array()
                val encrypted = crypto.encrypt(frame)
                output.writeInt(encrypted.size)
                output.write(encrypted)
                totalBytes += count
                index++
            }
            output.writeInt(END_OF_DATA)
        }
        return PayloadStats(sizeBytes = totalBytes, chunkCount = index)
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[count++] = single.toByte()
            } else {
                count += read
            }
        }
        return count
    }

    private fun encodeMetadata(
        attachment: StoredAttachment,
        chunkCount: Int,
        hasIndexedText: Boolean,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(METADATA_MAGIC)
            output.writeInt(FORMAT_VERSION)
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
                check(input.readInt() == METADATA_MAGIC) { "invalid attachment metadata" }
                check(input.readInt() == FORMAT_VERSION) { "unsupported attachment metadata" }
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
        private const val DATA_MAGIC = 0x4D415454
        private const val METADATA_MAGIC = 0x4D414D44
        private const val FORMAT_VERSION = 1
        private const val CHUNK_SIZE = 1024 * 1024
        private const val MAX_INDEXED_TEXT_BYTES = 2 * 1024 * 1024
        private const val MIN_ENCRYPTED_CHUNK_SIZE = 29
        private const val MAX_ENCRYPTED_CHUNK_SIZE = CHUNK_SIZE + Int.SIZE_BYTES + 29
        private const val END_OF_DATA = 0
        private const val DATA_SUFFIX = ".data.enc"
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

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
}

private fun DataInputStream.readString(): String {
    val size = readInt()
    check(size in 0..MAX_METADATA_STRING_BYTES) { "invalid metadata string size" }
    val bytes = ByteArray(size)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readString() else null

private const val MAX_METADATA_STRING_BYTES = 1024 * 1024
