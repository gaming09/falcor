package com.falcor.viewer.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.falcor.viewer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live camera preview using the app's trusted OkHttp client (JWT + self-signed TLS).
 * Prefers Frigate continuous MJPEG; falls back to polling `latest.jpg` so the user
 * always sees a moving preview when LibVLC fails on auth/TLS.
 */
@Composable
fun OkHttpLivePreview(
    mjpegUrl: String,
    snapshotUrl: String,
    okHttpClient: OkHttpClient,
    modifier: Modifier = Modifier,
    snapshotIntervalMs: Long = 350L,
    /** Prefer polled latest.jpg (lighter for home grid with many cameras). */
    snapshotOnly: Boolean = false,
    onPlaying: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onErrorState by rememberUpdatedState(onError)

    val streamClient = remember(okHttpClient) {
        okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    val snapshotClient = remember(okHttpClient) {
        okHttpClient.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val cancelled = remember { AtomicBoolean(false) }
    DisposableEffect(mjpegUrl, snapshotUrl) {
        cancelled.set(false)
        onDispose { cancelled.set(true) }
    }

    LaunchedEffect(mjpegUrl, snapshotUrl, streamClient, snapshotClient, snapshotOnly) {
        loading = true
        errorText = null
        var gotFrame = false

        if (!snapshotOnly) {
            try {
                gotFrame = withContext(Dispatchers.IO) {
                    streamJpegFrames(streamClient, mjpegUrl, cancelled) { frame ->
                        withContext(Dispatchers.Main.immediate) {
                            bitmap?.takeIf { it !== frame && !it.isRecycled }?.recycle()
                            bitmap = frame
                            loading = false
                            onPlayingState?.invoke()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MJPEG stream failed: ${t.message}")
            }
        }

        if (!isActive || cancelled.get()) return@LaunchedEffect

        if (!gotFrame) {
            if (!snapshotOnly) Log.i(TAG, "Falling back to snapshot poll: $snapshotUrl")
            while (isActive && !cancelled.get()) {
                try {
                    val frame = withContext(Dispatchers.IO) {
                        fetchJpegBitmap(snapshotClient, snapshotUrl)
                    }
                    if (frame != null) {
                        bitmap?.takeIf { it !== frame && !it.isRecycled }?.recycle()
                        bitmap = frame
                        loading = false
                        errorText = null
                        onPlayingState?.invoke()
                        gotFrame = true
                    } else if (!gotFrame) {
                        errorText = "preview_http"
                        loading = false
                        onErrorState?.invoke(errorText!!)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Snapshot poll failed: ${t.message}")
                    if (!gotFrame) {
                        errorText = t.message ?: "preview_error"
                        loading = false
                        onErrorState?.invoke(errorText!!)
                    }
                }
                delay(snapshotIntervalMs)
            }
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val frame = bitmap
        if (frame != null && !frame.isRecycled) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = stringResource(R.string.camera_live_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (loading && errorText == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        if (errorText != null && frame == null) {
            Text(
                text = stringResource(R.string.camera_error_stream),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Reads an HTTP response and extracts successive JPEG frames by SOI/EOI markers.
 * Works for multipart/x-mixed-replace MJPEG and for a single JPEG body.
 */
private suspend fun streamJpegFrames(
    client: OkHttpClient,
    url: String,
    cancelled: AtomicBoolean,
    onFrame: suspend (Bitmap) -> Unit
): Boolean {
    val request = Request.Builder().url(url).get().build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            Log.w(TAG, "MJPEG HTTP ${response.code} for $url")
            return false
        }
        val body = response.body ?: return false
        val contentType = body.contentType()?.toString().orEmpty()
        var gotAny = false

        // Single non-streaming JPEG
        val isMjpeg = contentType.contains("multipart", ignoreCase = true) ||
            contentType.contains("mjpeg", ignoreCase = true) ||
            contentType.contains("x-mixed-replace", ignoreCase = true)
        if (!isMjpeg && contentType.contains("image/jpeg", ignoreCase = true)) {
            val bytes = body.bytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            onFrame(bmp)
            return true
        }

        val input = BufferedInputStream(body.byteStream(), 64 * 1024)
        val jpeg = ByteArrayOutputStream(128 * 1024)
        var inFrame = false
        var prev = -1
        while (!cancelled.get()) {
            val b = input.read()
            if (b < 0) break
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    jpeg.reset()
                    jpeg.write(0xFF)
                    jpeg.write(0xD8)
                    inFrame = true
                }
            } else {
                jpeg.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    inFrame = false
                    val bytes = jpeg.toByteArray()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        gotAny = true
                        onFrame(bmp)
                    }
                }
                // Guard against runaway frames
                if (jpeg.size() > 8 * 1024 * 1024) {
                    inFrame = false
                    jpeg.reset()
                }
            }
            prev = b
        }
        return gotAny
    }
}

private fun fetchJpegBitmap(client: OkHttpClient, url: String): Bitmap? {
    val request = Request.Builder().url(url).get().build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val bytes = response.body?.bytes() ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}

private const val TAG = "OkHttpLivePreview"
