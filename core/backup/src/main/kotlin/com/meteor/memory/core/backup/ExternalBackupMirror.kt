package com.meteor.memory.core.backup

import java.io.File

/** Outcome of mirroring a snapshot to an external location. */
sealed interface MirrorResult {
    data object Copied : MirrorResult
    data object NotConfigured : MirrorResult
    data class Failed(val message: String, val cause: Throwable? = null) : MirrorResult
}

/**
 * Reserved extension point (Meteor archive §14): a second backup destination on
 * external storage via the Storage Access Framework.
 *
 * **Deliberately not implemented yet.** It exists so that adding it later is a
 * pure addition — [BackupModule] and every caller stay untouched, which is what
 * archive R2 demands. Wiring it now would mean a permissions dialog and a
 * persisted tree URI for a feature nobody asked for yet.
 */
interface ExternalBackupMirror {

    /** True once the user has granted a tree URI. Always false today. */
    fun isConfigured(): Boolean

    /** Copies one finished snapshot to the configured tree. */
    suspend fun mirror(snapshot: File): MirrorResult
}

/** Placeholder wiring: reports "not configured" and copies nothing. */
object NoExternalBackupMirror : ExternalBackupMirror {
    override fun isConfigured(): Boolean = false

    override suspend fun mirror(snapshot: File): MirrorResult = MirrorResult.NotConfigured
}
