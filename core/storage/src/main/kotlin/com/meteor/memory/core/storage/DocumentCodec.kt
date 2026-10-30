package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import com.meteor.memory.core.time.TimeModule
import java.io.File
import java.util.Base64

/** Raised when a file cannot be read as a Meteor record. */
class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a [Document] into file bytes and back.
 *
 * The only place that knows the on-disk shape:
 *
 * ```
 * ---  five plaintext keys  ---
 * base64( AES-GCM( body + meteor-sig ) )
 * ```
 *
 * Pure JVM: no Android types, so it is fully unit-testable.
 */
object DocumentCodec {

    /** Encrypted record as stored in the private directory. */
    fun encode(header: DocumentHeader, body: String, crypto: FileCrypto): ByteArray {
        val ciphertext = crypto.encrypt(body.toByteArray(Charsets.UTF_8))
        val payload = Base64.getEncoder().encodeToString(ciphertext)
        return (Frontmatter.encode(header, Frontmatter.ENC_SCHEME) + payload + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Readable record as written into an exported backup zip. */
    fun encodePlaintext(header: DocumentHeader, body: String): ByteArray =
        (Frontmatter.encode(header, Frontmatter.PLAINTEXT_SCHEME) + body.trimEnd() + "\n")
            .toByteArray(Charsets.UTF_8)

    fun decode(file: File, bytes: ByteArray, crypto: FileCrypto, time: TimeModule): Document {
        val parsed = Frontmatter.split(String(bytes, Charsets.UTF_8))
            ?: throw StorageException("not a Meteor record: ${file.name}")
        if (parsed.encScheme != Frontmatter.ENC_SCHEME) {
            throw StorageException("unexpected scheme ${parsed.encScheme}: ${file.name}")
        }

        val ciphertext = runCatching { Base64.getDecoder().decode(parsed.payload) }
            .getOrElse { throw StorageException("invalid base64 payload: ${file.name}", it) }

        val plaintext = try {
            String(crypto.decrypt(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw StorageException("cannot decrypt: ${file.name}", e)
        }

        return Document(
            file = file,
            personId = parsed.header.personId,
            type = parsed.header.type,
            timestamp = parsed.header.timestamp,
            source = parsed.header.source,
            body = time.strip(plaintext),
        )
    }

    /** Decodes a plaintext export entry. @return null when it is not one. */
    fun decodePlaintext(bytes: ByteArray): Pair<DocumentHeader, String>? {
        val parsed = Frontmatter.split(String(bytes, Charsets.UTF_8)) ?: return null
        if (parsed.encScheme != Frontmatter.PLAINTEXT_SCHEME) return null
        return parsed.header to parsed.payload
    }
}
