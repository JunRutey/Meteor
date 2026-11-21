package com.meteor.memory.ui.gallery

import com.meteor.memory.core.storage.AlbumKind

/**
 * Naming and MIME rules for the temporary file handed to the system share
 * sheet from the album viewer (ADR-054). Extends the `ImageShareFile` idea to
 * videos, audio and (ADR-065) imported files: the viewer already knows the
 * stored MIME type from the album entry, so it uses that instead of sniffing
 * bytes.
 */
object MediaShareFile {

    /** Fallback MIME when the stored entry carried none. */
    fun mimeTypeForKind(kind: AlbumKind): String = when (kind) {
        AlbumKind.IMAGE -> ImageShareFile.MIME_UNKNOWN
        AlbumKind.VIDEO -> MIME_VIDEO
        AlbumKind.AUDIO -> MIME_AUDIO
        // Sharing an imported file is how the user exports it, so keep the
        // generic type: the receiver decides how to open the bytes.
        AlbumKind.FILE -> MIME_OCTET_STREAM
    }

    /**
     * Stable, filesystem-safe name for the cached copy.
     *
     * Prefers the original [displayName] stem so the receiver sees a readable
     * file name; falls back to the ref id. Only letters, digits, `-` and `_`
     * survive, so a crafted name can never introduce a path separator.
     */
    fun fileName(ref: String, displayName: String?, mimeType: String, timestampMs: Long): String {
        val sourceStem = displayName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: ref.substringAfterLast('/').substringBeforeLast('.').ifBlank { null }
            ?: DEFAULT_STEM
        val stem = sourceStem
            .take(MAX_STEM)
            .map { char ->
                if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
            }
            .joinToString("")
            .ifEmpty { DEFAULT_STEM }
        return "meteor-$timestampMs-$stem.${extensionFor(mimeType)}"
    }

    private fun extensionFor(mimeType: String): String = when {
        mimeType.startsWith("video/") -> "mp4"
        mimeType.startsWith("audio/") -> "m4a"
        mimeType == "image/gif" -> "gif"
        mimeType == "image/png" -> "png"
        mimeType == "image/webp" -> "webp"
        mimeType.startsWith("image/") -> "jpg"
        else -> "bin"
    }

    const val MIME_VIDEO = "video/*"
    const val MIME_AUDIO = "audio/*"
    const val MIME_OCTET_STREAM = "application/octet-stream"

    private const val MAX_STEM = 32
    private const val DEFAULT_STEM = "media"
}
