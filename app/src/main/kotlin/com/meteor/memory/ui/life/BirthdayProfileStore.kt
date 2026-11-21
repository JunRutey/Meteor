package com.meteor.memory.ui.life

import android.util.AtomicFile
import com.meteor.memory.core.crypto.FileCrypto
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * AES-GCM encrypted birth profile (ADR-040).
 *
 * [file] lives inside the archive root so a plaintext export carries it. Older
 * builds stored the ciphertext directly under `filesDir`; [legacyFile] is
 * migrated once on first use and is kept as a read fallback until the copy has
 * been verified, so a failed migration never loses the birth date.
 */
class BirthdayProfileStore(
    private val file: File,
    private val crypto: FileCrypto,
    private val legacyFile: File? = null,
) {
    private val atomicFile = AtomicFile(file)
    private val lunarCalendar = ChineseLunarCalendar()

    /** Never treat the new file itself as the legacy source. */
    private val legacyCopy: File? =
        legacyFile?.takeIf { it.absolutePath != file.absolutePath }

    fun read(): BirthProfile? {
        // Fresh install with no legacy file: migration returns immediately and
        // nothing below creates the profile; null means "not set yet".
        migrateLegacyIfNeeded()
        val legacy = legacyCopy
        val encrypted = when {
            file.isFile -> try {
                atomicFile.openRead().use { it.readBytes() }
            } catch (_: FileNotFoundException) {
                return null
            }
            legacy != null && legacy.isFile -> legacy.readBytes()
            else -> return null
        }
        return lunarCalendar.normalize(BirthProfileCodec.decode(crypto.decrypt(encrypted)))
    }

    fun save(profile: BirthProfile) {
        // AtomicFile does not create directories, and on a fresh install the
        // archive's self-profile directory may not exist yet.
        ensureParentDirectory()
        val encrypted = crypto.encrypt(BirthProfileCodec.encode(profile))
        val output = atomicFile.startWrite()
        try {
            output.write(encrypted)
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
        // The new location is authoritative; the legacy copy is only a fallback.
        legacyCopy?.takeIf { it.isFile }?.let { runCatching { it.delete() } }
    }

    /**
     * Creates the profile's parent directory, throwing if the filesystem
     * refuses. Callers on the save path surface the failure to the user.
     */
    private fun ensureParentDirectory() {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        if (!parent.isDirectory) {
            throw IOException("cannot create birth profile directory: ${parent.absolutePath}")
        }
    }

    /**
     * One-time move of the pre-ADR-040 ciphertext into the archive root.
     *
     * Copies first and only deletes the old file after a byte-identical copy is
     * confirmed. Any failure leaves [legacyFile] in place; [read] still falls
     * back to it, so the birth date stays readable even when the copy fails.
     */
    fun migrateLegacyIfNeeded() {
        val legacy = legacyCopy ?: return
        if (!legacy.isFile) return
        if (!file.isFile) {
            runCatching {
                ensureParentDirectory()
                val bytes = legacy.readBytes()
                // Atomic copy: a killed process leaves no half-written profile
                // at the new path, so the legacy fallback below stays usable.
                val target = AtomicFile(file)
                val output = target.startWrite()
                try {
                    output.write(bytes)
                    target.finishWrite(output)
                } catch (failure: Exception) {
                    target.failWrite(output)
                    throw failure
                }
            }.getOrElse { return }
        }
        runCatching {
            if (file.length() == legacy.length() &&
                file.readBytes().contentEquals(legacy.readBytes())
            ) {
                legacy.delete()
            }
        }
    }
}
