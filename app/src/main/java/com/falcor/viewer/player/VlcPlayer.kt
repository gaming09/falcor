package com.falcor.viewer.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.falcor.viewer.R
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * LibVLC Compose wrapper for live RTSP/HTTP and recording playback.
 */
@Composable
fun VlcPlayer(
    mediaUrl: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    playWhenReady: Boolean = true,
    mute: Boolean = false,
    onError: ((String) -> Unit)? = null,
    onPlaying: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isBuffering by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val libVlc = remember {
        LibVLC(
            context,
            arrayListOf(
                "--rtsp-tcp",
                "--network-caching=300",
                "--live-caching=300",
                "--file-caching=300",
                "--sout-mux-caching=300",
                "--avcodec-hw=any",
                "-vvv"
            )
        )
    }
    val mediaPlayer = remember { MediaPlayer(libVlc) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVlc.release()
        }
    }

    LaunchedEffect(mediaUrl, playWhenReady, mute) {
        errorText = null
        isBuffering = true
        mediaPlayer.stop()
        if (mediaUrl.isNullOrBlank()) {
            isBuffering = false
            return@LaunchedEffect
        }
        try {
            val media = Media(libVlc, android.net.Uri.parse(mediaUrl))
            headers.forEach { (k, v) ->
                // LibVLC HTTP headers via option
                media.addOption(":http-user-agent=Falcor/1.0")
                if (k.equals("Authorization", true)) {
                    media.addOption(":http-password=${v.removePrefix("Bearer ").trim()}")
                    // Also pass as custom header when supported
                    media.addOption(":http-extra-headers=$k: $v")
                }
            }
            media.setHWDecoderEnabled(true, false)
            mediaPlayer.media = media
            media.release()
            mediaPlayer.volume = if (mute) 0 else 100
            if (playWhenReady) mediaPlayer.play()
        } catch (t: Throwable) {
            errorText = t.message
            isBuffering = false
            onError?.invoke(t.message ?: "error")
        }
    }

    DisposableEffect(mediaPlayer) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    isBuffering = event.buffering < 100f
                }
                MediaPlayer.Event.Playing -> {
                    isBuffering = false
                    errorText = null
                    onPlaying?.invoke()
                }
                MediaPlayer.Event.EncounteredError -> {
                    isBuffering = false
                    errorText = "stream_error"
                    onError?.invoke("stream_error")
                }
                MediaPlayer.Event.EndReached -> {
                    isBuffering = false
                }
            }
        }
        mediaPlayer.setEventListener(listener)
        onDispose { mediaPlayer.setEventListener(null) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    layout.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    mediaPlayer.attachViews(layout, null, false, false)
                }
            },
            update = { /* attached once */ }
        )
        if (isBuffering && errorText == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        if (errorText != null) {
            Text(
                text = stringResource(R.string.camera_error_stream),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Imperative controller helpers for seeking recordings via URL swap. */
class VlcController {
    var currentUrl: String? = null
        private set

    fun playUrl(url: String) {
        currentUrl = url
    }

    fun clear() {
        currentUrl = null
    }
}
