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
    /**
     * Payload key for images written by this build (ADR-067). Defaults to
     * [crypto] so a router built without it keeps the pre-ADR-067 behaviour.
     */
    mediaKeyCrypto: FileCrypto = crypto,
) : StorageRouter {

    private val layout = StorageLayout(File(context.filesDir, StorageLayout.ROOT_NAME))
    private val attachmentStore = EncryptedAttachmentStore(layout.rootDir(), crypto, mediaKeyCrypto)
    private val imageStore = EncryptedImageStore(layout.rootDir(), crypto, mediaKeyCrypto)

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

        // Attachment id → chunk count of the payload this import wrote. Local to
        // the call (never a field) so two imports cannot corrupt each other, and
        // flushed only once the zip is exhausted: the sidecar entry can arrive
        // before its payload, so re-stamping inline would race the entry order.
        val restoredChunkCounts = LinkedHashMap<String, Int>()
        try {
            stream.use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) importEntry(root, entry, zip, restoredChunkCounts)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } finally {
            // Also on a failed import: whatever payloads already reached the disk
            // must not be left with a sidecar that disagrees with them. One bad
            // re-stamp must not hide the original failure either.
            restoredChunkCounts.forEach { (id, chunkCount) ->
                runCatching { attachmentStore.rewriteChunkCount(id, chunkCount) }
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
            val imageToken = imageToken(relative)
            when {
                attachmentPayload != null -> attachmentStore.copyDecrypted(attachmentPayload.id, zip)
                // An image written after ADR-067 is a chunked frame, which the
                // whole-file decrypt in plaintextOf() cannot open.
                imageToken != null -> zip.write(imagePlaintext(imageToken, child))
                else -> zip.write(plaintextOf(child))
            }
            zip.closeEntry()
        }
    }

    /**
     * Decrypted bytes of one stored image.
     *
     * Buffered on purpose: an unreadable image used to be copied through as raw
     * bytes rather than aborting the whole export, so the failure has to be
     * detected before anything reaches the zip. Images are small (the editor
     * compresses to a 1568 px longest edge), so this costs no more than the
     * whole-file read it replaces.
     */
    private fun imagePlaintext(token: String, file: File): ByteArray =
        imageStore.load(token) ?: plaintextOf(file)

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

    private fun importEntry(
        root: File,
        entry: ZipEntry,
        input: InputStream,
        restoredChunkCounts: MutableMap<String, Int>,
    ) {
        val relative = safeRelativePath(entry.name) ?: throw StorageException("illegal entry: ${entry.name}")
        val target = File(root, relative)

        val rootPath = root.canonicalPath
        if (!target.canonicalPath.startsWith(rootPath + File.separator)) {
            throw StorageException("entry escapes archive root: ${entry.name}")
        }

        target.parentFile?.mkdirs()
        val attachmentPayload = attachmentPayload(relative)
        if (attachmentPayload != null) {
            // The payload is re-framed with this build's chunk size, so the
            // sidecar's chunk count is re-stamped after the whole zip is read.
            restoredChunkCounts[attachmentPayload.id] = attachmentStore.restorePayload(
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
        // An image re-enters through the image store so it lands as a chunked
        // frame under the key this build uses, and its token still resolves
        // (ADR-067). The bytes in the archive are already plaintext. A store that
        // cannot honour the fixed token returns null, and the fallback below then
        // keeps the image rather than dropping it.
        val imageToken = imageToken(relative)
        val restored = imageToken?.let { token ->
            runCatching { imageStore.restore(token, bytes) }.getOrNull()
        }
        if (restored != null) return
        // Anything else unrecognised: re-encrypt whole bytes with this device's key.
        target.writeBytes(crypto.encrypt(bytes))
    }

    /**
     * Archive-relative path of a stored image, or null when the entry is not one.
     *
     * Mirrors [EncryptedImageStore]: exactly `数字遗产/图片/<owner>/<file>.enc`.
     */
    private fun imageToken(relative: String): String? {
        val parts = relative.split('/')
        if (parts.size != IMAGE_PATH_SEGMENTS ||
            parts[0] != StorageLayout.LEGACY ||
            parts[1] != StorageLayout.IMAGES
        ) {
            return null
        }
        val owner = parts[2]
        if (owner != EncryptedImageStore.SELF_DIR && !StorageLayout.PERSON_ID_PATTERN.matches(owner)) {
            return null
        }
        val fileName = parts[3]
        if (fileName.length <= EncryptedImageStore.EXTENSION.length ||
            !fileName.endsWith(EncryptedImageStore.EXTENSION)
        ) {
            return null
        }
        return relative
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

        /** `数字遗产 / 图片 / <owner> / <file>.enc`, mirroring `EncryptedImageStore`. */
        const val IMAGE_PATH_SEGMENTS = 4
    }
}
