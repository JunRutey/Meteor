package com.meteor.memory.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnail of a device (not yet imported) `MediaStore` file.
 *
 * Uses the platform thumbnail API on API 29+ and a sampled decode below it, with
 * a small LRU cache so scrolling the picker grid does not re-decode. This is
 * strictly for the *source* file; archived media goes through [MediaThumb].
 */
@Composable
internal fun DeviceMediaThumb(
    uri: Uri,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(DeviceThumbCache.get(uri)) }

    LaunchedEffect(uri) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadDeviceThumbnail(context, uri) }
        if (loaded != null) DeviceThumbCache.put(uri, loaded)
        bitmap = loaded
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { decoded ->
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun loadDeviceThumbnail(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(THUMB_EDGE, THUMB_EDGE), null)
    } else {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            MediaBitmaps.decode(bytes, THUMB_EDGE)
        }
    }
}.getOrNull()

/** Bounded so a large library cannot exhaust the heap. */
private object DeviceThumbCache {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(uri: Uri): Bitmap? = runCatching { cache.get(uri.toString()) }.getOrNull()

    fun put(uri: Uri, bitmap: Bitmap) {
        runCatching { cache.put(uri.toString(), bitmap) }
    }

    private const val CACHE_BYTES = 8 * 1024 * 1024
}

private const val THUMB_EDGE = 256
