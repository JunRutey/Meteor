package com.meteor.memory.core.crypto

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Owns the media data key (ADR-063).
 *
 * A random 256-bit AES key is generated on first use and stored **wrapped** by
 * the Android Keystore key ([wrappingCrypto]). Media files are encrypted with
 * this key so the per-chunk AES work runs in this process — where it can be
 * parallelised — instead of inside the keystore daemon, which serialises every
 * Cipher call.
 *
 * Lifecycle: generated on the first [key] / [ensureGenerated] call, wrapped and
 * written once, then cached as a [SecretKey] handle for the process lifetime.
 * The key never leaves this object except as that handle for a Cipher: it is
 * not logged, shown, exported or backed up.
 *
 * A damaged key file raises an error instead of silently minting a replacement,
 * because a replacement would make every already-encrypted media file
 * permanently unreadable. The same is true for a file that cannot be unwrapped
 * (wrong keystore, truncated write): fail loudly, never overwrite.
 */
class MediaKeyStore(
    private val file: File,
    private val wrappingCrypto: FileCrypto,
) {

    private val lock = Any()

    @Volatile
    private var cached: SecretKey? = null

    /**
     * The media key, generated on first call and cached for the process lifetime.
     *
     * Thread-safe: concurrent first calls generate and wrap exactly one key.
     *
     * @throws IllegalStateException when an existing key file cannot be read or
     *   unwrapped; the file is left untouched in that case.
     */
    fun key(): SecretKey {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val loaded = loadOrCreate()
            cached = loaded
            return loaded
        }
    }

    /**
     * Startup warm-up: guarantees the key file exists (so the first media import
     * does not pay for generation) without handing the key out. Idempotent, and
     * it propagates the same corruption errors as [key].
     */
    fun ensureGenerated() {
        key()
    }

    private fun loadOrCreate(): SecretKey {
        if (file.isFile) return load()
        return generate()
    }

    private fun load(): SecretKey {
        val wrapped = try {
            file.readBytes()
        } catch (failure: IOException) {
            throw IllegalStateException("media key cannot be read", failure)
        }
        val plaintext = try {
            wrappingCrypto.decrypt(wrapped)
        } catch (failure: Exception) {
            // Never overwrite: the existing media depends on this exact key.
            throw IllegalStateException("media key cannot be unwrapped", failure)
        }
        try {
            check(plaintext.size == KEY_SIZE_BYTES) { "invalid media key length" }
            // Pass a copy and wipe the decrypted buffer below: SecretKeySpec
            // copies on Android/JDK, but never rely on another class's internals.
            return SecretKeySpec(plaintext.copyOf(), ALGORITHM)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun generate(): SecretKey {
        val generated = ByteArray(KEY_SIZE_BYTES)
        SecureRandom().nextBytes(generated)
        try {
            writeAtomically(wrappingCrypto.encrypt(generated))
            return SecretKeySpec(generated.copyOf(), ALGORITHM)
        } finally {
            generated.fill(0)
        }
    }

    /**
     * Writes through a sibling temp file so a crash cannot leave a half key.
     *
     * The rename is the commit point: either the complete wrapped key is visible
     * under [file] or the previous state is untouched.
     */
    private fun writeAtomically(wrapped: ByteArray) {
        val parent = file.absoluteFile.parentFile
        if (parent != null && !parent.isDirectory) {
            check(parent.mkdirs() || parent.isDirectory) { "cannot create media key directory" }
        }
        val temporary = File(parent, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(wrapped)
                output.flush()
                // Flush the wrapped key to disk before the rename so a power loss
                // cannot leave media_key.bin pointing at an empty/partial file.
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "cannot store media key" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        const val KEY_SIZE_BYTES = 32
        private const val ALGORITHM = "AES"
    }
}
