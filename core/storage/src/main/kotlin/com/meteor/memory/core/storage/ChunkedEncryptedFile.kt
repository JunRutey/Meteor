package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Authenticated chunk framing for large encrypted payloads (ADR-052, ADR-063).
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
 *
 * The version in the header decides both the plaintext chunk size and, through
 * the caller's `cryptoFor`, which [FileCrypto] can read the file: v1/v2 payloads
 * were written with the Keystore key, v3 payloads with the in-process media data
 * key (ADR-063).
 */
internal object ChunkedEncryptedFile {

    /**
     * Original 1 MiB framing: every file written before ADR-062, and all
     * attachments (which stay on this version).
     */
    const val FORMAT_VERSION = 1

    /**
     * 10 MiB framing for media payloads encrypted with the Keystore key
     * (ADR-062). Read-only today: new media is written as
     * [FORMAT_VERSION_MEDIA_KEY] instead.
     */
    const val FORMAT_VERSION_LARGE_CHUNKS = 2

    /**
     * 10 MiB framing encrypted with the in-process media data key (ADR-063).
     *
     * Same chunk size as [FORMAT_VERSION_LARGE_CHUNKS]: only the key differs, so
     * the reader needs nothing but the version to pick a [FileCrypto]. New media
     * is written in this format; v1/v2 files stay readable.
     */
    const val FORMAT_VERSION_MEDIA_KEY = 3

    const val CHUNK_SIZE = 1024 * 1024

    /** Plaintext size of one chunk in the v2/v3 formats. */
    internal const val LARGE_CHUNK_SIZE = 10 * 1024 * 1024

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

    /**
     * Streams [input] into [target] as encrypted chunks.
     *
     * [parallelism] > 1 encrypts chunks on a bounded thread pool: this thread
     * only reads and reports, while `parallelism` workers call
     * [FileCrypto.encrypt]. A FIFO queue of futures holds at most
     * `parallelism` chunks, and the oldest one is written before the next chunk
     * is read, so the plaintext and ciphertext of a whole large video are never
     * buffered (peak memory stays around `(parallelism + 2) * chunkSize`).
     * Because the queue is FIFO, chunks reach the file in read order and the
     * on-disk chunk indices stay ascending. A worker failure is rethrown by
     * [Future.get] with its [ExecutionException] wrapper removed, and the pool
     * is always shut down.
     *
     * `FileCrypto.encrypt` must be safe to call concurrently; [AesGcmFileCrypto]
     * creates a fresh Cipher per call, so it is.
     */
    fun write(
        input: InputStream,
        target: File,
        magic: Int,
        crypto: FileCrypto,
        version: Int = FORMAT_VERSION,
        parallelism: Int = 1,
    ): Stats {
        require(parallelism >= 1) { "parallelism must be positive" }
        val chunkSize = chunkSizeFor(version)
        var index = 0
        var totalBytes = 0L
        DataOutputStream(target.outputStream().buffered()).use { output ->
            output.writeInt(magic)
            output.writeInt(version)
            val buffer = ByteArray(chunkSize)
            if (parallelism == 1) {
                while (true) {
                    val count = readChunk(input, buffer)
                    if (count == 0) break
                    val chunk = EncryptedChunk(index, crypto.encrypt(frameOf(index, buffer, count)))
                    writeFrame(output, chunk)
                    totalBytes += count
                    index++
                }
            } else {
                val pool = Executors.newFixedThreadPool(parallelism)
                try {
                    val pending = ArrayDeque<Future<EncryptedChunk>>()
                    var written = 0
                    while (true) {
                        val count = readChunk(input, buffer)
                        if (count == 0) break
                        val chunkIndex = index
                        // Copy out of the reused buffer before handing the chunk
                        // to another thread.
                        val frame = frameOf(chunkIndex, buffer, count)
                        pending.addLast(
                            pool.submit(Callable { EncryptedChunk(chunkIndex, crypto.encrypt(frame)) }),
                        )
                        totalBytes += count
                        index++
                        // Bounded pipeline: draining the oldest future before
                        // reading again caps the outstanding plaintext/ciphertext.
                        while (pending.size >= parallelism) {
                            written = drainEncrypted(output, pending.removeFirst(), written)
                        }
                    }
                    while (pending.isNotEmpty()) {
                        written = drainEncrypted(output, pending.removeFirst(), written)
                    }
                } finally {
                    pool.shutdown()
                }
            }
            output.writeInt(END_OF_DATA)
        }
        return Stats(sizeBytes = totalBytes, chunkCount = index)
    }

    /**
     * Decrypts [source] into [output], verifying the header, the chunk order and
     * that nothing trails the end marker.
     *
     * The header version selects the key through [cryptoFor], which is how old
     * v1/v2 media (Keystore key) and new v3 media (media data key) stay readable
     * side by side. [parallelism] > 1 decrypts chunks on a bounded pool while
     * writing them strictly in file order; otherwise chunks are handled on the
     * calling thread.
     */
    fun copyDecrypted(
        source: File,
        magic: Int,
        cryptoFor: (Int) -> FileCrypto,
        output: OutputStream,
        parallelism: Int = 1,
    ): Stats {
        require(parallelism >= 1) { "parallelism must be positive" }
        DataInputStream(source.inputStream().buffered()).use { input ->
            check(input.readInt() == magic) { "invalid encrypted chunk header" }
            val version = input.readInt()
            val crypto = cryptoFor(version)
            val chunkSize = chunkSizeFor(version)
            val maxEncryptedSize = maxEncryptedChunkSize(chunkSize)

            var chunkIndex = 0
            var bytesCopied = 0L
            if (parallelism == 1) {
                while (true) {
                    val encrypted = readEncryptedChunk(input, maxEncryptedSize) ?: break
                    val chunk = decryptChunk(crypto, encrypted, chunkIndex, chunkSize)
                    output.write(chunk.content)
                    bytesCopied += chunk.content.size
                    chunkIndex++
                }
            } else {
                val pool = Executors.newFixedThreadPool(parallelism)
                try {
                    val pending = ArrayDeque<Future<PlainChunk>>()
                    var written = 0
                    while (true) {
                        val encrypted = readEncryptedChunk(input, maxEncryptedSize) ?: break
                        val expectedIndex = chunkIndex
                        pending.addLast(
                            pool.submit(
                                Callable { decryptChunk(crypto, encrypted, expectedIndex, chunkSize) },
                            ),
                        )
                        chunkIndex++
                        while (pending.size >= parallelism) {
                            bytesCopied += drainPlain(output, pending.removeFirst(), written)
                            written++
                        }
                    }
                    while (pending.isNotEmpty()) {
                        bytesCopied += drainPlain(output, pending.removeFirst(), written)
                        written++
                    }
                } finally {
                    pool.shutdown()
                }
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
     *
     * The key is chosen from the header version via [cryptoFor], and decryption
     * stays sequential: a player only needs one chunk at a time.
     */
    fun openDecrypted(
        source: File,
        magic: Int,
        cryptoFor: (Int) -> FileCrypto,
        offsetBytes: Long,
    ): InputStream {
        require(offsetBytes >= 0) { "negative plaintext offset" }
        val input = DataInputStream(source.inputStream().buffered())
        try {
            check(input.readInt() == magic) { "invalid encrypted chunk header" }
            val version = input.readInt()
            val crypto = cryptoFor(version)
            val chunkSize = chunkSizeFor(version)
            val maxEncryptedSize = maxEncryptedChunkSize(chunkSize)

            var chunkIndex = 0
            var remaining = offsetBytes
            while (remaining >= chunkSize) {
                val encryptedSize = input.readInt()
                check(encryptedSize != END_OF_DATA) { "offset beyond the encrypted payload" }
                check(encryptedSize in MIN_ENCRYPTED_CHUNK_SIZE..maxEncryptedSize) {
                    "invalid encrypted chunk size"
                }
                skipFully(input, encryptedSize.toLong())
                remaining -= chunkSize
                chunkIndex++
            }

            // The first chunk handed out starts [remaining] bytes into it.
            val first = readChunk(input, crypto, chunkIndex, chunkSize, maxEncryptedSize)
                ?: return EmptyInputStream().also { input.close() }
            val start = remaining.toInt()
            // An offset exactly at the end of the last chunk is a valid empty read.
            if (start >= first.size) return EmptyInputStream().also { input.close() }
            return DecryptingInputStream(
                input = input,
                crypto = crypto,
                chunkSize = chunkSize,
                maxEncryptedSize = maxEncryptedSize,
                buffer = if (start == 0) first else first.copyOfRange(start, first.size),
                chunkIndex = chunkIndex + 1,
            )
        } catch (failure: Exception) {
            runCatching { input.close() }
            throw failure
        }
    }

    /**
     * Reads one encrypted frame. Returns null at the end marker; the size is
     * validated against the format's AES-GCM envelope before anything is
     * allocated, so a corrupted length prefix cannot request a huge buffer.
     */
    private fun readEncryptedChunk(input: DataInputStream, maxEncryptedSize: Int): ByteArray? {
        val encryptedSize = input.readInt()
        if (encryptedSize == END_OF_DATA) return null
        check(encryptedSize in MIN_ENCRYPTED_CHUNK_SIZE..maxEncryptedSize) {
            "invalid encrypted chunk size"
        }
        return ByteArray(encryptedSize).also { input.readFully(it) }
    }

    /**
     * Reads, decrypts and validates one chunk from an already-open reader; used
     * by [openDecrypted], where chunks are consumed one at a time.
     */
    private fun readChunk(
        input: DataInputStream,
        crypto: FileCrypto,
        expectedIndex: Int,
        chunkSize: Int,
        maxEncryptedSize: Int,
    ): ByteArray? {
        val encrypted = readEncryptedChunk(input, maxEncryptedSize) ?: return null
        return decryptChunk(crypto, encrypted, expectedIndex, chunkSize).content
    }

    /**
     * Decrypts one frame and validates the embedded chunk index and payload
     * size. Runs on a pool worker in the parallel path, so it only touches its
     * arguments and the thread-safe [FileCrypto].
     */
    private fun decryptChunk(
        crypto: FileCrypto,
        encrypted: ByteArray,
        expectedIndex: Int,
        chunkSize: Int,
    ): PlainChunk {
        val chunk = crypto.decrypt(encrypted)
        val chunkInput = DataInputStream(ByteArrayInputStream(chunk))
        check(chunkInput.readInt() == expectedIndex) { "encrypted chunk order is invalid" }
        val content = chunk.copyOfRange(Int.SIZE_BYTES, chunk.size)
        check(content.isNotEmpty() && content.size <= chunkSize) { "invalid encrypted chunk" }
        return PlainChunk(expectedIndex, content)
    }

    /** Writes the [expectedIndex]-th future's frame; guards the FIFO invariant. */
    private fun drainEncrypted(
        output: DataOutputStream,
        future: Future<EncryptedChunk>,
        expectedIndex: Int,
    ): Int {
        val chunk = await(future)
        check(chunk.index == expectedIndex) { "encrypted chunk order is invalid" }
        writeFrame(output, chunk)
        return expectedIndex + 1
    }

    /** Writes the [expectedIndex]-th future's plaintext; guards the FIFO invariant. */
    private fun drainPlain(output: OutputStream, future: Future<PlainChunk>, expectedIndex: Int): Long {
        val chunk = await(future)
        check(chunk.index == expectedIndex) { "encrypted chunk order is invalid" }
        output.write(chunk.content)
        return chunk.content.size.toLong()
    }

    /**
     * Blocks on a worker result and unwraps [ExecutionException]: callers must
     * see the real failure (e.g. a tampered chunk) instead of the pool wrapper.
     */
    private fun <T> await(future: Future<T>): T =
        try {
            future.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("parallel chunk work was interrupted", interrupted)
        }

    private fun writeFrame(output: DataOutputStream, chunk: EncryptedChunk) {
        output.writeInt(chunk.payload.size)
        output.write(chunk.payload)
    }

    private fun frameOf(index: Int, buffer: ByteArray, count: Int): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + count)
            .putInt(index)
            .put(buffer, 0, count)
            .array()

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
        private val chunkSize: Int,
        private val maxEncryptedSize: Int,
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
                val next = readChunk(input, crypto, chunkIndex, chunkSize, maxEncryptedSize)
                    ?: return false
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

    /** Plaintext chunk size implied by the on-disk format version. */
    private fun chunkSizeFor(version: Int): Int = when (version) {
        FORMAT_VERSION -> CHUNK_SIZE
        FORMAT_VERSION_LARGE_CHUNKS, FORMAT_VERSION_MEDIA_KEY -> LARGE_CHUNK_SIZE
        else -> error("unsupported encrypted chunk format $version")
    }

    /** AES-GCM envelope: 1-byte nonce length + nonce + tag, plus the chunk index. */
    private fun maxEncryptedChunkSize(chunkSize: Int): Int =
        chunkSize + Int.SIZE_BYTES + 29

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

    private class EncryptedChunk(val index: Int, val payload: ByteArray)

    private class PlainChunk(val index: Int, val content: ByteArray)
}
