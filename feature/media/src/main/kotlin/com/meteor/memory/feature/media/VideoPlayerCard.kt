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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
        if (!playing) return@LaunchedEffect
        if (host == null) {
            // Nothing can decrypt without the archive handles; say so instead of
            // spinning forever.
            preparing = false
            playing = false
            failed = true
            return@LaunchedEffect
        }
        if (player != null) {
            // A key change can restart this effect after the player was built;
            // there is nothing left to prepare, but the spinner must still go.
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
        val built = ExoPlayer.Builder(context)
            // A call or another app taking audio focus must pause the video
            // instead of talking over it; unplugging headphones must not move a
            // private recording to the speaker.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        built.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // `prepare()` is asynchronous: the spinner has to stay up until
                // the first frame is decodable, not until the call returns.
                if (playbackState == Player.STATE_READY) preparing = false
                // Rewind so the next tap starts from the top instead of sitting
                // at the end doing nothing.
                if (playbackState == Player.STATE_ENDED) {
                    built.seekTo(0L)
                    playing = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Without this a corrupt file or an unsupported codec left a
                // silent black rectangle: the failure text was unreachable.
                failed = true
                playing = false
            }
        })
        built.setMediaSource(source)
        built.prepare()
        built.playWhenReady = true
        player = built
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
    // rotate. Every way out — the exit button, the system back gesture, a
    // released player, leaving the page while fullscreen — disposes this effect,
    // so a video can never strand another screen in landscape.
    //
    // The restore must NOT read `fullscreen` inside `onDispose`: that lambda
    // captures the MutableState, and by the time it runs the value has already
    // been flipped to false, so a guard on it is never true and the orientation
    // was never restored (the whole app stayed landscape). The direction to
    // return to is captured here, when the effect is created.
    DisposableEffect(fullscreen) {
        if (!fullscreen) return@DisposableEffect onDispose { }
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // SENSOR_LANDSCAPE, not LANDSCAPE: the fixed value pins one of the two
        // landscape rotations, so a user who turned the phone the other way saw
        // an upside-down picture they could not correct.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previous
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
    val retryLabel = stringResource(R.string.media_video_retry)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .aspectRatio(VIDEO_ASPECT)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        // A decode error is often transient (the file was being
                        // rewritten, the decoder was reclaimed); let the card go
                        // back to its cover instead of dead-ending on the message.
                        failed = false
                    }
                    .semantics { contentDescription = retryLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = failedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                )
            }

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
                // The activity is edge-to-edge (ADR-024), but a Dialog is its own
                // window and defaults to fitting the system windows: without this
                // the black player only covered the safe area and left bright
                // paper strips along the status bar and the landscape nav bar.
                decorFitsSystemWindows = false,
            ),
        ) {
            // A Dialog owns its own window, and that window seeds its bar-icon
            // appearance from the XML theme rather than from the activity's
            // runtime edge-to-edge state. With the player painting under the bars
            // the light theme therefore drew dark icons on top of black video,
            // where they were invisible.
            DialogDarkSystemBars()
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
                // The dialog paints edge-to-edge now, so the button carries the
                // insets itself: in landscape the cutout and the gesture bar sit
                // on the sides and would otherwise swallow the tap.
                CornerButton(
                    label = exitFullscreenLabel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp),
                    onClick = { leaveFullscreen() },
                ) {
                    MediaGlyph(MediaGlyphKind.FullscreenExit, tint = Color.White, iconSize = 18.dp)
                }
            }
        }
    }
}

/**
 * Forces light system-bar icons for as long as the fullscreen player is composed.
 *
 * The dialog's window is created by the platform, so it does not inherit the
 * activity's runtime bar-icon appearance; without this a light-theme session
 * drew dark icons onto the black player.
 */
@Composable
private fun DialogDarkSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
        val controller = WindowInsetsControllerCompat(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
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

/** 48dp: the accessibility minimum. At 36dp the exit button was easy to miss. */
private val CORNER_BUTTON_SIZE = 48.dp
