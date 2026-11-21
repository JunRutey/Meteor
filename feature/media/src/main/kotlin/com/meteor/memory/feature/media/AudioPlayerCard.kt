package com.meteor.memory.feature.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.meteor.memory.core.storage.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mini audio player for one stored reference (ADR-052 §4).
 *
 * Play/pause, a progress bar and the duration, streaming the encrypted file
 * through [MediaStreamDataSource] just like [VideoPlayerCard] (ADR-058) instead
 * of materialising a plaintext copy. A short poll keeps the progress bar and
 * label fresh; it stops the moment playback stops.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AudioPlayerCard(
    ref: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val host = LocalMediaHost.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val failedLabel = stringResource(R.string.media_audio_failed)
    var playing by remember(ref) { mutableStateOf(false) }
    var preparing by remember(ref) { mutableStateOf(false) }
    var failed by remember(ref) { mutableStateOf(false) }
    var player by remember(ref) { mutableStateOf<ExoPlayer?>(null) }
    var durationMs by remember(ref) { mutableLongStateOf(0L) }
    var positionMs by remember(ref) { mutableLongStateOf(0L) }

    // Stored metadata knows the duration before the player has decoded anything,
    // so the label is never "0:00" while preparing.
    LaunchedEffect(ref, host) {
        val storedDuration = host?.let { media ->
            withContext(Dispatchers.IO) {
                val parsed = MediaRef.parse(ref) as? MediaRef.Media
                parsed?.let { media.mediaStore.find(it.id)?.durationMs }
            }
        }
        // A local, not the cross-module property: only same-module `val`s are
        // smart-castable, so this keeps the non-null check meaningful.
        if (storedDuration != null && storedDuration > 0L && durationMs <= 0L) {
            durationMs = storedDuration
        }
    }

    LaunchedEffect(ref, playing, host) {
        if (host == null) return@LaunchedEffect
        if (!playing) {
            // Pause, don't tear down: resuming from the same position is the
            // whole point of a play/pause control.
            player?.playWhenReady = false
            return@LaunchedEffect
        }
        val existing = player
        if (existing != null) {
            existing.playWhenReady = true
            preparing = false
            return@LaunchedEffect
        }
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
        val built = ExoPlayer.Builder(context).build()
        built.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Rewind so the next tap starts from the top instead of sitting
                // at the end doing nothing.
                if (playbackState == Player.STATE_ENDED) {
                    built.seekTo(0L)
                    playing = false
                }
            }
        })
        built.setMediaSource(source)
        built.prepare()
        built.playWhenReady = true
        player = built
        preparing = false
    }

    // Poll while playing: Media3 has no Compose-friendly position flow.
    LaunchedEffect(player, playing) {
        while (player != null && playing) {
            val active = player ?: break
            // ExoPlayer reports C.TIME_UNSET (a large negative) until the
            // container is parsed; keep the metadata duration in that case.
            val reported = active.duration
            if (reported > 0L) durationMs = reported
            positionMs = active.currentPosition.coerceAtLeast(0L)
            delay(PROGRESS_POLL_MS)
        }
    }

    // Leaving the screen (lifecycle stop) frees the decoder; pausing keeps it
    // alive for an instant resume. No plaintext copy exists to clean up.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                playing = false
                player?.release()
                player = null
                positionMs = 0L
            }
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

    val resolvedTitle = title ?: stringResource(R.string.media_audio_title)
    val actionLabel = if (playing) {
        stringResource(R.string.media_audio_pause)
    } else {
        stringResource(R.string.media_audio_play)
    }
    val safeDuration = durationMs.coerceAtLeast(0L)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(enabled = !preparing) {
                        playing = !playing
                    }
                    .semantics { contentDescription = actionLabel },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    preparing -> MediaGlyph(
                        MediaGlyphKind.Audio,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconSize = 20.dp,
                    )
                    else -> MediaGlyph(
                        kind = if (playing) MediaGlyphKind.Pause else MediaGlyphKind.Play,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconSize = 20.dp,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = resolvedTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (failed) {
                    Text(
                        text = failedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (preparing) {
                    Text(
                        text = stringResource(R.string.media_audio_preparing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val fraction = if (safeDuration > 0L) {
                        (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = MediaImportLogic.formatDuration(positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = MediaImportLogic.formatDuration(safeDuration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val PROGRESS_POLL_MS = 500L
