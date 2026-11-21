package com.meteor.memory.ui.people

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.feature.editor.MediaBlockState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers "how do I draw `meteor://media/<id>`" for a person's body.
 *
 * `:feature-editor` renders media blocks but cannot read the archive — the kind
 * lives in an encrypted metadata sidecar (ADR-052 §3) and `:feature-editor` has
 * no `MediaStore`. The host therefore resolves every reference up front on the
 * IO dispatcher and hands the editor a synchronous lookup
 * (`MediaBlockState`, ADR-055).
 *
 * Only the ids a body actually references are touched: a person's page must not
 * decrypt the whole album to draw one paragraph. A dead reference resolves to
 * [MediaBlockState.Missing] rather than throwing, so one deleted file cannot
 * take down the whole body.
 */
internal suspend fun decodeMediaBlocks(
    mediaStore: MediaStore,
    ids: Collection<String>,
): Map<String, MediaBlockState> = withContext(Dispatchers.IO) {
    if (ids.isEmpty()) return@withContext emptyMap()

    val states = LinkedHashMap<String, MediaBlockState>(ids.size)
    for (id in ids) {
        val kind = runCatching { mediaStore.find(id)?.kind }.getOrNull()
        states[id] = when (kind) {
            null -> MediaBlockState.Missing
            MediaKind.IMAGE -> MediaBlockState.Image(decodeImage(mediaStore, id))
            MediaKind.VIDEO -> MediaBlockState.Video
            MediaKind.AUDIO -> MediaBlockState.Audio
        }
    }
    states
}

/**
 * Decodes an inline image, downsampled.
 *
 * Sample size 2 matches the person page's other image decoder: the block is at
 * most the page width, and a full-resolution photo would cost megabytes per body.
 */
private fun decodeImage(mediaStore: MediaStore, id: String) =
    runCatching { mediaStore.load(id) }.getOrNull()?.let { bytes ->
        val options = BitmapFactory.Options().apply { inSampleSize = IMAGE_SAMPLE_SIZE }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

private const val IMAGE_SAMPLE_SIZE = 2
