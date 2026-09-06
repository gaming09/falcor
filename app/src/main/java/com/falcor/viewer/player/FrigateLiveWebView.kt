package com.falcor.viewer.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.falcor.viewer.R
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authenticated WebView hosting Frigate/go2rtc MSE or WebRTC live player pages.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FrigateLiveWebView(
    pageUrls: List<String>,
    bearerToken: String?,
    modifier: Modifier = Modifier,
    fillAspect: Boolean = true,
    showDetections: Boolean = true,
    onPlaying: (() -> Unit)? = null,
    onAllFailed: (() -> Unit)? = null
) {
    var candidateIndex by remember(pageUrls) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var exhausted by remember { mutableStateOf(false) }
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onAllFailedState by rememberUpdatedState(onAllFailed)
    val showDetState by rememberUpdatedState(showDetections)
    val urlsState by rememberUpdatedState(pageUrls)
    val attempt = remember { AtomicInteger(0) }
    val pageUrl = pageUrls.getOrNull(candidateIndex)

    LaunchedEffect(pageUrls) {
        candidateIndex = 0
        exhausted = false
        loading = true
        attempt.set(0)
    }

    val boxMod = if (fillAspect) {
        modifier.fillMaxWidth().aspectRatio(16f / 9f)
    } else {
        modifier.fillMaxSize()
    }

    Box(
        modifier = boxMod.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (pageUrl != null && !exhausted) {
            keyAndroidView(
                pageUrl = pageUrl,
                bearerToken = bearerToken,
                showDetections = showDetState,
                onPlaying = { loading = false; onPlayingState?.invoke() },
                onMainFrameError = {
                    val next = candidateIndex + 1
                    if (next < urlsState.size) {
                        candidateIndex = next
                        loading = true
                    } else {
                        exhausted = true
                        loading = false
                        onAllFailedState?.invoke()
                    }
                },
                onStarted = { loading = true }
            )
        }
        if (loading && !exhausted) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        if (exhausted) {
            Text(
                stringResource(R.string.camera_error_stream),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun keyAndroidView(
    pageUrl: String,
    bearerToken: String?,
    showDetections: Boolean,
    onPlaying: () -> Unit,
    onMainFrameError: () -> Unit,
    onStarted: () -> Unit
) {
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onErrorState by rememberUpdatedState(onMainFrameError)
    val showDet by rememberUpdatedState(showDetections)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                val token = bearerToken?.trim()?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    injectAuthCookie(pageUrl, "frigate_token", token)
                }

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onStarted()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPlayingState()
                        val hideBoxes = if (!showDet) {
                            "var s2=document.createElement('style');s2.innerHTML='canvas,.bounding-box,[class*=detect]{display:none!important;}';document.head.appendChild(s2);"
                        } else ""
                        view?.evaluateJavascript(
                            "(function(){var s=document.createElement('style');s.innerHTML='body{margin:0;background:#000;overflow:hidden;}video{width:100%!important;height:100%!important;object-fit:contain!important;}';document.head.appendChild(s);$hideBoxes})();",
                            null
                        )
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) onErrorState()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        if (failingUrl == null || failingUrl == pageUrl) onErrorState()
                    }
                }

                val headers = mutableMapOf<String, String>()
                if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
                loadUrl(pageUrl, headers)
            }
        },
        update = { webView ->
            if (webView.url != pageUrl) {
                val token = bearerToken?.trim()?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")?.trim().orEmpty()
                if (token.isNotEmpty()) injectAuthCookie(pageUrl, "frigate_token", token)
                val headers = mutableMapOf<String, String>()
                if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
                webView.loadUrl(pageUrl, headers)
            }
        }
    )
}

internal fun injectAuthCookie(pageUrl: String, cookieName: String, token: String) {
    val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return
    val host = uri.host ?: return
    val secure = uri.scheme.equals("https", ignoreCase = true)
    val cookieManager = CookieManager.getInstance()
    val base = "${uri.scheme}://$host"
    val attrs = buildString {
        append("$cookieName=$token; Path=/")
        if (secure) append("; Secure")
    }
    cookieManager.setCookie(base, attrs)
    cookieManager.setCookie(pageUrl, attrs)
    cookieManager.flush()
}
