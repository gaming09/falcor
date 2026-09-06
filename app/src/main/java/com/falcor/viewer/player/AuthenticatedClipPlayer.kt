package com.falcor.viewer.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.falcor.viewer.R
import com.falcor.viewer.data.media.MediaDownloader
import okhttp3.OkHttpClient
import java.io.File

/**
 * Downloads a Frigate clip/VOD URL via OkHttp (JWT + trusted TLS), then plays the
 * local file with LibVLC. Avoids VLC failing on HTTPS+JWT+self-signed for clip.mp4.
 */
@Composable
fun AuthenticatedClipPlayer(
    remoteUrl: String?,
    okHttpClient: OkHttpClient,
    modifier: Modifier = Modifier,
    mute: Boolean = false,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var localPath by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(false) }
    var errorKind by remember { mutableStateOf<ErrorKind?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(remoteUrl, okHttpClient) {
        localPath = null
        errorKind = null
        downloadedFile = null
        if (remoteUrl.isNullOrBlank()) {
            buffering = false
            return@LaunchedEffect
        }
        // Local file URLs skip download
        if (remoteUrl.startsWith("file:")) {
            localPath = remoteUrl
            buffering = false
            return@LaunchedEffect
        }
        buffering = true
        MediaDownloader.clearOldCache(context)
        when (val result = MediaDownloader.downloadToCache(context, okHttpClient, remoteUrl)) {
            is MediaDownloader.DownloadResult.Success -> {
                downloadedFile = result.file
                localPath = "file://${result.file.absolutePath}"
                buffering = false
            }
            is MediaDownloader.DownloadResult.NotFound -> {
                errorKind = ErrorKind.NotFound
                buffering = false
                onError?.invoke("not_found")
            }
            is MediaDownloader.DownloadResult.Failed -> {
                errorKind = ErrorKind.Failed
                buffering = false
                onError?.invoke(result.message)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Keep cache files for reuse; only drop reference
            downloadedFile = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            buffering -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.media_downloading),
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
            localPath != null -> {
                VlcPlayer(
                    mediaUrl = localPath,
                    headers = emptyMap(),
                    mute = mute,
                    modifier = Modifier.fillMaxSize(),
                    onError = { onError?.invoke(it) }
                )
            }
        }
    }
}

private enum class ErrorKind { NotFound, Failed }
