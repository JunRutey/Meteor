package com.meteor.memory.ui.attachments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AttachmentTextExtractorTest {

    @Test
    fun `utf8 text is decoded`() {
        val expected = "\u4e2d\u6587\u6d4b\u8bd5"
        val extracted = AttachmentTextExtractor.extract(
            fileName = "text.txt",
            mimeType = "text/plain",
            input = ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8)),
        )

        assertEquals(expected, extracted)
    }

    @Test
    fun `gbk text is decoded through gb18030 fallback`() {
        val expected = "\u4e2d\u6587\u6d4b\u8bd5"
        val gbk = Charset.forName("GBK")
        val extracted = AttachmentTextExtractor.extract(
            fileName = "text.txt",
            mimeType = "text/plain",
            input = ByteArrayInputStream(expected.toByteArray(gbk)),
        )

        assertEquals(expected, extracted)
    }

    @Test
    fun `zip text entries are extracted in memory`() {
        val archive = zipOf(
            "chat.txt" to "hello",
            "metadata.json" to """{"name":"Ada"}""",
            "photo.jpg" to "not indexed",
        )

        val extracted = AttachmentTextExtractor.extract(
            fileName = "archive.zip",
            mimeType = "application/zip",
            input = ByteArrayInputStream(archive),
        ).orEmpty()

        assertTrue(extracted.contains("chat.txt"))
        assertTrue(extracted.contains("hello"))
        assertTrue(extracted.contains("metadata.json"))
        assertTrue(extracted.contains("Ada"))
        assertFalse(extracted.contains("not indexed"))
    }

    @Test
    fun `zip decompression is bounded across text and binary entries`() {
        val hugeBinary = "x".repeat(MAX_TEST_ARCHIVE_BYTES)
        val archive = zipOf(
            "movie.bin" to hugeBinary,
            "late.txt" to "must not be reached",
        )

        val extracted = AttachmentTextExtractor.extract(
            fileName = "archive.zip",
            mimeType = "application/zip",
            input = ByteArrayInputStream(archive),
        )

        assertTrue(extracted.isNullOrBlank())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private companion object {
        const val MAX_TEST_ARCHIVE_BYTES = 1024 * 1024 + 128
    }
}
