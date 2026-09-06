package com.falcor.viewer.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.falcor.viewer.R
import com.falcor.viewer.data.media.ClipSaver
import com.falcor.viewer.data.media.MediaDownloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Progressive clip playback via ExoPlayer + OkHttp DataSource (JWT/TLS).
 * Falls back to download-to-cache then local file if progressive fails.
 * Exposes a real seek Slider bound to duration/position.
 */
@OptIn(UnstableApi::class)
@Composable
fun AuthenticatedClipPlayer(
    remoteUrl: String?,
    okHttpClient: OkHttpClient,
    modifier: Modifier = Modifier,
    mute: Boolean = false,
    downloadFileName: String? = null,
    onError: ((String) -> Unit)? = null,
    onDownloadResult: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var buffering by remember { mutableStateOf(false) }
    var errorKind by remember { mutableStateOf<ErrorKind?>(null) }
    var localFallback by remember { mutableStateOf<File?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var downloadingSave by remember { mutableStateOf(false) }
    val onErrorState by rememberUpdatedState(onError)
    val onDownloadState by rememberUpdatedState(onDownloadResult)

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            volume = if (mute) 0f else 1f
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                // Progressive failed — try download-then-play once
                val url = remoteUrl
                if (localFallback == null && !url.isNullOrBlank() && !url.startsWith("file:")) {
                    scope.launch {
                        buffering = true
                        MediaDownloader.clearOldCache(context)
                        when (val result = MediaDownloader.downloadToCache(context, okHttpClient, url)) {
                            is MediaDownloader.DownloadResult.Success -> {
                                localFallback = result.file
                                errorKind = null
                                playLocal(player, result.file)
                                buffering = false
                            }
                            is MediaDownloader.DownloadResult.NotFound -> {
                                errorKind = ErrorKind.NotFound
                                buffering = false
                                onErrorState?.invoke("not_found")
                            }
                            is MediaDownloader.DownloadResult.Failed -> {
                                errorKind = ErrorKind.Failed
                                buffering = false
                                onErrorState?.invoke(result.message)
                            }
                        }
                    }
                } else {
                    errorKind = ErrorKind.Failed
                    buffering = false
                    onErrorState?.invoke(error.message ?: "playback_error")
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(remoteUrl, okHttpClient, mute) {
        localFallback = null
        errorKind = null
        durationMs = 0L
        positionMs = 0L
        sliderValue = 0f
        player.volume = if (mute) 0f else 1f
        if (remoteUrl.isNullOrBlank()) {
            player.stop()
            buffering = false
            return@LaunchedEffect
        }
        buffering = true
        if (remoteUrl.startsWith("file:")) {
            val path = remoteUrl.removePrefix("file://")
            playLocal(player, File(path))
            buffering = false
            return@LaunchedEffect
        }
        // Prefer progressive HTTP via OkHttp (JWT + trusted TLS)
        val factory = OkHttpDataSource.Factory(
            okHttpClient.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(context, factory)
        ).createMediaSource(MediaItem.fromUri(remoteUrl))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(player) {
        while (isActive) {
            if (!scrubbing && player.duration > 0) {
                durationMs = player.duration
                positionMs = player.currentPosition.coerceAtLeast(0L)
                sliderValue = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
            }
            delay(250)
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                    }
                },
                update = { it.player = player }
            )
            when {
                buffering && errorKind == null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.media_buffering),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                errorKind == ErrorKind.NotFound -> {
                    Text(
                        stringResource(R.string.media_not_found),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                errorKind == ErrorKind.Failed -> {
                    Text(
                        stringResource(R.string.media_download_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = {
                if (player.isPlaying) player.pause() else player.play()
            }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.media_play_pause)
                )
            }
            Text(
                formatMs(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = {
                    scrubbing = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    val seek = (sliderValue * durationMs).toLong().coerceAtLeast(0L)
                    player.seekTo(seek)
                    positionMs = seek
                    scrubbing = false
                },
                modifier = Modifier.weight(1f),
                enabled = durationMs > 0L
            )
            Text(
                formatMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                enabled = !remoteUrl.isNullOrBlank() && !downloadingSave,
                onClick = {
                    val url = remoteUrl ?: return@IconButton
                    downloadingSave = true
                    scope.launch {
                        val name = downloadFileName ?: "falcor_clip_${System.currentTimeMillis()}.mp4"
                        val local = localFallback
                        val result = if (local != null && local.exists()) {
                            ClipSaver.saveLocalFileToDownloads(context, local, name)
                        } else {
                            ClipSaver.saveToDownloads(context, okHttpClient, url, name)
                        }
                        downloadingSave = false
                        when (result) {
                            is ClipSaver.SaveResult.Success ->
                                onDownloadState?.invoke(result.displayName)
                            is ClipSaver.SaveResult.Failed ->
                                onDownloadState?.invoke("error:${result.message}")
                        }
                    }
                }
            ) {
                if (downloadingSave) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.media_download)
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun playLocal(player: ExoPlayer, file: File) {
    player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
    player.prepare()
    player.playWhenReady = true
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

private enum class ErrorKind { NotFound, Failed }
