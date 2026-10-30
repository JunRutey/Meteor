package com.meteor.memory.core.backup

import java.io.File

/**
 * Decides which rolling snapshots to drop.
 *
 * Pure and IO-free so retention rules are unit-testable: the caller passes the
 * snapshot files it found and gets back the ones that must be deleted.
 */
object BackupPruner {

    fun selectForDeletion(backups: List<File>, retention: Int): List<File> {
        val keep = retention.coerceAtLeast(1)
        return backups
            .sortedByDescending { it.lastModified() }
            .drop(keep)
    }
}
