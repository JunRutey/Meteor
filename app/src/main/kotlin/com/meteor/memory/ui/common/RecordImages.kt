package com.meteor.memory.ui.common

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.meteor.memory.core.storage.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every image a body references, decoded once.
 *
 * Three outcomes are kept apart on purpose: a bitmap, a token that is gone (file
 * deleted), and a token that exists but yields no bitmap. The last two look the
 * same to the user but both mean "do not wait for this one any more" — without
 * that distinction the picker strip can sit on an empty frame forever.
 */
internal data class DecodedImages(
    val bitmaps: Map<String, ImageBitmap> = emptyMap(),
    val missing: Set<String> = emptySet(),
)

/**
 * Decodes [tokens] off the main thread.
 *
 * Sample size 2 is enough for a 64dp thumbnail and a half-screen preview, and
 * saves three quarters of the memory when a record carries many images.
 */
internal suspend fun decodeImages(
    store: ImageStore,
    tokens: List<String>,
    max: Int = 20,
): DecodedImages = withContext(Dispatchers.IO) {
    if (tokens.isEmpty()) return@withContext DecodedImages()

    val bitmaps = LinkedHashMap<String, ImageBitmap>(tokens.size)
    val missing = LinkedHashSet<String>()
    for (token in tokens.take(max)) {
        val bytes = store.load(token)
        if (bytes == null) {
            missing += token
            continue
        }
        val options = BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (bitmap == null) missing += token else bitmaps[token] = bitmap.asImageBitmap()
    }
    DecodedImages(bitmaps, missing)
}

private const val SAMPLE_SIZE = 2
