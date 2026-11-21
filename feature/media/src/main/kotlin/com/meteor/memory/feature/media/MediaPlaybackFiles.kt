package com.meteor.memory.feature.media

import com.meteor.memory.core.storage.MediaRef
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Temporary plaintext copies for ExoPlayer (ADR-052 §4).
 *
 * Playback must never load a whole video into memory: the encrypted payload is
 * streamed through `MediaStore.copyDecrypted` into `cacheDir` and handed to
 * Media3 as a file. Every copy is deleted as soon as the player is released, and
 * [prune] clears copies a crash left behind.
 */
internal object MediaPlaybackFiles {

    private val counter = AtomicLong()

    /**
     * Streams the media behind [ref] into a fresh file inside [directory].
     *
     * Returns null when the reference cannot be resolved or the copy failed; a
     * partially written file is removed before returning.
     */
    fun materialize(host: MediaHost, ref: String, directory: File): File? {
        val mimeType = mimeTypeOf(host, ref)
        val suffix = extensionFor(mimeType)
        // Unique per call: two cards for the same reference must not share a file
        // that one of them deletes while the other is still playing.
        val file = File(directory, "$FILE_PREFIX${System.nanoTime()}-${counter.incrementAndGet()}$suffix")
        return try {
            check(directory.isDirectory || directory.mkdirs()) { "cannot create playback directory" }
            val written = FileOutputStream(file).use { output ->
                host.album.copyDecrypted(ref, output)
            }
            if (written == null || written <= 0L) {
                file.delete()
                null
            } else {
                file
            }
        } catch (failure: Exception) {
            file.delete()
            null
        }
    }

    /** Best-effort removal; safe to call twice. */
    fun release(file: File?) {
        if (file == null) return
        runCatching { file.delete() }
    }

    /**
     * Deletes playback copies left over by a previous process.
     *
     * Only files this module created are touched, and only when they are stale,
     * so a card that is currently playing keeps its file.
     */
    fun prune(directory: File, olderThanMs: Long, now: Long = System.currentTimeMillis()) {
        if (!directory.isDirectory) return
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) }
            .filter { now - it.lastModified() > olderThanMs }
            .forEach { runCatching { it.delete() } }
    }

    /** Container hint for the temporary file, so Media3 picks the right extractor. */
    fun extensionFor(mimeType: String?): String = when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "video/mp4" -> ".mp4"
        "video/x-m4v" -> ".m4v"
        "video/quicktime" -> ".mov"
        "video/3gpp" -> ".3gp"
        "video/webm" -> ".webm"
        "video/x-matroska" -> ".mkv"
        "audio/mpeg" -> ".mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a"
        "audio/aac" -> ".aac"
        "audio/wav", "audio/x-wav" -> ".wav"
        "audio/ogg" -> ".ogg"
        "audio/opus" -> ".opus"
        "audio/flac", "audio/x-flac" -> ".flac"
        "audio/amr" -> ".amr"
        else -> ".bin"
    }

    private fun mimeTypeOf(host: MediaHost, ref: String): String? =
        when (val parsed = MediaRef.parse(ref)) {
            is MediaRef.Media -> runCatching { host.mediaStore.find(parsed.id)?.mimeType }.getOrNull()
            is MediaRef.LegacyImage -> null
            // An imported file is never handed to Media3: it has no player, so it
            // never reaches this temporary-playback-copy path (ADR-065).
            is MediaRef.File -> null
            null -> null
        }

    private const val FILE_PREFIX = "play-"

    /** Copies older than this are assumed abandoned (a playback session does not last this long). */
    const val STALE_AFTER_MS = 6L * 60L * 60L * 1000L
}
