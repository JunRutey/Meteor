package com.meteor.memory.ui.diary

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.feature.editor.MediaBlockState
import com.meteor.memory.ui.common.sampledImageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns `meteor://media/<id>` references into something the editor can draw
 * (ADR-052 §5 D, ADR-055).
 *
 * The body only carries ids; the kind lives in the encrypted metadata sidecar, so
 * this is where the diary editor asks the store what each reference is and
 * prepares the one piece of state the renderer cannot fetch itself — the decoded
 * picture. Video and audio need no bytes up front: their cards decrypt on demand.
 *
 * Ordered like the decode of legacy images (bitmap / missing / not loaded yet) so
 * the two paths behave the same: a reference that produced no bitmap is kept out
 * of the "still loading" set instead of being retried forever.
 */
internal suspend fun decodeMediaStates(
    store: MediaStore,
    ids: List<String>,
): Map<String, MediaBlockState> = withContext(Dispatchers.IO) {
    if (ids.isEmpty()) return@withContext emptyMap()

    val states = LinkedHashMap<String, MediaBlockState>(ids.size)
    for (id in ids) {
        val stored = runCatching { store.find(id) }.getOrNull()
        if (stored == null) {
            states[id] = MediaBlockState.Missing
            continue
        }
        states[id] = when (stored.kind) {
            MediaKind.VIDEO -> MediaBlockState.Video
            MediaKind.AUDIO -> MediaBlockState.Audio
            MediaKind.IMAGE -> MediaBlockState.Image(decodeImage(store, id))
        }
    }
    states
}

/**
 * Decrypts one stored picture at a sampled size.
 *
 * Downsampled on decode rather than by reusing the 512px thumbnail: the editing
 * surface shows the picture at full width, and a blurred card would read as a
 * broken import.
 */
private fun decodeImage(store: MediaStore, id: String): ImageBitmap? {
    val bytes = runCatching { store.load(id) }.getOrNull() ?: return null
    val options: BitmapFactory.Options = sampledImageOptions(bytes, maxEdge = EDITOR_IMAGE_MAX_EDGE)
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}

/** Roughly two pixels per dp on the widest phone, so a card is never visibly soft. */
private const val EDITOR_IMAGE_MAX_EDGE = 2048
