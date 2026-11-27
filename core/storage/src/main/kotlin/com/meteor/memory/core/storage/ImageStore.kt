package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** One encrypted image on disk, addressed by its token. */
data class StoredImage(
    val token: String,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

/**
 * Image storage addressed by an opaque token.
 *
 * Images carry no frontmatter (archive §5.2): the directory decides ownership and
 * the filename is a random token, so nothing sensitive leaks through the path.
 */
interface ImageStore {

    /**
     * Encrypts and stores [bytes], returning a stable token usable inside
     * Markdown as `![](meteor://image/<token>)`.
     */
    fun save(bytes: ByteArray, personId: String?, at: Instant = Instant.now()): String

    /** Reads and decrypts the image for [token], or null when it is gone. */
    fun load(token: String): ByteArray?

    /**
     * Streams the decrypted image for [token] to [output]; returns the byte count.
     *
     * Added under R2 as an additive, defaulted method: callers that only ever
     * hold small images keep using [load].
     */
    fun copyDecrypted(token: String, output: OutputStream): Long =
        throw UnsupportedOperationException("streaming decryption is not supported")

    /** Every stored image, newest first — powers the standalone gallery. */
    fun list(): List<StoredImage>

    /**
     * Writes [bytes] under an existing [token], replacing whatever was there.
     *
     * The backup import uses this to re-encrypt a plaintext image in place so
     * the `meteor://image/<token>` references in record bodies keep resolving
     * (ADR-067). Implementations that cannot honour a fixed token return null.
     *
     * Added under R2 as an additive, defaulted method.
     */
    fun restore(token: String, bytes: ByteArray): String? = null

    /**
     * Removes the encrypted image behind [token].
     *
     * Deleting is irreversible and leaves no tombstone: callers must already have
     * dropped the Markdown reference from every body that pointed here.
     *
     * @return true when a file was actually removed, false for an unknown token
     *   or a rejected path.
     */
    fun delete(token: String): Boolean

    companion object {
        /** Markdown URL scheme used inside a record body. */
        const val IMAGE_SCHEME = "meteor://image/"

        fun markdownFor(token: String, alt: String = ""): String = "![$alt]($IMAGE_SCHEME$token)"
    }
}

/**
 * Image storage on the unified payload format (ADR-067).
 *
 * A new image is written as a **v3 chunked frame** encrypted with the media data
 * key — the same framing, key and chunk size a media file or an attachment uses.
 * Only the container differs: an image is one file with no metadata sidecar, so
 * the framing magic (`IMAG`) tells it apart from a pre-ADR-067 whole-file
 * ciphertext, which starts with the AES-GCM IV length byte instead.
 *
 * Reads sniff the header: a `IMAG` magic means the chunked frame (key chosen by
 * its version), anything else is a legacy whole-file ciphertext decrypted with
 * the Keystore key. Old images therefore stay readable with no migration, and
 * new ones are written once, streamed, and readable by the same code that reads
 * media.
 */
class EncryptedImageStore(
    private val rootDir: File,
    private val crypto: FileCrypto,
    /**
     * Key for newly written images (ADR-067). Defaults to [crypto] so existing
     * callers and tests keep the pre-ADR-067 single-key behaviour.
     */
    private val mediaKeyCrypto: FileCrypto = crypto,
) : ImageStore {

    private val cryptoFor: (Int) -> FileCrypto = { version ->
        if (version >= ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY) mediaKeyCrypto else crypto
    }

    override fun save(bytes: ByteArray, personId: String?, at: Instant): String {
        val person = ownerOf(personId)
        val dir = ownerDirectory(person)
        if (!dir.exists()) dir.mkdirs()

        // Random name: the timestamp would leak when a memory was written.
        val file = File(dir, "${UUID.randomUUID()}$EXTENSION")
        writePayload(file, bytes)
        return tokenFor(person, file.name)
    }

    override fun restore(token: String, bytes: ByteArray): String? {
        val file = resolve(token) ?: return null
        writePayload(file, bytes)
        return token
    }

    override fun load(token: String): ByteArray? {
        val file = resolve(token) ?: return null
        if (!file.isFile) return null
        return runCatching {
            ByteArrayOutputStream().use { output ->
                copyTo(file, output)
                output.toByteArray()
            }
        }.getOrNull()
    }

    override fun copyDecrypted(token: String, output: OutputStream): Long {
        // A token that does not resolve is an error, not an empty image: the
        // caller (backup export) has to know that nothing was written.
        val file = resolve(token) ?: error("illegal image token")
        check(file.isFile) { "encrypted image is missing" }
        return copyTo(file, output)
    }

    override fun delete(token: String): Boolean {
        val file = resolve(token) ?: return false
        if (!file.isFile) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * Turns a stored token back into a file, refusing anything that escapes.
     *
     * A token is only ever produced by [save], so anything that is not exactly
     * `数字遗产/图片/<person>/<file>.enc` is rejected: a crafted token pointing at
     * a record or a profile file must not resolve to a readable path.
     */
    private fun resolve(token: String): File? {
        val parts = token.trim().removePrefix("/").replace('\\', '/').split('/')
        if (parts.size != IMAGE_PATH_SEGMENTS) return null
        if (parts[0] != StorageLayout.LEGACY || parts[1] != StorageLayout.IMAGES) return null
        val owner = parts[2]
        if (owner != SELF_DIR && !StorageLayout.PERSON_ID_PATTERN.matches(owner)) return null
        val fileName = parts[3]
        if (fileName.length <= EXTENSION.length || !fileName.endsWith(EXTENSION)) return null
        return File(rootDir, parts.joinToString("/"))
    }

    override fun list(): List<StoredImage> {
        val imagesRoot = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}")
        if (!imagesRoot.isDirectory) return emptyList()

        // Exactly one owner level, mirroring [save] and [resolve], so the gallery
        // never lists a file that could not be opened again.
        return imagesRoot.listFiles().orEmpty()
            .filter { dir ->
                dir.isDirectory &&
                    (dir.name == SELF_DIR || StorageLayout.PERSON_ID_PATTERN.matches(dir.name))
            }
            .flatMap { ownerDir -> ownerDir.listFiles().orEmpty().asList() }
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .map { file ->
                StoredImage(
                    token = file.relativeTo(rootDir).invariantSeparatorsPath,
                    sizeBytes = file.length(),
                    modifiedAt = Instant.ofEpochMilli(file.lastModified()),
                )
            }
            .sortedByDescending { it.modifiedAt }
    }

    private fun writePayload(file: File, bytes: ByteArray) {
        val parent = file.absoluteFile.parentFile ?: error("image has no parent directory")
        if (!parent.isDirectory) {
            check(parent.mkdirs() || parent.isDirectory) { "cannot create image directory" }
        }
        val temporary = File(parent, "${file.name}.part")
        try {
            ChunkedEncryptedFile.write(
                input = ByteArrayInputStream(bytes),
                target = temporary,
                magic = DATA_MAGIC,
                crypto = mediaKeyCrypto,
                version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
            )
            // The rename is the commit point: either the complete frame is
            // visible under the token or the previous state is untouched.
            replaceAtomically(temporary, file)
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

    private fun copyTo(file: File, output: OutputStream): Long =
        if (isChunkedFrame(file)) {
            ChunkedEncryptedFile.copyDecrypted(
                source = file,
                magic = DATA_MAGIC,
                cryptoFor = cryptoFor,
                output = output,
            ).sizeBytes
        } else {
            val plaintext = crypto.decrypt(file.readBytes())
            output.write(plaintext)
            plaintext.size.toLong()
        }

    /**
     * A v3 frame starts with the `IMAG` magic, a legacy ciphertext with its
     * AES-GCM IV length byte (12–16), so the two can never be confused. Only a
     * 4-byte prefix is read, so sniffing a large image never loads it.
     */
    private fun isChunkedFrame(file: File): Boolean {
        val header = ByteArray(MAGIC_BYTES)
        try {
            file.inputStream().use { input ->
                var read = 0
                while (read < MAGIC_BYTES) {
                    val count = input.read(header, read, MAGIC_BYTES - read)
                    // A FileInputStream never yields 0 here; treat it as
                    // unreadable rather than risk spinning on a stuck stream.
                    if (count <= 0) return false
                    read += count
                }
            }
        } catch (_: IOException) {
            return false
        }
        return DataInputStream(ByteArrayInputStream(header)).use { it.readInt() == DATA_MAGIC }
    }

    private fun ownerOf(personId: String?): String =
        personId?.takeIf { StorageLayout.PERSON_ID_PATTERN.matches(it) } ?: SELF_DIR

    private fun ownerDirectory(person: String): File =
        File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/$person")

    private fun tokenFor(person: String, fileName: String): String =
        "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/$person/$fileName"

    companion object {
        const val SELF_DIR = "_self"
        const val EXTENSION = ".enc"

        /** `数字遗产 / 图片 / <person> / <file>.enc` — nothing deeper, nothing else. */
        private const val IMAGE_PATH_SEGMENTS = 4

        /** Chunked-frame magic; `IMAG`. A legacy ciphertext never starts with it. */
        private const val DATA_MAGIC = 0x494D4147

        /** Size of the magic compared by [isChunkedFrame]: one `int`. */
        private const val MAGIC_BYTES = 4
    }
}
