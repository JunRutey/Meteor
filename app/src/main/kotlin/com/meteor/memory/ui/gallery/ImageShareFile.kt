package com.meteor.memory.ui.gallery

/**
 * Naming and MIME rules for the temporary file handed to the system share sheet.
 *
 * The image store only knows a random encrypted token, so the shared copy gets
 * a readable name derived from that token instead of the original filename
 * (which was never kept). Everything here is pure Kotlin so it can be unit
 * tested without Android or the filesystem.
 */
object ImageShareFile {

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Best-effort MIME type from the decrypted image bytes. */
    fun mimeType(bytes: ByteArray): String = when {
        bytes.startsWith(PNG_MAGIC) -> MIME_PNG
        bytes.startsWith(JPEG_MAGIC) -> MIME_JPEG
        bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> MIME_GIF
        bytes.startsWithAscii("RIFF") && bytes.regionEqualsAscii(8, "WEBP") -> MIME_WEBP
        bytes.startsWithAscii("BM") -> MIME_BMP
        bytes.startsWithAscii("II*\u0000") || bytes.startsWithAscii("MM\u0000*") -> MIME_TIFF
        bytes.isIsoBmff() -> isoBmffMime(bytes)
        else -> MIME_UNKNOWN
    }

    /**
     * Stable, filesystem-safe name for the cached copy.
     *
     * [timestampMs] keeps repeated shares of the same token from overwriting a
     * file a receiver may still be reading. Only letters, digits, `-` and `_`
     * survive, so a crafted token can never introduce a path separator.
     */
    fun fileName(token: String, mimeType: String, timestampMs: Long): String {
        val stem = token
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .take(MAX_STEM)
            .map { char ->
                if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
            }
            .joinToString("")
            .ifEmpty { DEFAULT_STEM }
        return "meteor-$timestampMs-$stem.${extensionFor(mimeType)}"
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        MIME_JPEG -> "jpg"
        MIME_PNG -> "png"
        MIME_GIF -> "gif"
        MIME_WEBP -> "webp"
        MIME_BMP -> "bmp"
        MIME_TIFF -> "tif"
        MIME_HEIC -> "heic"
        MIME_AVIF -> "avif"
        else -> "img"
    }

    private fun ByteArray.startsWith(magic: ByteArray): Boolean {
        if (size < magic.size) return false
        return magic.indices.all { this[it] == magic[it] }
    }

    private fun ByteArray.startsWithAscii(text: String): Boolean =
        regionEqualsAscii(0, text)

    private fun ByteArray.regionEqualsAscii(offset: Int, text: String): Boolean {
        if (offset < 0 || size < offset + text.length) return false
        return text.indices.all { this[offset + it] == text[it].code.toByte() }
    }

    private fun ByteArray.isIsoBmff(): Boolean = regionEqualsAscii(4, "ftyp")

    private fun isoBmffMime(bytes: ByteArray): String {
        if (bytes.size < 12) return MIME_UNKNOWN
        val brand = String(bytes, 8, 4, Charsets.US_ASCII)
        return when (brand) {
            "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> MIME_HEIC
            "avif", "avis" -> MIME_AVIF
            else -> MIME_UNKNOWN
        }
    }

    const val MIME_JPEG = "image/jpeg"
    const val MIME_PNG = "image/png"
    const val MIME_GIF = "image/gif"
    const val MIME_WEBP = "image/webp"
    const val MIME_BMP = "image/bmp"
    const val MIME_TIFF = "image/tiff"
    const val MIME_HEIC = "image/heic"
    const val MIME_AVIF = "image/avif"

    /** Unknown images are still shareable; the receiver sniffs the content. */
    const val MIME_UNKNOWN = "image/*"

    private const val MAX_STEM = 32
    private const val DEFAULT_STEM = "image"
}
