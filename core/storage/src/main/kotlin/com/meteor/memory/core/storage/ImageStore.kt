package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Whole-file encrypted image storage.
 *
 * Images carry no frontmatter (archive §5.2): the directory decides ownership and
 * the filename is a random token, so nothing sensitive leaks through the path.
 */
/** One encrypted image on disk, addressed by its token. */
data class StoredImage(
    val token: String,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

interface ImageStore {

    /**
     * Encrypts and stores [bytes], returning a stable token usable inside
     * Markdown as `![](meteor://image/<token>)`.
     */
    fun save(bytes: ByteArray, personId: String?, at: Instant = Instant.now()): String

    /** Reads and decrypts the image for [token], or null when it is gone. */
    fun load(token: String): ByteArray?

    /** Every stored image, newest first — powers the standalone gallery. */
    fun list(): List<StoredImage>

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

class EncryptedImageStore(
    private val rootDir: File,
    private val crypto: FileCrypto,
) : ImageStore {

    override fun save(bytes: ByteArray, personId: String?, at: Instant): String {
        val person = personId?.takeIf { StorageLayout.PERSON_ID_PATTERN.matches(it) } ?: SELF_DIR
        val dir = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/$person")
        if (!dir.exists()) dir.mkdirs()

        // Random name: the timestamp would leak when a memory was written.
        val file = File(dir, "${UUID.randomUUID()}$EXTENSION")
        file.writeBytes(crypto.encrypt(bytes))
        return "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/$person/${file.name}"
    }

    override fun load(token: String): ByteArray? {
        val file = resolve(token) ?: return null
        if (!file.isFile) return null
        return runCatching { crypto.decrypt(file.readBytes()) }.getOrNull()
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

    companion object {
        const val SELF_DIR = "_self"
        const val EXTENSION = ".enc"

        /** `数字遗产 / 图片 / <person> / <file>.enc` — nothing deeper, nothing else. */
        private const val IMAGE_PATH_SEGMENTS = 4
    }
}
