package com.meteor.memory.backup

import android.net.Uri
import com.meteor.memory.core.backup.BackupMeta
import com.meteor.memory.core.backup.BackupModule
import com.meteor.memory.core.backup.BackupResult
import com.meteor.memory.core.backup.ImportResult
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.ui.life.BirthdayProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-level [BackupModule] decorator that closes the v1.3.0 coverage gaps
 * without touching the frozen interface (ADR-040):
 *
 * - before an export/auto backup, move the legacy birth profile into the archive
 *   root and write the encrypted settings snapshot;
 * - after a successful import, apply the settings snapshot if the archive has
 *   one. Old backups simply do not contain it.
 *
 * The delegate (plaintext zip + rolling snapshots) is unchanged, so the zip
 * path structure and `listBackups` behaviour stay exactly as before.
 */
class SettingsSnapshotBackupModule(
    private val delegate: BackupModule,
    private val settingsSnapshotStore: SettingsSnapshotStore,
    private val birthdayProfileStore: BirthdayProfileStore,
    private val appLog: AppLog,
) : BackupModule {

    override suspend fun exportPlaintext(dest: Uri): BackupResult {
        prepareArchiveExtras()
        return delegate.exportPlaintext(dest)
    }

    override suspend fun importFrom(src: Uri): ImportResult {
        // Drop the local copy first: an old archive without a snapshot must not
        // be able to re-apply whatever this device exported earlier.
        settingsSnapshotStore.discardLocal()
        val result = delegate.importFrom(src)
        if (result is ImportResult.Success) {
            settingsSnapshotStore.restoreIfPresent()
        }
        return result
    }

    override suspend fun autoBackup(): BackupResult {
        prepareArchiveExtras()
        return delegate.autoBackup()
    }

    override fun listBackups(): List<BackupMeta> = delegate.listBackups()

    private suspend fun prepareArchiveExtras() {
        withContext(Dispatchers.IO) {
            runCatching { birthdayProfileStore.migrateLegacyIfNeeded() }
                .onFailure { failure ->
                    appLog.warn(TAG, "birth profile migration failed before backup", failure)
                }
        }
        // A failed snapshot write must not abort the export: the archive records
        // are more important, and the store already logs the failure.
        settingsSnapshotStore.write()
    }

    private companion object {
        const val TAG = "BackupExtras"
    }
}
