package com.meteor.memory.core.backup

import android.content.Context
import android.net.Uri
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.core.storage.StorageLayout
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
 *
 * [mediaStore] is optional and purely additive (ADR-057). While it is null, or
 * holds no media, every path below behaves exactly as it did before media
 * existed — same zip structure, same entry count, same import semantics. Once it
 * holds media, `数字遗产/媒体/` is decrypted into the archive and described by
 * `设置/media-manifest.json`, because the encrypted sidecars cannot survive a
 * device change and their ciphertext would be worthless on the new one.
 */
class PlaintextBackupModule(
    private val context: Context,
    private val router: StorageRouter,
    private val retentionProvider: BackupRetentionProvider,
    private val clock: () -> Instant = Instant::now,
    private val mirror: ExternalBackupMirror = NoExternalBackupMirror,
    private val mediaStore: MediaStore? = null,
) : BackupModule {

    private val backupDir = File(context.filesDir, BACKUP_DIR_NAME)

    override suspend fun exportPlaintext(dest: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val stats = writeArchive(dest)
            BackupResult.Success(fileCount = stats.fileCount, bytes = stats.bytes, at = clock()) as BackupResult
        }.getOrElse { failure ->
            // The router aborts on the first bad file; the entry it had already
            // written is not rolled back by the zip stream, so drop the file.
            discardPartialArchive(dest)
            BackupResult.Failure("导出失败：${failure.message}", failure)
        }
    }

    override suspend fun importFrom(src: Uri): ImportResult = withContext(Dispatchers.IO) {
        runCatching { readArchive(src) }
            .getOrElse { ImportResult.Failure("导入失败：${it.message}", it) }
    }

    override suspend fun autoBackup(): BackupResult = withContext(Dispatchers.IO) {
        val target = File(backupDir, fileNameFor(clock()))
        runCatching {
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                return@runCatching BackupResult.Failure("无法创建备份目录") as BackupResult
            }

            val (files, _) = countStoredFiles()
            if (files == 0) {
                return@runCatching BackupResult.Failure("还没有可备份的内容") as BackupResult
            }

            // Same path as a manual export, so a rolling snapshot carries media too.
            val stats = writeArchive(Uri.fromFile(target))
            prune()

            // Second destination is a reserved extension point; today the
            // no-op reports "not configured" and nothing else happens.
            runCatching { mirror.mirror(target) }

            BackupResult.Success(fileCount = stats.fileCount, bytes = stats.bytes, at = clock()) as BackupResult
        }.getOrElse { failure ->
            // A truncated zip must not stay in `bak/` where listBackups() would
            // offer it as a restorable snapshot.
            discardPartialArchive(Uri.fromFile(target))
            BackupResult.Failure("自动备份失败：${failure.message}", failure)
        }
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

    // --- media-aware paths (ADR-057) -----------------------------------------

    /**
     * True only when media support is wired **and** something is stored.
     *
     * Both halves matter for backward compatibility: an archive exported from a
     * device with no media must not gain an empty manifest, or its structure would
     * silently differ from every pre-ADR-052 backup.
     */
    private fun hasMedia(): Boolean {
        val store = mediaStore ?: return false
        return runCatching { store.list().isNotEmpty() }.getOrDefault(false)
    }

    /**
     * Writes the archive, going through the media rewriter only when needed.
     *
     * The router has no notion of media and keeps its frozen behaviour, so the
     * media path first produces a complete, correct archive in the cache and then
     * replaces just the `数字遗产/媒体/` part of it.
     */
    private suspend fun writeArchive(dest: Uri): ArchiveStats {
        val store = mediaStore
        if (store == null || !hasMedia()) {
            val (files, bytes) = countStoredFiles()
            router.exportTo(dest)
            return ArchiveStats(files, bytes)
        }

        val staging = scratchFile(STAGING_ARCHIVE)
        try {
            router.exportTo(Uri.fromFile(staging))
            val output = context.contentResolver.openOutputStream(dest)
                ?: throw IllegalStateException("cannot open export destination")
            val stats = output.use { target -> MediaArchiveExporter(store).export(staging, target) }
            return ArchiveStats(stats.fileCount, stats.bytes)
        } finally {
            staging.delete()
        }
    }

    /**
     * Imports an archive, restoring media when — and only when — it carries a
     * manifest. Archives without one take the original code path byte for byte.
     */
    private suspend fun readArchive(src: Uri): ImportResult {
        val store = mediaStore ?: return legacyImport(src)

        // ZipFile needs random access, but a picked archive arrives as a stream.
        val archive = copyToScratch(src) ?: return legacyImport(src)
        try {
            val importer = MediaArchiveImporter(store)
            val manifest = importer.readManifest(archive)
            return if (manifest == null) {
                // Old backup: untouched behaviour, no media path at all. The scratch
                // copy is a faithful byte copy, so importing from it is equivalent
                // to importing from the picked Uri (and avoids re-opening a
                // one-shot provider stream).
                legacyImport(Uri.fromFile(archive))
            } else {
                restoreWithMedia(archive, importer, manifest)
            }
        } finally {
            archive.delete()
        }
    }

    /** Records first, then media — both from the same verified archive file. */
    private suspend fun restoreWithMedia(
        source: File,
        importer: MediaArchiveImporter,
        manifest: String,
    ): ImportResult {
        val stripped = scratchFile(STRIPPED_ARCHIVE)
        try {
            val strip = importer.stripMedia(source, stripped, manifest)
            // The router sees a copy that contains no media entry, so it can never
            // mistake a decrypted payload for ciphertext and encrypt it again.
            router.importFrom(Uri.fromFile(stripped))
            val restored = importer.restore(source, strip.entries)
            return ImportResult.Success(
                fileCount = strip.fileCount + restored.restored,
                skipped = restored.skipped,
            )
        } finally {
            stripped.delete()
        }
    }

    /** The pre-ADR-057 import path, untouched. */
    private suspend fun legacyImport(src: Uri): ImportResult {
        val entries = countArchiveEntries(src)
        router.importFrom(src)
        return ImportResult.Success(fileCount = entries, skipped = 0)
    }

    /** Copies a content Uri into cache so its entries can be looked up by name. */
    private fun copyToScratch(uri: Uri): File? {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        val target = scratchFile(SOURCE_ARCHIVE)
        return runCatching {
            stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            target
        }.getOrNull()
    }

    private fun scratchFile(name: String): File {
        val dir = File(context.cacheDir, SCRATCH_DIR_NAME)
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, name)
    }

    // --- unchanged legacy helpers --------------------------------------------

    private fun prune() {
        val backups = (backupDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".zip") }
            .toList()

        BackupPruner.selectForDeletion(backups, retentionProvider.retention())
            .forEach { runCatching { it.delete() } }
    }

    /** Aggregates while walking: a full archive can be tens of thousands of files. */
    private fun countStoredFiles(): Pair<Int, Long> {
        val root = File(context.filesDir, StorageLayout.ROOT_NAME)
        if (!root.isDirectory) return 0 to 0L
        return root.walkTopDown()
            .filter { it.isFile }
            .fold(0 to 0L) { (count, bytes), file -> (count + 1) to (bytes + file.length()) }
    }

    /** Deletes a half-written export; only plain files can be removed this way. */
    private fun discardPartialArchive(dest: Uri) {
        if (dest.scheme != "file") return
        val path = dest.path ?: return
        runCatching { File(path).delete() }
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

    /** File/byte totals reported to the UI. */
    private data class ArchiveStats(val fileCount: Int, val bytes: Long)

    private companion object {
        const val BACKUP_DIR_NAME = "bak"
        const val SCRATCH_DIR_NAME = "backup-scratch"
        const val STAGING_ARCHIVE = "export-staging.zip"
        const val SOURCE_ARCHIVE = "import-source.zip"
        const val STRIPPED_ARCHIVE = "import-stripped.zip"

        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
