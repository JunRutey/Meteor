package com.meteor.memory.feature.media

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.meteor.memory.core.storage.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inline video player for one stored reference (ADR-052 §4).
 *
 * Collapsed it is the cover thumbnail with a play disc; tapping it streams the
 * encrypted file through [MediaStreamDataSource] and plays it inline with the
 * Media3 controller. Only the opening chunks are decrypted for the first frame:
 * a large video is never fully decrypted into a temporary plaintext copy
 * (ADR-058). Playback stops and the decoder is released when the lifecycle
 * drops below STARTED.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerCard(
    ref: String,
    modifier: Modifier = Modifier,
) {
    val host = LocalMediaHost.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val failedLabel = stringResource(R.string.media_video_failed)
    var playing by remember(ref) { mutableStateOf(false) }
    var preparing by remember(ref) { mutableStateOf(false) }
    var failed by remember(ref) { mutableStateOf(false) }
    var player by remember(ref) { mutableStateOf<ExoPlayer?>(null) }

    // The encrypted payload is streamed, not materialised: the first frame needs
    // only the opening chunks, and seeking reopens at the requested offset.
    LaunchedEffect(ref, playing, host) {
        if (!playing || host == null || player != null) return@LaunchedEffect
        preparing = true
        failed = false
        val parsed = MediaRef.parse(ref) as? MediaRef.Media
        val media = parsed?.let { target ->
            withContext(Dispatchers.IO) {
                runCatching { host.mediaStore.find(target.id) }.getOrNull()
            }
        }
        if (parsed == null || media == null) {
            failed = true
            preparing = false
            playing = false
            return@LaunchedEffect
        }
        val source = ProgressiveMediaSource.Factory(MediaStreamDataSource.Factory(host.mediaStore))
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(MediaStreamDataSource.uriFor(parsed.id))
                    .setMimeType(media.mimeType)
                    .build(),
            )
        player = ExoPlayer.Builder(context).build().apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
        preparing = false
    }

    // Stopping playback tears the decoder down immediately instead of waiting
    // for the composable to leave the tree.
    LaunchedEffect(playing) {
        if (playing) return@LaunchedEffect
        player?.release()
        player = null
        preparing = false
    }

    // Backgrounding the screen must not keep playing (or holding a decoder).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playing = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(ref) {
        onDispose {
            player?.release()
            player = null
        }
    }

    val description = stringResource(R.string.media_video_description)
    val stopLabel = stringResource(R.string.media_video_stop)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .aspectRatio(VIDEO_ASPECT)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text(
                text = failedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )

            preparing -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.media_video_preparing),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }

            player != null -> AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view -> view.player = null },
            )

            else -> {
                MediaThumb(ref = ref, modifier = Modifier.fillMaxSize())
                CoverPlayBadge(
                    label = stringResource(R.string.media_video_play),
                    onClick = {
                        failed = false
                        playing = true
                    },
                )
            }
        }

        if (player != null) {
            // Top-end corner, not the center: the Media3 controller's own
            // play/pause sits in the middle and must stay reachable.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SCRIM)
                    .clickable { playing = false }
                    .semantics { contentDescription = stopLabel },
                contentAlignment = Alignment.Center,
            ) {
                MediaGlyph(MediaGlyphKind.Pause, tint = Color.White, iconSize = 18.dp)
            }
        }
    }
}

@Composable
private fun CoverPlayBadge(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(SCRIM)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MediaGlyph(MediaGlyphKind.Play, tint = Color.White, iconSize = 26.dp)
    }
}

private val VIDEO_ASPECT = 16f / 9f
private val SCRIM = Color(0x80000000)
