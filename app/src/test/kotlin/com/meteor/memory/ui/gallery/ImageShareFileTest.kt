package com.meteor.memory.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun jpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)

    private fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0,
    )
}
