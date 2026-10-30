package com.meteor.memory.core.backup

import android.net.Uri
import java.time.Instant

/** Outcome of a plaintext export. */
sealed interface BackupResult {
    data class Success(val fileCount: Int, val bytes: Long, val at: Instant) : BackupResult
    data class Failure(val message: String, val cause: Throwable? = null) : BackupResult
}

/** Outcome of an import. Every file is re-encrypted with this device's key. */
sealed interface ImportResult {
    data class Success(val fileCount: Int, val skipped: Int) : ImportResult
    data class Failure(val message: String, val cause: Throwable? = null) : ImportResult
}

/** Metadata for one rolling snapshot under `filesDir/bak/`. */
data class BackupMeta(
    val name: String,
    val createdAt: Instant,
    val bytes: Long,
)

/**
 * FROZEN INTERFACE (Meteor archive §7). Do not modify without an ADR.
 *
 * Exports are deliberately plaintext: the Keystore key is device-bound and
 * cannot migrate, so plaintext zip is the only path to a new device.
 * The UI must warn the user before writing one.
 */
interface BackupModule {
    suspend fun exportPlaintext(dest: Uri): BackupResult
    suspend fun importFrom(src: Uri): ImportResult
    suspend fun autoBackup(): BackupResult
    fun listBackups(): List<BackupMeta>
}
