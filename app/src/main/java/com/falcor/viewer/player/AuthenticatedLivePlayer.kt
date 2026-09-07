package com.falcor.viewer.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated live stream via ExoPlayer (Media3) + OkHttp DataSource (JWT/TLS).
 *
 * Demoted for live open path (WebView-primary). Kept for optional explicit
 * [preferNativeLive] fallback. PlayerView alone — no Compose "Connecting" overlay.
 * Ready timeout (~9s) calls [onError] if playback never reaches STATE_READY.
 */
@OptIn(UnstableApi::class)
@Composable
fun AuthenticatedLivePlayer(
    streamUrl: String?,
    okHttpClient: OkHttpClient,
    modifier: Modifier = Modifier,
    mute: Boolean = false,
    onError: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val onErrorState by rememberUpdatedState(onError)
    val reachedReady = remember { AtomicBoolean(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            volume = if (mute) 0f else 1f
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    reachedReady.set(true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onErrorState?.invoke()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(mute) {
        player.volume = if (mute) 0f else 1f
    }

    LaunchedEffect(streamUrl, okHttpClient) {
        reachedReady.set(false)
        if (streamUrl.isNullOrBlank()) {
            player.stop()
            return@LaunchedEffect
        }
        val factory = OkHttpDataSource.Factory(
            okHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        val dataSourceFactory = DefaultDataSource.Factory(context, factory)
        val mediaItem = MediaItem.fromUri(streamUrl)
        val mediaSource = if (isHlsUrl(streamUrl)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        player.volume = if (mute) 0f else 1f

        // Ready timeout so Exo never sits forever on a black bar / buffering UI.
        delay(9_000L)
        if (!reachedReady.get()) {
            onErrorState?.invoke()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                    controllerShowTimeoutMs = 3_000
                    controllerHideOnTouch = true
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.player = player
                view.useController = true
            }
        )
    }
}

internal fun isHlsUrl(url: String): Boolean =
    url.contains("m3u8", ignoreCase = true)

/** ExoPlayer-friendly live candidates (HLS or go2rtc/Frigate MP4). */
internal fun isExoLiveCandidate(url: String): Boolean {
    val u = url.lowercase()
    return u.contains("m3u8") ||
        u.contains("stream.mp4") ||
        (u.contains("go2rtc") && u.contains(".mp4"))
}
