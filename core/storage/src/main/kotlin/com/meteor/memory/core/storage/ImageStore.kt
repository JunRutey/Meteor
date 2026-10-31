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
        val relative = token.trim().removePrefix("/")
        if (relative.contains("..")) return null
        val file = File(rootDir, relative)
        if (!file.isFile) return null
        return runCatching { crypto.decrypt(file.readBytes()) }.getOrNull()
    }

    override fun list(): List<StoredImage> {
        val imagesRoot = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}")
        if (!imagesRoot.isDirectory) return emptyList()

        return imagesRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .map { file ->
                StoredImage(
                    token = file.relativeTo(rootDir).invariantSeparatorsPath,
                    sizeBytes = file.length(),
                    modifiedAt = Instant.ofEpochMilli(file.lastModified()),
                )
            }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    companion object {
        const val SELF_DIR = "_self"
        const val EXTENSION = ".enc"
    }
}
