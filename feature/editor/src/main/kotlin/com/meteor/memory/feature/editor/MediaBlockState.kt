package com.meteor.memory.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import com.meteor.memory.core.storage.MediaKind

/**
 * How a `meteor://media/<id>` line must be drawn (ADR-055).
 *
 * The body only carries the id: the kind lives in the encrypted metadata sidecar,
 * which `:feature-editor` deliberately cannot read (it has no `MediaStore`). The
 * **host** therefore answers this question — `:app` for the diary and person
 * editors, which already own the store — and the editor stays a pure renderer.
 *
 * The same value is used by the editing surface and by [MarkdownPreview], so both
 * show the identical card for the identical reference.
 */
sealed interface MediaBlockState {

    /**
     * The host has not answered yet, or no resolver was supplied.
     *
     * Rendered as a neutral frame: a record whose media is still being decrypted
     * must not look broken, and a host that installs no resolver must not crash.
     * Never rendered as "missing" — that would accuse the archive of losing a file
     * it simply has not loaded yet.
     */
    data object Pending : MediaBlockState

    /**
     * A stored image, drawn inline exactly like an ADR-049 image block.
     *
     * [bitmap] is null while the host is still decrypting: the card then shows the
     * same neutral frame as [Pending].
     */
    data class Image(val bitmap: ImageBitmap?) : MediaBlockState

    /** A stored video: an inline, playable [com.meteor.memory.feature.media.VideoPlayerCard]. */
    data object Video : MediaBlockState

    /**
     * A stored audio file: a [com.meteor.memory.feature.media.AudioPlayerCard].
     *
     * [title] is the **original file name** from the encrypted metadata sidecar
     * (`StoredMedia.fileName`, e.g. `voice memo.m4a`), not the Markdown caption:
     * the caption is optional user text and the player must still say *which*
     * recording it is when the description is empty (ADR-064). Null means the
     * host could not read a name and the player keeps its generic label.
     */
    data class Audio(val title: String?) : MediaBlockState

    /**
     * The user picked this file and it is still being encrypted in the background
     * (ADR-061).
     *
     * The body already carries a placeholder `meteor://media/<temp-id>` so the
     * editor never has to wait for a large import: the card says "importing" and
     * is replaced with the real id once [MediaStore.save] returns. It is not the
     * same value as [Pending]: pending means "host has not resolved yet", while
     * this means "the file is actively being imported".
     */
    data object Importing : MediaBlockState

    /**
     * The entry is gone (deleted, or the metadata cannot be read).
     *
     * Shown as a "cannot display" frame plus the caption, mirroring the legacy
     * image placeholder, so a broken reference is visible instead of silent.
     */
    data object Missing : MediaBlockState

    companion object {
        /** Convenience for hosts: map a stored kind plus an already decoded bitmap. */
        fun of(
            kind: MediaKind,
            bitmap: ImageBitmap? = null,
            audioTitle: String? = null,
        ): MediaBlockState = when (kind) {
            MediaKind.IMAGE -> Image(bitmap)
            MediaKind.VIDEO -> Video
            MediaKind.AUDIO -> Audio(audioTitle)
        }
    }
}
