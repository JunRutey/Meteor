package com.meteor.memory.core.storage

import android.content.Context
import android.net.Uri
import com.meteor.memory.core.crypto.FileCrypto
import com.meteor.memory.core.time.TimeModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Filesystem-backed implementation of the frozen [StorageRouter] contract.
 *
 * Encryption is transparent to callers: [read] hands back plaintext, [write]
 * encrypts on the way out. Nothing above this class ever sees ciphertext.
 *
 * Backups are deliberately plaintext (ADR-004): the Keystore key is device
 * bound, so a ciphertext backup would be unreadable on a new device.
 */
class PrivateStorageRouter(
    private val context: Context,
    private val crypto: FileCrypto,
    private val time: TimeModule,
) : StorageRouter {

    private val layout = StorageLayout(File(context.filesDir, StorageLayout.ROOT_NAME))
    private val attachmentStore = EncryptedAttachmentStore(layout.rootDir(), crypto)

    override fun route(personId: String?, type: RecordType, date: LocalDate): File =
        layout.fileFor(personId, type, date)

    override fun read(file: File): Document {
        if (!file.exists()) throw StorageException("missing file: ${file.name}")
        return DocumentCodec.decode(file, file.readBytes(), crypto, time)
    }

    override fun write(file: File, doc: Document) {
        file.parentFile?.mkdirs()
        val header = DocumentHeader(
            personId = doc.personId,
            type = doc.type,
            timestamp = doc.timestamp,
            source = doc.source,
        )
        val stamped = time.stamp(doc.body, doc.timestamp)
        file.writeBytes(DocumentCodec.encode(header, stamped, crypto))
    }

    override suspend fun exportTo(zipUri: Uri) = withContext(Dispatchers.IO) {
        val root = layout.rootDir()
        if (!root.exists()) throw StorageException("nothing to export yet")

        val stream = context.contentResolver.openOutputStream(zipUri)
            ?: throw StorageException("cannot open export destination")

        stream.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                exportDirectory(root, root, zip)
            }
        }
        Unit
    }

    override suspend fun importFrom(zipUri: Uri) = withContext(Dispatchers.IO) {
        val root = layout.rootDir()
        val stream = context.contentResolver.openInputStream(zipUri)
            ?: throw StorageException("cannot open backup archive")

        stream.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) importEntry(root, entry, zip)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        Unit
    }

    // --- export ------------------------------------------------------------

    private fun exportDirectory(root: File, dir: File, zip: ZipOutputStream) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                exportDirectory(root, child, zip)
                continue
            }
            val relative = child.relativeTo(root).invariantSeparatorsPath
            val entryName = "${root.name}/$relative"
            zip.putNextEntry(ZipEntry(entryName))
            val attachmentPayload = attachmentPayload(relative)
            if (attachmentPayload != null) {
                attachmentStore.copyDecrypted(attachmentPayload.id, zip)
            } else {
                zip.write(plaintextOf(child))
            }
            zip.closeEntry()
        }
    }

    /** Readable Markdown for records, decrypted bytes for media, raw bytes otherwise. */
    private fun plaintextOf(file: File): ByteArray {
        val bytes = file.readBytes()
        if (file.extension.equals("md", ignoreCase = true)) {
            val doc = runCatching { DocumentCodec.decode(file, bytes, crypto, time) }.getOrNull()
            if (doc != null) {
                val header = DocumentHeader(doc.personId, doc.type, doc.timestamp, doc.source)
                return DocumentCodec.encodePlaintext(header, time.stamp(doc.body, doc.timestamp))
            }
            return bytes
        }
        return runCatching { crypto.decrypt(bytes) }.getOrDefault(bytes)
    }

    // --- import ------------------------------------------------------------

    private fun importEntry(root: File, entry: ZipEntry, input: InputStream) {
        val relative = safeRelativePath(entry.name) ?: throw StorageException("illegal entry: ${entry.name}")
        val target = File(root, relative)

        val rootPath = root.canonicalPath
        if (!target.canonicalPath.startsWith(rootPath + File.separator)) {
            throw StorageException("entry escapes archive root: ${entry.name}")
        }

        target.parentFile?.mkdirs()
        val attachmentPayload = attachmentPayload(relative)
        if (attachmentPayload != null) {
            attachmentStore.restorePayload(
                id = attachmentPayload.id,
                personId = attachmentPayload.personId,
                input = input,
            )
            return
        }

        val bytes = input.readBytes()
        if (target.extension.equals("md", ignoreCase = true)) {
            val plain = DocumentCodec.decodePlaintext(bytes)
            if (plain != null) {
                val (header, body) = plain
                target.writeBytes(DocumentCodec.encode(header, body, crypto))
                return
            }
        }
        // Media and anything unrecognised: re-encrypt whole bytes with this device's key.
        target.writeBytes(crypto.encrypt(bytes))
    }

    private fun attachmentPayload(relative: String): AttachmentPayload? {
        val parts = relative.split('/')
        if (parts.size != ATTACHMENT_PATH_SEGMENTS ||
            parts[0] != StorageLayout.LEGACY ||
            parts[1] != StorageLayout.ATTACHMENTS
        ) {
            return null
        }
        val personId = when (val owner = parts[2]) {
            EncryptedAttachmentStore.SELF_DIR_NAME -> null
            else -> owner.takeIf(StorageLayout.PERSON_ID_PATTERN::matches) ?: return null
        }
        val fileName = parts[3]
        if (!fileName.matches(ATTACHMENT_DATA_FILE_PATTERN)) return null
        return AttachmentPayload(
            id = fileName.removeSuffix(ATTACHMENT_DATA_SUFFIX),
            personId = personId,
        )
    }

    private data class AttachmentPayload(
        val id: String,
        val personId: String?,
    )

    /**
     * Strips the leading `我的人生/` prefix and rejects traversal, absolute
     * paths and drive letters before anything touches the filesystem.
     */
    private fun safeRelativePath(entryName: String): String? {
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains(':')) return null
        if (normalized == StorageLayout.ROOT_NAME) return null

        val prefix = "${StorageLayout.ROOT_NAME}/"
        val relative = if (normalized.startsWith(prefix)) normalized.removePrefix(prefix) else normalized
        return relative.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val ATTACHMENT_PATH_SEGMENTS = 4
        const val ATTACHMENT_DATA_SUFFIX = ".data.enc"
        val ATTACHMENT_DATA_FILE_PATTERN = Regex("^[a-f0-9]{32}\\.data\\.enc$")
    }
}
