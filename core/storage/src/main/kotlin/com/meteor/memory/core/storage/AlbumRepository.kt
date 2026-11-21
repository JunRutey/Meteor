package com.meteor.memory.core.storage

import java.io.OutputStream
import java.time.Instant

/** Which albums to read: everything, only the archive owner, or one person. */
sealed interface MediaOwner {
    data object All : MediaOwner
    data object Self : MediaOwner
    data class Person(val id: String) : MediaOwner
}

/**
 * What one album entry is.
 *
 * [IMAGE]/[VIDEO]/[AUDIO] come from the media and legacy image stores. [FILE] is
 * an imported document from [AttachmentStore] (ADR-065): the former 记录 page
 * merged into the album, so one grid lists every imported thing. A file has no
 * thumbnail and no player — only the text extracted at import.
 */
enum class AlbumKind { IMAGE, VIDEO, AUDIO, FILE }

/** The album's sort switch (ADR-052 §5 C): source date or import date. */
enum class AlbumSortMode { SOURCE_DATE, IMPORT_DATE }

/**
 * One grid item in the album, regardless of whether it came from the new media
 * store, the legacy image store or the attachment store.
 *
 * [ref] is the full Markdown URL, i.e. `meteor://media/<id>`, `meteor://image/<token>`
 * or `meteor://file/<id>`, so the same value can be fed back into
 * [AlbumRepository.entry], [MediaRef.parse] and the viewer route.
 */
data class AlbumEntry(
    val ref: String,
    val kind: AlbumKind,
    val personId: String?,
    val fileName: String?,
    val mimeType: String?,
    val sourceDate: Instant?,
    val importedAt: Instant,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val hasThumbnail: Boolean,
    /** Byte size of an imported file; null for media and legacy images. */
    val sizeBytes: Long? = null,
)

/**
 * Read side of the album: merges the new encrypted media store, the legacy
 * whole-file image store and (ADR-065) the imported-file store, and owns the only
 * two sort orders the UI may use.
 *
 * Legacy images are never migrated (ADR-052 §2): they are listed in place with
 * `sourceDate = null`, their file mtime as `importedAt`, and the full
 * `meteor://image/<token>` reference.
 */
class AlbumRepository(
    private val media: MediaStore,
    private val images: ImageStore,
    /**
     * Imported documents, merged into the same grid (ADR-065). Null keeps the
     * album media-only, which is what a host without an attachment store sees.
     */
    private val attachments: AttachmentStore? = null,
) {

    fun entries(owner: MediaOwner = MediaOwner.All, kinds: Set<AlbumKind>? = null): List<AlbumEntry> {
        val belongs = ownerFilter(owner)
        val wanted = kinds ?: ALL_KINDS

        // Both stores are read in full and filtered here: `MediaStore.list(null)`
        // means "every owner" and `ImageStore.list()` has no owner argument at
        // all, so a local filter is the only shape that covers both.
        val stored = media.list()
            .asSequence()
            .filter { item -> item.kind.toAlbumKind() in wanted && belongs(item.personId) }
            .map { item -> item.toAlbumEntry() }

        val legacy = images.list()
            .asSequence()
            .mapNotNull { image -> image.toAlbumEntry() }
            .filter { entry -> entry.kind in wanted && belongs(entry.personId) }

        // `attachments` is nullable: spell the empty case out so the receiver is
        // a `List<StoredAttachment>` and `filter` cannot resolve to an array
        // overload.
        val importedFiles: List<StoredAttachment> = attachments?.list().orEmpty()
        val imported = importedFiles
            .asSequence()
            .filter { AlbumKind.FILE in wanted && belongs(it.personId) }
            .map { it.toAlbumEntry() }

        return (stored + legacy + imported).toList().sortedByDescending { it.importedAt }
    }

    /** SOURCE_DATE falls back to the import date; both orders are newest first. */
    fun sorted(entries: List<AlbumEntry>, mode: AlbumSortMode): List<AlbumEntry> = when (mode) {
        AlbumSortMode.SOURCE_DATE -> entries.sortedByDescending { it.sourceDate ?: it.importedAt }
        AlbumSortMode.IMPORT_DATE -> entries.sortedByDescending { it.importedAt }
    }

    fun entry(ref: String): AlbumEntry? = when (val parsed = MediaRef.parse(ref)) {
        null -> null
        is MediaRef.Media -> media.find(parsed.id)?.toAlbumEntry()
        is MediaRef.LegacyImage -> legacyEntry(parsed.token)
        is MediaRef.File -> fileEntry(parsed.id)
    }

    /** Whole-file bytes; only for small payloads such as images and thumbnails. */
    fun loadBytes(ref: String): ByteArray? = when (val parsed = MediaRef.parse(ref)) {
        null -> null
        is MediaRef.Media -> media.load(parsed.id)
        is MediaRef.LegacyImage -> images.load(parsed.token)
        // An imported file is streamed through copyDecrypted; buffering a
        // 256 MB attachment in the heap would be exactly what the store avoids.
        is MediaRef.File -> null
    }

    /** Streams plaintext to [output]; returns the byte count, or null for a bad ref. */
    fun copyDecrypted(ref: String, output: OutputStream): Long? = when (val parsed = MediaRef.parse(ref)) {
        null -> null
        is MediaRef.Media -> runCatching { media.copyDecrypted(parsed.id, output) }.getOrNull()
        is MediaRef.LegacyImage -> images.load(parsed.token)?.let { bytes ->
            output.write(bytes)
            bytes.size.toLong()
        }
        is MediaRef.File -> runCatching { attachments?.copyDecrypted(parsed.id, output) }.getOrNull()
    }

    fun delete(ref: String): Boolean = when (val parsed = MediaRef.parse(ref)) {
        null -> false
        is MediaRef.Media -> media.delete(parsed.id)
        is MediaRef.LegacyImage -> images.delete(parsed.token)
        is MediaRef.File -> attachments?.delete(parsed.id) ?: false
    }

    /** Decrypted text extracted at import for a `meteor://file/` ref (ADR-065). */
    fun readFileText(ref: String): String? = when (val parsed = MediaRef.parse(ref)) {
        is MediaRef.File -> attachments?.readIndexedText(parsed.id)
        else -> null
    }

    /**
     * A `null` person id means the archive owner, so `MediaOwner.Self` and
     * `MediaOwner.All` cannot both be expressed as "personId == null".
     */
    private fun ownerFilter(owner: MediaOwner): (String?) -> Boolean = when (owner) {
        MediaOwner.All -> { _ -> true }
        MediaOwner.Self -> { personId -> personId == null }
        is MediaOwner.Person -> personFilter(owner.id)
    }

    private fun personFilter(personId: String): (String?) -> Boolean {
        require(StorageLayout.PERSON_ID_PATTERN.matches(personId)) { "illegal person_id: $personId" }
        return { ownerId -> ownerId == personId }
    }

    /** One legacy image looked up by its token; null when it is gone or unsafe. */
    private fun legacyEntry(token: String): AlbumEntry? {
        val owner = legacyOwner(token) ?: return null
        val image = images.list().firstOrNull { it.token == token } ?: return null
        return image.toAlbumEntry(owner.personId)
    }

    /** One imported file looked up by its id; null when it is gone. */
    private fun fileEntry(id: String): AlbumEntry? =
        attachments?.list()?.firstOrNull { it.id == id }?.toAlbumEntry()

    private fun MediaKind.toAlbumKind(): AlbumKind = when (this) {
        MediaKind.IMAGE -> AlbumKind.IMAGE
        MediaKind.VIDEO -> AlbumKind.VIDEO
        MediaKind.AUDIO -> AlbumKind.AUDIO
    }

    private fun StoredMedia.toAlbumEntry() = AlbumEntry(
        ref = "${MediaStore.MEDIA_SCHEME}$id",
        kind = kind.toAlbumKind(),
        personId = personId,
        fileName = fileName,
        mimeType = mimeType,
        sourceDate = sourceDate,
        importedAt = importedAt,
        width = width,
        height = height,
        durationMs = durationMs,
        hasThumbnail = hasThumbnail,
    )

    /**
     * An imported document as a grid entry.
     *
     * `sourceDate` stays null: the file's own timestamp is not trustworthy (a
     * chat export keeps its export date), so it sorts by import date like the
     * rest of the album. `hasThumbnail` is false — there is no picture to draw.
     */
    private fun StoredAttachment.toAlbumEntry() = AlbumEntry(
        ref = "${MediaRef.FILE_SCHEME}$id",
        kind = AlbumKind.FILE,
        personId = personId,
        fileName = fileName,
        mimeType = mimeType,
        sourceDate = null,
        importedAt = modifiedAt,
        width = null,
        height = null,
        durationMs = null,
        hasThumbnail = false,
        sizeBytes = sizeBytes,
    )

    private fun StoredImage.toAlbumEntry(personId: String?) = AlbumEntry(
        ref = "${ImageStore.IMAGE_SCHEME}$token",
        kind = AlbumKind.IMAGE,
        personId = personId,
        fileName = null,
        mimeType = null,
        sourceDate = null,
        importedAt = modifiedAt,
        width = null,
        height = null,
        durationMs = null,
        hasThumbnail = false,
    )

    private fun StoredImage.toAlbumEntry(): AlbumEntry? {
        val owner = legacyOwner(token) ?: return null
        return toAlbumEntry(owner.personId)
    }

    /**
     * Owner segment of a legacy image token, or null when the token is not
     * exactly `数字遗产/图片/<owner>/<file>.enc`.
     *
     * A token that escapes the image tree is dropped rather than listed,
     * mirroring [EncryptedImageStore.load]; `_self` yields a null `personId`.
     */
    private fun legacyOwner(token: String): LegacyOwner? {
        val parts = token.trim().removePrefix("/").replace('\\', '/').split('/')
        if (parts.size != IMAGE_PATH_SEGMENTS) return null
        if (parts[0] != StorageLayout.LEGACY || parts[1] != StorageLayout.IMAGES) return null
        val fileName = parts[3]
        if (fileName.length <= LEGACY_IMAGE_EXTENSION.length ||
            !fileName.endsWith(LEGACY_IMAGE_EXTENSION)
        ) {
            return null
        }
        return when (val owner = parts[2]) {
            EncryptedImageStore.SELF_DIR -> LegacyOwner(null)
            else -> owner.takeIf(StorageLayout.PERSON_ID_PATTERN::matches)?.let(::LegacyOwner)
        }
    }

    /** Distinguishes "owned by self" (null id) from "not an image token" (null result). */
    private class LegacyOwner(val personId: String?)

    private companion object {
        val ALL_KINDS: Set<AlbumKind> = AlbumKind.entries.toSet()

        /** `数字遗产 / 图片 / <owner> / <file>.enc` — nothing deeper, nothing else. */
        const val IMAGE_PATH_SEGMENTS = 4
        const val LEGACY_IMAGE_EXTENSION = EncryptedImageStore.EXTENSION
    }
}
