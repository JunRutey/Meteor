package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Authenticated chunk framing for large encrypted payloads (ADR-052).
 *
 * Wire format, mirroring the one [EncryptedAttachmentStore] writes:
 * ```
 * int magic | int version
 * repeat: int encryptedSize | encrypted( int chunkIndex | chunkBytes )
 * int 0
 * ```
 * Each chunk is encrypted on its own, so a multi-gigabyte video never has to sit
 * in memory and a corrupted chunk fails only that chunk. The layout is kept
 * byte-compatible in spirit with the attachment store, but callers pass their
 * own magic so a file can never be read through the wrong parser.
 */
internal object ChunkedEncryptedFile {

    const val FORMAT_VERSION = 1
    const val CHUNK_SIZE = 1024 * 1024

    /**
     * AES-256-GCM adds a 12-byte nonce and a 16-byte tag; a one-byte chunk is
     * therefore at least 29 bytes once encrypted. Anything shorter cannot have
     * come out of [FileCrypto].
     */
    const val MIN_ENCRYPTED_CHUNK_SIZE = 29
    const val MAX_ENCRYPTED_CHUNK_SIZE = CHUNK_SIZE + Int.SIZE_BYTES + 29

    private const val END_OF_DATA = 0

    /** Plaintext byte count and chunk count of a written or read payload. */
    data class Stats(val sizeBytes: Long, val chunkCount: Int)

    /** Streams [input] into [target] as encrypted chunks, replacing it atomically. */
    fun write(input: InputStream, target: File, magic: Int, crypto: FileCrypto): Stats {
        var index = 0
        var totalBytes = 0L
        DataOutputStream(target.outputStream().buffered()).use { output ->
            output.writeInt(magic)
            output.writeInt(FORMAT_VERSION)
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val count = readChunk(input, buffer)
                if (count == 0) break

                val frame = ByteBuffer.allocate(Int.SIZE_BYTES + count)
                    .putInt(index)
                    .put(buffer, 0, count)
                    .array()
                val encrypted = crypto.encrypt(frame)
                output.writeInt(encrypted.size)
                output.write(encrypted)
                totalBytes += count
                index++
            }
            output.writeInt(END_OF_DATA)
        }
        return Stats(sizeBytes = totalBytes, chunkCount = index)
    }

    /**
     * Decrypts [source] into [output], verifying the header, the chunk order and
     * that nothing trails the end marker.
     */
    fun copyDecrypted(source: File, magic: Int, crypto: FileCrypto, output: OutputStream): Stats {
        DataInputStream(source.inputStream().buffered()).use { input ->
            check(input.readInt() == magic) { "invalid encrypted chunk header" }
            check(input.readInt() == FORMAT_VERSION) { "unsupported encrypted chunk format" }

            var chunkIndex = 0
            var bytesCopied = 0L
            while (true) {
                val encryptedSize = input.readInt()
                if (encryptedSize == END_OF_DATA) break
                check(encryptedSize in MIN_ENCRYPTED_CHUNK_SIZE..MAX_ENCRYPTED_CHUNK_SIZE) {
                    "invalid encrypted chunk size"
                }

                val encrypted = ByteArray(encryptedSize)
                input.readFully(encrypted)
                val chunk = crypto.decrypt(encrypted)
                val chunkInput = DataInputStream(ByteArrayInputStream(chunk))
                check(chunkInput.readInt() == chunkIndex) { "encrypted chunk order is invalid" }
                val content = chunk.copyOfRange(Int.SIZE_BYTES, chunk.size)
                check(content.isNotEmpty() && content.size <= CHUNK_SIZE) { "invalid encrypted chunk" }
                output.write(content)
                bytesCopied += content.size
                chunkIndex++
            }

            check(input.read() == -1) { "unexpected bytes after encrypted chunk end" }
            return Stats(sizeBytes = bytesCopied, chunkCount = chunkIndex)
        }
    }

    /**
     * Opens a decrypted stream starting at [offsetBytes] inside the plaintext.
     *
     * Streaming playback (ADR-058) reads the payload front to back and seeks by
     * reopening at a byte offset, so a large video never has to be decrypted
     * into a full plaintext copy first. The chunk index is not stored on disk;
     * a seek scans the length prefixes (a few bytes per chunk) without
     * decrypting them, which keeps seeking into a large file cheap.
     */
    fun openDecrypted(
        source: File,
        magic: Int,
        crypto: FileCrypto,
        offsetBytes: Long,
    ): InputStream {
        require(offsetBytes >= 0) { "negative plaintext offset" }
        val input = DataInputStream(source.inputStream().buffered())
        try {
            check(input.readInt() == magic) { "invalid encrypted chunk header" }
            check(input.readInt() == FORMAT_VERSION) { "unsupported encrypted chunk format" }

            var chunkIndex = 0
            var remaining = offsetBytes
            while (remaining >= CHUNK_SIZE) {
                val encryptedSize = input.readInt()
                check(encryptedSize != END_OF_DATA) { "offset beyond the encrypted payload" }
                check(encryptedSize in MIN_ENCRYPTED_CHUNK_SIZE..MAX_ENCRYPTED_CHUNK_SIZE) {
                    "invalid encrypted chunk size"
                }
                skipFully(input, encryptedSize.toLong())
                remaining -= CHUNK_SIZE
                chunkIndex++
            }

            // The first chunk handed out starts [remaining] bytes into it.
            val first = readChunk(input, crypto, chunkIndex)
                ?: return EmptyInputStream().also { input.close() }
            val start = remaining.toInt()
            // An offset exactly at the end of the last chunk is a valid empty read.
            if (start >= first.size) return EmptyInputStream().also { input.close() }
            return DecryptingInputStream(
                input = input,
                crypto = crypto,
                buffer = if (start == 0) first else first.copyOfRange(start, first.size),
                chunkIndex = chunkIndex + 1,
            )
        } catch (failure: Exception) {
            runCatching { input.close() }
            throw failure
        }
    }

    private fun readChunk(input: DataInputStream, crypto: FileCrypto, expectedIndex: Int): ByteArray? {
        val encryptedSize = input.readInt()
        if (encryptedSize == END_OF_DATA) return null
        check(encryptedSize in MIN_ENCRYPTED_CHUNK_SIZE..MAX_ENCRYPTED_CHUNK_SIZE) {
            "invalid encrypted chunk size"
        }
        val encrypted = ByteArray(encryptedSize)
        input.readFully(encrypted)
        val chunk = crypto.decrypt(encrypted)
        val chunkInput = DataInputStream(ByteArrayInputStream(chunk))
        check(chunkInput.readInt() == expectedIndex) { "encrypted chunk order is invalid" }
        val content = chunk.copyOfRange(Int.SIZE_BYTES, chunk.size)
        check(content.isNotEmpty() && content.size <= CHUNK_SIZE) { "invalid encrypted chunk" }
        return content
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                check(input.read() >= 0) { "unexpected end of encrypted payload" }
                remaining -= 1
            }
        }
    }

    private class EmptyInputStream : InputStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    }

    /**
     * Decrypts chunks on demand; [buffer] already holds the first decrypted
     * chunk, trimmed to the requested offset.
     */
    private class DecryptingInputStream(
        private val input: DataInputStream,
        private val crypto: FileCrypto,
        private var buffer: ByteArray,
        private var chunkIndex: Int,
    ) : InputStream() {

        private var position = 0
        private var closed = false

        override fun read(): Int {
            if (!ensureBuffer()) return -1
            return buffer[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensureBuffer()) return -1
            val count = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, b, off, count)
            position += count
            return count
        }

        private fun ensureBuffer(): Boolean {
            while (position >= buffer.size) {
                val next = readChunk(input, crypto, chunkIndex) ?: return false
                buffer = next
                position = 0
                chunkIndex++
            }
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            input.close()
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[count++] = single.toByte()
            } else {
                count += read
            }
        }
        return count
    }
}
