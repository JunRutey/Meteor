package com.meteor.memory.feature.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 *
 * The top-right corner also offers landscape fullscreen (ADR-064). The dialog
 * reuses the **same** [ExoPlayer]: Media3 binds a player to one `PlayerView`'s
 * surface at a time, so the inline view is unbound before the fullscreen view
 * attaches and unbound again before the inline view is restored.
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

    // One player, two possible surfaces (ADR-064). Keeping the views around lets
    // the buttons explicitly unbind the surface before the other one attaches.
    var fullscreen by remember(ref) { mutableStateOf(false) }
    var inlineView by remember(ref) { mutableStateOf<PlayerView?>(null) }
    var fullscreenView by remember(ref) { mutableStateOf<PlayerView?>(null) }

    // Serialises the surface hand-over in both directions: dismiss the dialog
    // only after its view let go of the player.
    fun leaveFullscreen() {
        fullscreenView?.player = null
        fullscreen = false
    }

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

    // A released player cannot back a fullscreen dialog; leaving it open would
    // also keep the activity locked in landscape.
    LaunchedEffect(player) {
        if (player == null) leaveFullscreen()
    }

    // The dialog is a second window, but the activity itself is what has to
    // rotate. onDispose covers every way out — the exit button, the system back
    // gesture, a released player, and leaving the page while fullscreen — so a
    // video can never strand another screen in landscape.
    DisposableEffect(fullscreen) {
        val activity = context.findActivity()
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            if (fullscreen) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
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
    val fullscreenLabel = stringResource(R.string.media_video_fullscreen)
    val exitFullscreenLabel = stringResource(R.string.media_video_exit_fullscreen)
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

            // While the dialog is up the player lives in the dialog's PlayerView;
            // composing an inline view for the same player would crash Media3.
            player != null -> if (!fullscreen) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = true
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }.also { view -> inlineView = view }
                    },
                    update = { view -> if (view.player !== player) view.player = player },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        view.player = null
                        if (inlineView === view) inlineView = null
                    },
                )
            }

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

        if (player != null && !fullscreen) {
            // Top-end corner, not the center: the Media3 controller's own
            // play/pause sits in the middle and must stay reachable.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CornerButton(
                    label = fullscreenLabel,
                    onClick = {
                        // Hand the surface over explicitly, before the dialog
                        // composes its own PlayerView (ADR-064).
                        inlineView?.player = null
                        fullscreen = true
                    },
                ) {
                    MediaGlyph(MediaGlyphKind.Fullscreen, tint = Color.White, iconSize = 18.dp)
                }
                CornerButton(label = stopLabel, onClick = { playing = false }) {
                    MediaGlyph(MediaGlyphKind.Pause, tint = Color.White, iconSize = 18.dp)
                }
            }
        }
    }

    if (fullscreen && player != null) {
        Dialog(
            onDismissRequest = { leaveFullscreen() },
            properties = DialogProperties(
                // The dialog has to fill the landscape window, not be a card.
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = true
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }.also { view -> fullscreenView = view }
                    },
                    update = { view -> if (view.player !== player) view.player = player },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        view.player = null
                        if (fullscreenView === view) fullscreenView = null
                    },
                )

                // Top-start, away from the Media3 controller bar at the bottom.
                CornerButton(
                    label = exitFullscreenLabel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    onClick = { leaveFullscreen() },
                ) {
                    MediaGlyph(MediaGlyphKind.FullscreenExit, tint = Color.White, iconSize = 18.dp)
                }
            }
        }
    }
}

/** Small circular action sitting on top of the video, with a scrim for contrast. */
@Composable
private fun CornerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(CORNER_BUTTON_SIZE)
            .clip(CircleShape)
            .background(SCRIM)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
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

/**
 * `LocalContext` is not always the `Activity` itself (a theme can wrap it), so
 * the orientation request walks the wrapper chain instead of assuming a cast.
 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val VIDEO_ASPECT = 16f / 9f
private val SCRIM = Color(0x80000000)
private val CORNER_BUTTON_SIZE = 36.dp
