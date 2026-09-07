package com.falcor.viewer.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.falcor.viewer.R
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authenticated WebView hosting Frigate/go2rtc MSE or WebRTC live / talk player pages.
 *
 * - Native HTML5 controls stay visible (controls=true); user unmutes via the bar
 * - No AppBar mute / __falcorMuted / applyMute storms / Tap-for-sound overlay
 * - Grants CAMERA / AUDIO_CAPTURE for getUserMedia (two-way talk)
 * - Minimal chrome JS: black background + object-fit only (does not strip controls or force mute)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FrigateLiveWebView(
    pageUrls: List<String>,
    bearerToken: String?,
    modifier: Modifier = Modifier,
    fillAspect: Boolean = true,
    showDetections: Boolean = true,
    /** When true, WebView may request mic/camera for go2rtc talk. */
    allowMicrophone: Boolean = false,
    onPlaying: (() -> Unit)? = null,
    onAllFailed: (() -> Unit)? = null
) {
    var candidateIndex by remember(pageUrls) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var exhausted by remember { mutableStateOf(false) }
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onAllFailedState by rememberUpdatedState(onAllFailed)
    val showDetState by rememberUpdatedState(showDetections)
    val allowMicState by rememberUpdatedState(allowMicrophone)
    val urlsState by rememberUpdatedState(pageUrls)
    val attempt = remember { AtomicInteger(0) }
    val pageUrl = pageUrls.getOrNull(candidateIndex)
    val context = LocalContext.current

    LaunchedEffect(pageUrls) {
        candidateIndex = 0
        exhausted = false
        loading = true
        attempt.set(0)
    }

    // Request STREAM_MUSIC audio focus so live WebView audio is audible after unmute.
    DisposableEffect(Unit) {
        val am = context.getSystemService(AudioManager::class.java)
        var focusRequest: AudioFocusRequest? = null
        var legacyGranted = false
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    { },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                legacyGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }
        onDispose {
            if (am == null) return@onDispose
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            } else if (legacyGranted) {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus { }
            }
        }
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
                allowMicrophone = allowMicState,
                onPlaying = {
                    loading = false
                    onPlayingState?.invoke()
                },
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
}

/**
 * Minimal page chrome: black fill + object-fit. Enables native HTML5 controls.
 * Does NOT hide ::-webkit-media-controls*, force muted, or fight volumechange.
 */
private const val PLAYER_CHROME_JS = """
(function(){
  function collectMedia(root){
    var list = [];
    try {
      root.querySelectorAll('video,audio').forEach(function(v){ list.push(v); });
    } catch(e) {}
    try {
      root.querySelectorAll('iframe').forEach(function(f){
        try {
          var doc = f.contentDocument || (f.contentWindow && f.contentWindow.document);
          if (doc) collectMedia(doc).forEach(function(v){ list.push(v); });
        } catch(e) {}
      });
    } catch(e) {}
    return list;
  }
  function ensureControls(v){
    try {
      v.controls = true;
      v.setAttribute('controls','');
      v.setAttribute('playsinline','');
      v.setAttribute('webkit-playsinline','');
      var p = v.play();
      if (p && p.catch) p.catch(function(){});
    } catch(e) {}
  }
  function stylePlayer(){
    try {
      if (!document.getElementById('falcor-player-css')) {
        var s = document.createElement('style');
        s.id = 'falcor-player-css';
        s.textContent = [
          'html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;width:100%!important;height:100%!important;}',
          'video{width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;position:fixed!important;inset:0!important;z-index:1!important;pointer-events:auto!important;}',
          'nav,aside,header,footer,[class*=sidebar],[class*=history],[class*=History],[class*=timeline],[class*=Timeline],[class*=review],[id*=sidebar]{display:none!important;visibility:hidden!important;width:0!important;height:0!important;}'
        ].join('');
        (document.head || document.documentElement).appendChild(s);
      }
      collectMedia(document).forEach(ensureControls);
    } catch(e) {}
  }
  stylePlayer();
  if (!window.__falcorPlayerTimer) {
    window.__falcorPlayerTimer = setInterval(stylePlayer, 1200);
  }
  document.addEventListener('DOMContentLoaded', stylePlayer);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun keyAndroidView(
    pageUrl: String,
    bearerToken: String?,
    showDetections: Boolean,
    allowMicrophone: Boolean,
    onPlaying: () -> Unit,
    onMainFrameError: () -> Unit,
    onStarted: () -> Unit
) {
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onErrorState by rememberUpdatedState(onMainFrameError)
    val showDet by rememberUpdatedState(showDetections)
    val allowMic by rememberUpdatedState(allowMicrophone)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Touches reach native HTML5 control bar (mute/volume).
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                val token = bearerToken?.trim()?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    injectAuthCookie(pageUrl, "frigate_token", token)
                }

                // Mutable holder so update{} can refresh allowMic without recreating WebView.
                val micAllowed = booleanArrayOf(allowMic)
                setTag(com.falcor.viewer.R.id.falcor_webview_mic_tag, micAllowed)
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (request == null) return
                        val wanted = request.resources ?: return
                        val allow = (getTag(com.falcor.viewer.R.id.falcor_webview_mic_tag) as? BooleanArray)
                            ?.getOrNull(0) == true
                        // Always grant PROTECTED_MEDIA_ID (EME). AUDIO/VIDEO capture only for talk.
                        val grant = wanted.filter { res ->
                            when (res) {
                                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> allow
                                else -> false
                            }
                        }.toTypedArray()
                        android.util.Log.d(
                            "FrigateLiveWebView",
                            "onPermissionRequest origin=${request.origin} resources=${wanted.toList()} grant=${grant.toList()} allowMic=$allow"
                        )
                        if (grant.isNotEmpty()) {
                            request.grant(grant)
                        } else {
                            request.deny()
                        }
                    }
                }
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
                            "(function(){var s=document.createElement('style');s.innerHTML='html,body{margin:0;background:#000;overflow:hidden;width:100%;height:100%;}video{width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;pointer-events:auto!important;z-index:1!important;}';document.head.appendChild(s);$hideBoxes})();",
                            null
                        )
                        // Native controls visible; no mute flag / applyMute.
                        view?.evaluateJavascript(PLAYER_CHROME_JS, null)
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
            (webView.getTag(com.falcor.viewer.R.id.falcor_webview_mic_tag) as? BooleanArray)
                ?.set(0, allowMic)
            if (webView.url != pageUrl) {
                val token = bearerToken?.trim()?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")?.trim().orEmpty()
                if (token.isNotEmpty()) injectAuthCookie(pageUrl, "frigate_token", token)
                val headers = mutableMapOf<String, String>()
                if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
                webView.loadUrl(pageUrl, headers)
            } else {
                webView.evaluateJavascript(PLAYER_CHROME_JS, null)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
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
