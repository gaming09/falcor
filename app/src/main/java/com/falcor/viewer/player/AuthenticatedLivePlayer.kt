package com.falcor.viewer.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Authenticated live HLS via ExoPlayer (Media3) + OkHttp DataSource (JWT/TLS).
 * Mute is [ExoPlayer.setVolume] 0f/1f — reliable compared to WebView autoplay policies.
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
    var buffering by remember { mutableStateOf(false) }
    val onErrorState by rememberUpdatedState(onError)

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
                buffering = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_READY) {
                    buffering = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
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
        if (streamUrl.isNullOrBlank()) {
            player.stop()
            buffering = false
            return@LaunchedEffect
        }
        buffering = true
        val factory = OkHttpDataSource.Factory(
            okHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        val dataSourceFactory = DefaultDataSource.Factory(context, factory)
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(streamUrl))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        player.volume = if (mute) 0f else 1f
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
                    useController = false
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player }
        )
        if (buffering) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
