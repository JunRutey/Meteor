package com.meteor.memory.feature.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Async thumbnail of a stored media reference (ADR-052 §4).
 *
 * The PNG sidecar written at import time is preferred; a legacy `meteor://image/`
 * reference without one is decrypted and downsampled on the fly, so old entries
 * render with no migration and no rewritten Markdown. A spinner shows while the
 * decrypt runs, and an unavailable file degrades to a kind glyph instead of a
 * broken image — one missing file must never break a whole grid.
 */
@Composable
fun MediaThumb(
    ref: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val host = LocalMediaHost.current
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    val label = contentDescription ?: stringResource(R.string.media_thumb_description)
    var state by remember(ref, host) { mutableStateOf<ThumbState>(ThumbState.Loading) }

    LaunchedEffect(ref, host) {
        if (host == null) {
            state = ThumbState.Unavailable(MediaKind.IMAGE)
            return@LaunchedEffect
        }
        state = ThumbState.Loading
        state = withContext(Dispatchers.IO) { loadThumbnail(host, ref) }
    }

    Box(
        modifier = modifier.background(placeholderColor),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is ThumbState.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            ThumbState.Loading -> CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(FRACTION_PLACEHOLDER_ICON),
                strokeWidth = 2.dp,
                color = iconTint,
            )

            is ThumbState.Unavailable -> MediaGlyph(
                kind = when (current.kind) {
                    MediaKind.IMAGE -> MediaGlyphKind.Missing
                    MediaKind.VIDEO -> MediaGlyphKind.Video
                    MediaKind.AUDIO -> MediaGlyphKind.Audio
                },
                tint = iconTint,
                modifier = Modifier.fillMaxSize(FRACTION_PLACEHOLDER_ICON),
            )
        }
    }
}

/** Internal render state of a [MediaThumb]; not part of the public surface. */
internal sealed interface ThumbState {
    data object Loading : ThumbState

    /** No thumbnail exists and the original cannot be decoded either. */
    data class Unavailable(val kind: MediaKind) : ThumbState

    data class Ready(val bitmap: ImageBitmap) : ThumbState
}

/**
 * Runs on `Dispatchers.IO`.
 *
 * Sidecar first, legacy lazy decode second. Legacy images are decrypted whole by
 * [com.meteor.memory.core.storage.ImageStore], which is acceptable: they are
 * photos the gallery already showed, and the decode is downsampled to
 * [MediaBitmaps.THUMBNAIL_MAX_EDGE].
 */
internal fun loadThumbnail(host: MediaHost, ref: String): ThumbState {
    val parsed = MediaRef.parse(ref) ?: return ThumbState.Unavailable(MediaKind.IMAGE)
    return when (parsed) {
        is MediaRef.Media -> {
            val kind = runCatching { host.mediaStore.find(parsed.id)?.kind }.getOrNull()
                ?: MediaKind.IMAGE
            val sidecar = runCatching { host.mediaStore.loadThumbnail(parsed.id) }.getOrNull()
            if (sidecar != null) {
                val decoded = MediaBitmaps.decode(sidecar, MediaBitmaps.THUMBNAIL_MAX_EDGE)
                if (decoded != null) return ThumbState.Ready(decoded.asImageBitmap())
            }
            // Only images may fall back to the original payload: load(id) reads
            // the whole file into memory, which a multi-gigabyte video must never
            // do (ADR-052 §4 forbids exactly that). Video/audio without a
            // thumbnail show their kind glyph instead.
            if (kind != MediaKind.IMAGE) return ThumbState.Unavailable(kind)
            val original = runCatching { host.mediaStore.load(parsed.id) }.getOrNull()
            val decoded = original?.let { MediaBitmaps.decode(it, MediaBitmaps.THUMBNAIL_MAX_EDGE) }
            if (decoded != null && original != null) {
                val upright = MediaBitmaps.applyExifOrientation(original, decoded)
                ThumbState.Ready(upright.asImageBitmap())
            } else {
                ThumbState.Unavailable(kind)
            }
        }

        is MediaRef.LegacyImage -> {
            val bytes = runCatching { host.imageStore.load(parsed.token) }.getOrNull()
            val decoded = bytes?.let { MediaBitmaps.decode(it, MediaBitmaps.THUMBNAIL_MAX_EDGE) }
            if (decoded != null && bytes != null) {
                // Legacy images have no sidecar: decode and apply the EXIF
                // rotation on the fly, no migration needed.
                ThumbState.Ready(MediaBitmaps.applyExifOrientation(bytes, decoded).asImageBitmap())
            } else {
                ThumbState.Unavailable(MediaKind.IMAGE)
            }
        }
    }
}

private const val FRACTION_PLACEHOLDER_ICON = 0.28f
