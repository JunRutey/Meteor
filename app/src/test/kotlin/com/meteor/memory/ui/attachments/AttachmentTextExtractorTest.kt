package com.meteor.memory.ui.attachments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AttachmentTextExtractorTest {

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
