package com.meteor.memory.core.backup

import android.content.Context
import android.net.Uri
import com.meteor.memory.core.storage.StorageRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/** Supplies the retention count without making `:core-backup` depend on settings. */
fun interface BackupRetentionProvider {
    fun retention(): Int
}

/**
 * Implements the frozen [BackupModule] contract.
 *
 * Exports are plaintext zips (ADR-004): the Keystore key cannot leave the
 * device, so a ciphertext backup would be unreadable after a device change.
 * Imports re-encrypt every entry with the current device key.
 *
 * Rolling snapshots live in `filesDir/bak/` and only protect against in-app
 * deletion — they do not survive an uninstall (archive §7).
 */
class PlaintextBackupModule(
    private val context: Context,
    private val router: StorageRouter,
    private val retentionProvider: BackupRetentionProvider,
    private val clock: () -> Instant = Instant::now,
) : BackupModule {

    private val backupDir = File(context.filesDir, BACKUP_DIR_NAME)

    override suspend fun exportPlaintext(dest: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val (files, bytes) = countStoredFiles()
            router.exportTo(dest)
            BackupResult.Success(fileCount = files, bytes = bytes, at = clock()) as BackupResult
        }.getOrElse { BackupResult.Failure("导出失败：${it.message}", it) }
    }

    override suspend fun importFrom(src: Uri): ImportResult = withContext(Dispatchers.IO) {
        runCatching {
            val entries = countArchiveEntries(src)
            router.importFrom(src)
            ImportResult.Success(fileCount = entries, skipped = 0) as ImportResult
        }.getOrElse { ImportResult.Failure("导入失败：${it.message}", it) }
    }

    override suspend fun autoBackup(): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                return@runCatching BackupResult.Failure("无法创建备份目录") as BackupResult
            }

            val (files, bytes) = countStoredFiles()
            if (files == 0) {
                return@runCatching BackupResult.Failure("还没有可备份的内容") as BackupResult
            }

            val target = File(backupDir, fileNameFor(clock()))
            router.exportTo(Uri.fromFile(target))
            prune()

            BackupResult.Success(fileCount = files, bytes = bytes, at = clock()) as BackupResult
        }.getOrElse { BackupResult.Failure("自动备份失败：${it.message}", it) }
    }

    override fun listBackups(): List<BackupMeta> {
        if (!backupDir.isDirectory) return emptyList()
        return (backupDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".zip") }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                BackupMeta(
                    name = file.name,
                    createdAt = Instant.ofEpochMilli(file.lastModified()),
                    bytes = file.length(),
                )
            }
    }

    private fun prune() {
        val backups = (backupDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".zip") }
            .toList()

        BackupPruner.selectForDeletion(backups, retentionProvider.retention())
            .forEach { runCatching { it.delete() } }
    }

    private fun countStoredFiles(): Pair<Int, Long> {
        val root = File(context.filesDir, STORAGE_ROOT_NAME)
        if (!root.isDirectory) return 0 to 0L
        val files = root.walkTopDown().filter { it.isFile }.toList()
        return files.size to files.sumOf { it.length() }
    }

    private fun countArchiveEntries(uri: Uri): Int {
        val stream = context.contentResolver.openInputStream(uri) ?: return 0
        return stream.use { raw ->
            ZipInputStream(raw).use { zip ->
                var count = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) count++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                count
            }
        }
    }

    private fun fileNameFor(instant: Instant): String {
        val stamp = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(FILE_STAMP)
        return "meteor-$stamp.zip"
    }

    private companion object {
        const val BACKUP_DIR_NAME = "bak"

        /** Kept in sync with `:core-storage`'s StorageLayout.ROOT_NAME. */
        const val STORAGE_ROOT_NAME = "我的人生"

        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
