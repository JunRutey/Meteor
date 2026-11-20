package com.meteor.memory.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageShareFileTest {

    @Test
    fun `common image signatures map to a concrete mime type`() {
        assertEquals(ImageShareFile.MIME_JPEG, ImageShareFile.mimeType(jpeg()))
        assertEquals(ImageShareFile.MIME_PNG, ImageShareFile.mimeType(png()))
        assertEquals(ImageShareFile.MIME_GIF, ImageShareFile.mimeType("GIF89a".toByteArray()))
        assertEquals(
            ImageShareFile.MIME_WEBP,
            ImageShareFile.mimeType("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `heif brands are detected from the ftyp box`() {
        val heic = ByteArray(12).also {
            "ftyp".toByteArray().copyInto(it, 4)
            "heic".toByteArray().copyInto(it, 8)
        }
        assertEquals(ImageShareFile.MIME_HEIC, ImageShareFile.mimeType(heic))
    }

    @Test
    fun `unknown bytes still produce a shareable image mime type`() {
        assertEquals(ImageShareFile.MIME_UNKNOWN, ImageShareFile.mimeType(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `file name is sanitized and keeps the right extension`() {
        val name = ImageShareFile.fileName(
            token = "images/_self/evil name.enc",
            mimeType = ImageShareFile.MIME_PNG,
            timestampMs = 123L,
        )
        assertEquals("meteor-123-evil_name.png", name)
        assertFalse(name.contains('/'))
    }

    @Test
    fun `file name falls back to a stem when the token has none`() {
        val name = ImageShareFile.fileName("/", ImageShareFile.MIME_JPEG, 7L)
        assertEquals("meteor-7-image.jpg", name)
        assertTrue(name.endsWith(".jpg"))
    }

    // --- MIME detection beyond the common formats -------------------------

    @Test
    fun `empty bytes fall back to the generic image mime type`() {
        assertEquals(ImageShareFile.MIME_UNKNOWN, ImageShareFile.mimeType(ByteArray(0)))
    }

    @Test
    fun `bmp and tiff signatures are recognised in both byte orders`() {
        assertEquals(
            ImageShareFile.MIME_BMP,
            ImageShareFile.mimeType("BM\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)),
        )
        // II*\0 (little endian) and MM\0* (big endian) are the two classic TIFF headers.
        assertEquals(
            ImageShareFile.MIME_TIFF,
            ImageShareFile.mimeType(byteArrayOf(0x49, 0x49, 0x2A, 0x00)),
        )
        assertEquals(
            ImageShareFile.MIME_TIFF,
            ImageShareFile.mimeType("MM\u0000*".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `avif brands are detected from the ftyp box`() {
        assertEquals(ImageShareFile.MIME_AVIF, ImageShareFile.mimeType(isoBmff("avif")))
        assertEquals(ImageShareFile.MIME_AVIF, ImageShareFile.mimeType(isoBmff("avis")))
        assertEquals(ImageShareFile.MIME_HEIC, ImageShareFile.mimeType(isoBmff("hevc")))
        assertEquals(ImageShareFile.MIME_HEIC, ImageShareFile.mimeType(isoBmff("mif1")))
    }

    @Test
    fun `gif87a is recognised like gif89a`() {
        assertEquals(ImageShareFile.MIME_GIF, ImageShareFile.mimeType("GIF87a".toByteArray()))
    }

    /**
     * A signature is only useful when it is complete. Short or look-alike
     * headers must stay on the `image/*` fallback instead of claiming a format
     * the receiver would then fail to decode.
     */
    @Test
    fun `truncated or look-alike signatures stay on the generic mime type`() {
        // JPEG needs all three bytes; 0xFF 0xD8 alone is not enough.
        assertEquals(
            ImageShareFile.MIME_UNKNOWN,
            ImageShareFile.mimeType(byteArrayOf(0xFF.toByte(), 0xD8.toByte())),
        )
        // RIFF container that is not WebP (e.g. a WAV file).
        assertEquals(
            ImageShareFile.MIME_UNKNOWN,
            ImageShareFile.mimeType("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.US_ASCII)),
        )
        // ftyp box shorter than the 12 bytes needed to hold a brand.
        assertEquals(ImageShareFile.MIME_UNKNOWN, ImageShareFile.mimeType(isoBmff("avif", size = 8)))
        // ftyp box with a brand we do not know.
        assertEquals(ImageShareFile.MIME_UNKNOWN, ImageShareFile.mimeType(isoBmff("abcd")))
    }

    // --- File naming ------------------------------------------------------

    @Test
    fun `every mime type maps to its own file extension`() {
        val expected = listOf(
            ImageShareFile.MIME_JPEG to "jpg",
            ImageShareFile.MIME_PNG to "png",
            ImageShareFile.MIME_GIF to "gif",
            ImageShareFile.MIME_WEBP to "webp",
            ImageShareFile.MIME_BMP to "bmp",
            ImageShareFile.MIME_TIFF to "tif",
            ImageShareFile.MIME_HEIC to "heic",
            ImageShareFile.MIME_AVIF to "avif",
            ImageShareFile.MIME_UNKNOWN to "img",
        )
        expected.forEach { (mimeType, extension) ->
            assertEquals(
                "extension for $mimeType",
                "meteor-1-token.$extension",
                ImageShareFile.fileName(token = "token", mimeType = mimeType, timestampMs = 1L),
            )
        }
        assertEquals(
            "extensions must not collide",
            expected.size,
            expected.map { it.second }.toSet().size,
        )
    }

    @Test
    fun `percent signs in the token cannot reach the file name`() {
        val name = ImageShareFile.fileName(
            token = "images/2026/summer%20day.enc",
            mimeType = ImageShareFile.MIME_PNG,
            timestampMs = 1000L,
        )
        assertEquals("meteor-1000-summer_20day.png", name)
        assertFalse(name.contains('%'))
    }

    @Test
    fun `only the last path segment of the token is kept`() {
        val name = ImageShareFile.fileName("a/b/c.enc", ImageShareFile.MIME_JPEG, 1L)
        assertEquals("meteor-1-c.jpg", name)
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
    }

    @Test
    fun `dots in the token are flattened so the extension stays unambiguous`() {
        val name = ImageShareFile.fileName("a.b.png", ImageShareFile.MIME_WEBP, 4L)
        assertEquals("meteor-4-a_b.webp", name)
        assertFalse(name.dropLast(".webp".length).contains('.'))
    }

    @Test
    fun `long tokens are truncated to the stem limit`() {
        val stem = "a".repeat(32)
        val name = ImageShareFile.fileName(stem + "a".repeat(8), ImageShareFile.MIME_PNG, 5L)
        assertEquals("meteor-5-$stem.png", name)
    }

    /** Truncation runs first, so characters past the limit never become `_`. */
    @Test
    fun `truncation happens before sanitising`() {
        val stem = "b".repeat(32)
        val name = ImageShareFile.fileName(stem + "%c", ImageShareFile.MIME_PNG, 9L)
        assertEquals("meteor-9-$stem.png", name)
        assertFalse(name.contains('_'))
    }

    @Test
    fun `a token that is only an extension falls back to the default stem`() {
        assertEquals(
            "meteor-3-image.png",
            ImageShareFile.fileName(".enc", ImageShareFile.MIME_PNG, 3L),
        )
        assertEquals(
            "meteor-1-image.png",
            ImageShareFile.fileName("", ImageShareFile.MIME_PNG, 1L),
        )
    }

    @Test
    fun `the timestamp is part of the file name`() {
        val name = ImageShareFile.fileName("abc", ImageShareFile.MIME_JPEG, 1_700_000_000_123L)
        assertEquals("meteor-1700000000123-abc.jpg", name)
        assertTrue(name.contains("1700000000123"))
    }

    /** Two shares of the same token must not overwrite a file a receiver still reads. */
    @Test
    fun `different timestamps produce different file names for one token`() {
        val first = ImageShareFile.fileName("abc", ImageShareFile.MIME_JPEG, 1L)
        val second = ImageShareFile.fileName("abc", ImageShareFile.MIME_JPEG, 2L)
        assertNotEquals(first, second)
    }

    private fun isoBmff(brand: String, size: Int = 12): ByteArray {
        val bytes = ByteArray(size)
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
        if (size >= 12) brand.toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        return bytes
    }

    private fun jpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)

    private fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0,
    )
}
