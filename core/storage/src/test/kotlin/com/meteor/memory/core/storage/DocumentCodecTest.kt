package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import com.meteor.memory.core.time.SystemTimeModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class DocumentCodecTest {

    /** Deterministic stand-in for AES-GCM: enough to verify framing, not crypto. */
    private class ReversingCrypto : FileCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.reversedArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.reversedArray()
    }

    private val crypto = ReversingCrypto()
    private val time = SystemTimeModule()
    private val timestamp = Instant.parse("2025-03-14T13:30:07Z")

    private fun header() = DocumentHeader(
        personId = "V1StGXR8_Z5jdHi6B-myT",
        type = RecordType.DIARY,
        timestamp = timestamp,
        source = RecordSource.USER,
    )

    private fun tempFile(): File = File.createTempFile("meteor", ".md").apply { deleteOnExit() }

    @Test
    fun `body is not readable on disk`() {
        val bytes = DocumentCodec.encode(header(), "今天很难过，但还好", crypto)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(!text.contains("今天很难过"))
    }

    @Test
    fun `round trips body and strips the signature`() {
        val stamped = time.stamp("今日记录", timestamp)
        val file = tempFile()
        val bytes = DocumentCodec.encode(header(), stamped, crypto)

        val doc = DocumentCodec.decode(file, bytes, crypto, time)

        assertEquals("今日记录", doc.body)
        assertTrue(!doc.body.contains("meteor-sig"))
        assertEquals(RecordType.DIARY, doc.type)
        assertEquals(timestamp, doc.timestamp)
        assertEquals("V1StGXR8_Z5jdHi6B-myT", doc.personId)
        // The watermark is recovered for the UI even though the body is stripped.
        assertEquals(timestamp, doc.signedAt)
    }

    @Test
    fun `plaintext export round trips and keeps readable body`() {
        val bytes = DocumentCodec.encodePlaintext(header(), "可读的正文")
        assertTrue(String(bytes, Charsets.UTF_8).contains("可读的正文"))

        val decoded = DocumentCodec.decodePlaintext(bytes)
        assertEquals("可读的正文", decoded!!.second)
        assertEquals(RecordType.DIARY, decoded.first.type)
    }

    @Test
    fun `decodePlaintext refuses an encrypted record`() {
        val bytes = DocumentCodec.encode(header(), "secret", crypto)
        assertNull(DocumentCodec.decodePlaintext(bytes))
    }

    @Test
    fun `decode refuses an exported plaintext record`() {
        val bytes = DocumentCodec.encodePlaintext(header(), "readable")
        assertThrows(StorageException::class.java) {
            DocumentCodec.decode(tempFile(), bytes, crypto, time)
        }
    }

    @Test
    fun `decode rejects foreign files`() {
        val stranger = "# not a meteor file\n".toByteArray()
        assertThrows(StorageException::class.java) {
            DocumentCodec.decode(tempFile(), stranger, crypto, time)
        }
    }

    @Test
    fun `same body encrypts differently because the sig is re-stamped`() {
        val a = time.stamp("same", timestamp)
        val b = time.stamp("same", timestamp.plusSeconds(1))
        assertNotEquals(a, b)
    }
}
