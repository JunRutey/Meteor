package com.meteor.memory.feature.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.meteor.memory.core.storage.AlbumRepository
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaStore
import java.io.File

/**
 * Archive access handed to the media composables (ADR-052 §4).
 *
 * `:feature-media` deliberately has no dependency injection: Hilt only exists in
 * `:app` (AGENTS §9), and the contract signatures for [MediaThumb],
 * [VideoPlayerCard] and [AudioPlayerCard] take no store parameter. `:app`
 * therefore builds one [MediaHost] from its already-constructed stores and wraps
 * the Compose tree in [ProvideMediaHost]. Calling code never sees this type, so
 * the public surface stays exactly the contract.
 */
class MediaHost(
    val mediaStore: MediaStore,
    val imageStore: ImageStore,
    /** `context.cacheDir`: decrypted playback copies live here and are deleted after use. */
    val cacheDir: File,
) {

    /** New and legacy references through the one implementation in `:core-storage`. */
    val album: AlbumRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AlbumRepository(mediaStore, imageStore)
    }
}

/** Null when the tree was not wrapped in [ProvideMediaHost]; composables degrade instead of crashing. */
val LocalMediaHost = staticCompositionLocalOf<MediaHost?> { null }

/** Installs the archive handles for every media composable below [content]. */
@Composable
fun ProvideMediaHost(host: MediaHost, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMediaHost provides host, content = content)
}
