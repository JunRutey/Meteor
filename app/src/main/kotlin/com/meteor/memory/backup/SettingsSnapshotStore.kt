package com.meteor.memory.backup

import android.util.AtomicFile
import com.meteor.memory.core.crypto.FileCrypto
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads and writes the encrypted settings snapshot inside the archive root
 * (ADR-040).
 *
 * The snapshot is encrypted with the same device-bound AES-GCM key as every
 * other archive file. [com.meteor.memory.core.storage.StorageRouter] therefore
 * exports it as plaintext JSON in the zip and re-encrypts it with the importing
 * device's key, exactly like media files.
 */
class SettingsSnapshotStore(
    private val file: File,
    private val crypto: FileCrypto,
    private val settingsRepository: SettingsRepository,
    private val appLog: AppLog,
) {

    /** Writes the current non-secret preferences; failure never blocks an export. */
    suspend fun write(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = SettingsSnapshotCodec.encode(settingsRepository.backupSnapshot())
            file.parentFile?.mkdirs()
            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()
            try {
                output.write(crypto.encrypt(json.toByteArray(Charsets.UTF_8)))
                atomicFile.finishWrite(output)
            } catch (failure: Exception) {
                atomicFile.failWrite(output)
                throw failure
            }
        }.onFailure { failure ->
            appLog.warn(TAG, "could not write settings snapshot", failure)
        }.isSuccess
    }

    /**
     * Removes the local snapshot before an import. The file is derived from the
     * current settings and rewritten on every export, so deleting it is safe;
     * it guarantees [restoreIfPresent] only sees a snapshot that actually came
     * from the imported archive.
     */
    suspend fun discardLocal() = withContext(Dispatchers.IO) {
        runCatching {
            if (file.isFile) file.delete()
        }.onFailure { failure ->
            appLog.warn(TAG, "could not remove local settings snapshot", failure)
        }
        Unit
    }

    /**
     * Applies a snapshot found in an imported archive, if any. Old backups do
     * not contain one, so a missing file is a normal no-op.
     */
    suspend fun restoreIfPresent(): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext false
        runCatching {
            val plaintext = crypto.decrypt(file.readBytes())
            val snapshot = SettingsSnapshotCodec.decode(String(plaintext, Charsets.UTF_8))
            settingsRepository.applyBackupSnapshot(snapshot)
        }.onFailure { failure ->
            appLog.warn(TAG, "could not restore settings snapshot", failure)
        }.isSuccess
    }

    private companion object {
        const val TAG = "SettingsSnapshot"
    }
}
