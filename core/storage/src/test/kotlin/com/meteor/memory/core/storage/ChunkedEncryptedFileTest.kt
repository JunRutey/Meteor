package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer

class ChunkedEncryptedFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Reverses the bytes behind a 29-byte envelope, mirroring the real AES-GCM
     * overhead (12-byte nonce + 16-byte tag + 1 byte plaintext). Stateless, so
     * the parallel paths can call it from several threads.
     */
    private class ReversingCrypto : FileCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size + ENVELOPE_SIZE).also { encrypted ->
                plaintext.reversedArray().copyInto(encrypted, destinationOffset = ENVELOPE_SIZE)
            }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size >= ENVELOPE_SIZE)
            return ciphertext.copyOfRange(ENVELOPE_SIZE, ciphertext.size).reversedArray()
        }

        private companion object {
            const val ENVELOPE_SIZE = 29
        }
    }

    /** Fails on the chunk with the given index so the pool's error path is covered. */
    private class FailingCrypto(private val failFromIndex: Int) : FileCrypto {
        private val delegate = ReversingCrypto()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val index = ByteBuffer.wrap(plaintext).getInt()
            if (index >= failFromIndex) throw IllegalStateException("chunk encryption failed")
            return delegate.encrypt(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray = delegate.decrypt(ciphertext)
    }

    private val crypto = ReversingCrypto()

    private fun newFile(name: String): File = File(tmp.root, name)

    /** Deterministic but position-dependent bytes, so reordered chunks cannot match. */
    private fun payload(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun writeVersion(file: File): Int =
        DataInputStream(file.inputStream().buffered()).use {
            it.readInt()
            it.readInt()
        }

    private fun readAll(file: File, version: Int): Pair<ByteArray, List<Int>> {
        val selected = mutableListOf<Int>()
        val output = ByteArrayOutputStream()
        val stats = ChunkedEncryptedFile.copyDecrypted(
            source = file,
            magic = MAGIC,
            cryptoFor = { readVersion ->
                selected += readVersion
                crypto
            },
            output = output,
        )
        assertEquals(version, selected.single())
        assertEquals(output.size().toLong(), stats.sizeBytes)
        return output.toByteArray() to selected
    }

    @Test
    fun `version 1 legacy payloads are read through the version dispatch`() {
        val file = newFile("legacy-v1.enc")
        val source = payload(2 * ChunkedEncryptedFile.CHUNK_SIZE + 1024)
        val written = ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(source),
            target = file,
            magic = MAGIC,
            crypto = crypto,
        )

        assertEquals(ChunkedEncryptedFile.FORMAT_VERSION, writeVersion(file))
        assertEquals(3, written.chunkCount)
        assertEquals(source.size.toLong(), written.sizeBytes)

        val (decrypted, selected) = readAll(file, ChunkedEncryptedFile.FORMAT_VERSION)
        assertEquals(listOf(ChunkedEncryptedFile.FORMAT_VERSION), selected)
        assertArrayEquals(source, decrypted)
    }

    @Test
    fun `version 2 large-chunk payloads stay readable`() {
        val file = newFile("legacy-v2.enc")
        val source = payload(ChunkedEncryptedFile.LARGE_CHUNK_SIZE + 4096)
        val written = ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(source),
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION_LARGE_CHUNKS,
        )

        assertEquals(ChunkedEncryptedFile.FORMAT_VERSION_LARGE_CHUNKS, writeVersion(file))
        assertEquals(2, written.chunkCount)
        assertArrayEquals(source, readAll(file, ChunkedEncryptedFile.FORMAT_VERSION_LARGE_CHUNKS).first)
    }

    @Test
    fun `version 3 round trips and selects the media key crypto`() {
        val file = newFile("media-v3.enc")
        val source = payload(ChunkedEncryptedFile.LARGE_CHUNK_SIZE + 4096)
        val written = ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(source),
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
        )

        assertEquals(ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY, writeVersion(file))
        assertEquals(2, written.chunkCount)
        assertArrayEquals(source, readAll(file, ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY).first)
    }

    @Test
    fun `parallel encryption across three chunks keeps read order`() {
        val file = newFile("parallel-write.enc")
        // Two full chunks plus a tail: three chunk indices, so a reordered pool
        // queue would show up as a wrong chunk order or wrong content.
        val source = payload(2 * ChunkedEncryptedFile.LARGE_CHUNK_SIZE + 4096)

        val written = ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(source),
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
            parallelism = 2,
        )

        assertEquals(3, written.chunkCount)
        assertEquals(source.size.toLong(), written.sizeBytes)
        assertEquals(ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY, writeVersion(file))
        assertArrayEquals(source, readAll(file, ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY).first)
    }

    @Test
    fun `parallel decryption across three chunks keeps read order`() {
        val file = newFile("parallel-read.enc")
        val source = payload(2 * ChunkedEncryptedFile.LARGE_CHUNK_SIZE + 4096)
        ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(source),
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
        )

        val output = ByteArrayOutputStream()
        val stats = ChunkedEncryptedFile.copyDecrypted(
            source = file,
            magic = MAGIC,
            cryptoFor = { crypto },
            output = output,
            parallelism = 2,
        )

        assertEquals(3, stats.chunkCount)
        assertEquals(source.size.toLong(), stats.sizeBytes)
        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun `parallel encryption surfaces the worker failure without the future wrapper`() {
        val file = newFile("parallel-failure.enc")
        val source = payload(2 * ChunkedEncryptedFile.LARGE_CHUNK_SIZE + 4096)

        val failure = assertThrows(IllegalStateException::class.java) {
            ChunkedEncryptedFile.write(
                input = ByteArrayInputStream(source),
                target = file,
                magic = MAGIC,
                crypto = FailingCrypto(failFromIndex = 1),
                version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
                parallelism = 2,
            )
        }

        assertEquals("chunk encryption failed", failure.message)
    }

    @Test
    fun `openDecrypted dispatches on version and seeks across chunk boundaries`() {
        for (version in listOf(ChunkedEncryptedFile.FORMAT_VERSION, ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY)) {
            val chunkSize = when (version) {
                ChunkedEncryptedFile.FORMAT_VERSION -> ChunkedEncryptedFile.CHUNK_SIZE
                else -> ChunkedEncryptedFile.LARGE_CHUNK_SIZE
            }
            val file = newFile("stream-$version.enc")
            val source = payload(chunkSize + 1024)
            ChunkedEncryptedFile.write(
                input = ByteArrayInputStream(source),
                target = file,
                magic = MAGIC,
                crypto = crypto,
                version = version,
            )

            val selected = mutableListOf<Int>()
            val offset = chunkSize - 5L
            val streamed = ChunkedEncryptedFile.openDecrypted(
                source = file,
                magic = MAGIC,
                cryptoFor = { readVersion ->
                    selected += readVersion
                    crypto
                },
                offsetBytes = offset,
            ).use { it.readBytes() }

            assertEquals(listOf(version), selected)
            assertArrayEquals(source.copyOfRange(offset.toInt(), source.size), streamed)
        }
    }

    @Test
    fun `bytes after the end marker are rejected`() {
        val file = newFile("trailing.enc")
        ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(payload(1024)),
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
        )
        file.appendBytes(byteArrayOf(0x42))

        assertThrows(IllegalStateException::class.java) {
            ChunkedEncryptedFile.copyDecrypted(
                source = file,
                magic = MAGIC,
                cryptoFor = { crypto },
                output = ByteArrayOutputStream(),
            )
        }
    }

    // --- on-demand read buffer (ADR-067) ------------------------------------

    /**
     * Counts the frames on disk, which is how the read buffer's growth shows up:
     * a small payload must be exactly one frame, not one frame per refill.
     */
    private fun frameCount(file: File): Int {
        var frames = 0
        val scratch = ByteArray(8 * 1024)
        DataInputStream(file.inputStream().buffered()).use { input ->
            input.readInt()
            input.readInt()
            while (true) {
                val size = input.readInt()
                if (size == 0) break
                var remaining = size
                while (remaining > 0) {
                    val count = input.read(scratch, 0, minOf(remaining, scratch.size))
                    if (count < 0) error("truncated frame")
                    remaining -= count
                }
                frames++
            }
        }
        return frames
    }

    @Test
    fun `a payload smaller than one chunk is a single frame`() {
        val file = newFile("small.enc")
        // Well below the 10 MiB logical chunk size, but several times the initial
        // buffer: growth must not split the payload into one frame per refill.
        val source = payload(1536 * 1024)

        val written = ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(source),
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY,
            parallelism = 2,
        )

        assertEquals(1, written.chunkCount)
        assertEquals(1, frameCount(file))
        assertArrayEquals(source, readAll(file, ChunkedEncryptedFile.FORMAT_VERSION_MEDIA_KEY).first)
    }

    @Test
    fun `chunk boundaries stay at the logical chunk size`() {
        val file = newFile("boundaries.enc")
        val chunk = ChunkedEncryptedFile.CHUNK_SIZE
        val source = payload(chunk + 1)

        val written = ChunkedEncryptedFile.write(
            input = ByteArrayInputStream(source),
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION,
            parallelism = 2,
        )

        assertEquals(2, written.chunkCount)
        assertEquals(2, frameCount(file))
        assertArrayEquals(source, readAll(file, ChunkedEncryptedFile.FORMAT_VERSION).first)
    }

    @Test
    fun `a dribbling stream still fills whole chunks`() {
        val file = newFile("dribble.enc")
        // The 1 MiB format keeps this test fast; the growth logic is shared.
        val chunk = ChunkedEncryptedFile.CHUNK_SIZE
        val source = payload(chunk + 7)
        // One byte per read: the buffer must keep growing until a chunk is full.
        val dribble = object : java.io.InputStream() {
            private var index = 0
            override fun read(): Int = if (index < source.size) source[index++].toInt() and 0xFF else -1
        }

        val written = ChunkedEncryptedFile.write(
            input = dribble,
            target = file,
            magic = MAGIC,
            crypto = crypto,
            version = ChunkedEncryptedFile.FORMAT_VERSION,
        )

        assertEquals(2, written.chunkCount)
        assertArrayEquals(source, readAll(file, ChunkedEncryptedFile.FORMAT_VERSION).first)
    }

    private companion object {
        /** Mirrors the media store's magic; the parser never cares about the value. */
        const val MAGIC = 0x4D454449
    }
}
