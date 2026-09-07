package com.falcor.viewer.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioManager
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
 * Holds a stable [WebView] reference so mute/unmute can run
 * [WebView.evaluateJavascript] synchronously on the mute-button click path
 * (user gesture → unmuted autoplay).
 */
class WebViewAudioController {
    @Volatile
    var webView: WebView? = null
        private set

    fun attach(view: WebView) {
        webView = view
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    /**
     * Apply mute/unmute + play immediately (call from button onClick / user gesture).
     * Only touches video/audio elements + AudioContext — never clicks page mute UI
     * (those clicks can flip go2rtc/Frigate stream controls).
     */
    fun applyMute(muted: Boolean) {
        val wv = webView ?: return
        val js = applyMuteJs(muted)
        // Click path is on the UI thread — evaluate now so unmuted play shares the gesture.
        try {
            wv.evaluateJavascript(js, null)
        } catch (_: Throwable) {
            wv.post { runCatching { wv.evaluateJavascript(js, null) } }
        }
    }
}

/**
 * Authenticated WebView hosting Frigate/go2rtc MSE or WebRTC live / talk player pages.
 *
 * - Autoplay + unmuted HTML5 video (live audio)
 * - Grants CAMERA / AUDIO_CAPTURE for getUserMedia (two-way talk)
 * - Compose mute drives JS via [WebViewAudioController] (user-gesture safe)
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
    /** Compose-driven mute — injects JS on <video>/<audio> (HTML speaker is stripped). */
    muted: Boolean = false,
    audioController: WebViewAudioController? = null,
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
    val mutedState by rememberUpdatedState(muted)
    val urlsState by rememberUpdatedState(pageUrls)
    val controllerState by rememberUpdatedState(audioController)
    val attempt = remember { AtomicInteger(0) }
    val pageUrl = pageUrls.getOrNull(candidateIndex)
    val context = LocalContext.current

    LaunchedEffect(pageUrls) {
        candidateIndex = 0
        exhausted = false
        loading = true
        attempt.set(0)
    }

    // Keep controller mute in sync when StateFlow changes (e.g. fullscreen / talk).
    LaunchedEffect(muted) {
        controllerState?.applyMute(muted)
    }

    // Request audio focus so live audio is audible over other apps when possible.
    DisposableEffect(Unit) {
        val am = context.getSystemService(AudioManager::class.java)
        val result = am?.requestAudioFocus(
            { },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        onDispose {
            if (result != null) {
                am?.abandonAudioFocus { }
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
                muted = mutedState,
                audioController = controllerState,
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
}

/** Strip HTML5/Frigate chrome; pointer-events:none so Compose overlay owns taps. */
private const val PLAYER_CHROME_JS = """
(function(){
  function stylePlayer(){
    try {
      if (!document.getElementById('falcor-player-css')) {
        var s = document.createElement('style');
        s.id = 'falcor-player-css';
        s.textContent = [
          'html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;width:100%!important;height:100%!important;pointer-events:none!important;}',
          '*{pointer-events:none!important;}',
          'video{width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;position:fixed!important;inset:0!important;z-index:1!important;pointer-events:none!important;}',
          'video::-webkit-media-controls{display:none!important;}',
          'video::-webkit-media-controls-enclosure{display:none!important;}',
          'video::-webkit-media-controls-panel{display:none!important;}',
          'video::-webkit-media-controls-start-playback-button{display:none!important;}',
          'video::-webkit-media-controls-mute-button{display:none!important;}',
          'video::-webkit-media-controls-volume-slider{display:none!important;}',
          'video::-webkit-media-controls-overlay-play-button{display:none!important;}',
          'audio{display:none!important;}',
          'nav,aside,header,footer,[class*=sidebar],[class*=history],[class*=History],[class*=timeline],[class*=Timeline],[class*=review],[id*=sidebar]{display:none!important;visibility:hidden!important;width:0!important;height:0!important;}',
          '.vjs-control-bar,.video-js .vjs-big-play-button,button.play,[class*=control],[class*=Controls],.plyr__controls,[class*=mute],[class*=Mute],[class*=volume],[class*=Volume],.mute-button,.volume-button,button,a,[role=button]{display:none!important;opacity:0!important;pointer-events:none!important;visibility:hidden!important;}'
        ].join('');
        (document.head || document.documentElement).appendChild(s);
      }
      var muted = !!window.__falcorMuted;
      document.querySelectorAll('video,audio').forEach(function(v){
        try {
          v.removeAttribute('controls');
          v.controls = false;
          v.setAttribute('playsinline','');
          v.setAttribute('webkit-playsinline','');
          v.muted = muted;
          v.defaultMuted = muted;
          v.volume = muted ? 0.0 : 1.0;
          if (v.srcObject && v.srcObject.getAudioTracks) {
            v.srcObject.getAudioTracks().forEach(function(t){ t.enabled = !muted; });
          }
          var p = v.play();
          if (p && p.catch) p.catch(function(){});
        } catch(e) {}
      });
    } catch(e) {}
  }
  stylePlayer();
  if (!window.__falcorPlayerTimer) {
    window.__falcorPlayerTimer = setInterval(stylePlayer, 800);
  }
  document.addEventListener('DOMContentLoaded', stylePlayer);
})();
"""

private fun muteFlagJs(muted: Boolean): String =
    "window.__falcorMuted = ${if (muted) "true" else "false"};"

internal fun applyMuteJs(muted: Boolean): String = """
(function(){
  var wantMuted = ${if (muted) "true" else "false"};
  window.__falcorMuted = wantMuted;
  function applyMedia(v){
    try {
      v.removeAttribute('controls');
      v.controls = false;
      v.setAttribute('playsinline','');
      v.setAttribute('webkit-playsinline','');
      v.muted = wantMuted;
      v.defaultMuted = wantMuted;
      try { v.volume = wantMuted ? 0.0 : 1.0; } catch(e) {}
      if (v.srcObject && v.srcObject.getAudioTracks) {
        v.srcObject.getAudioTracks().forEach(function(t){ try { t.enabled = !wantMuted; } catch(e) {} });
      }
      if (typeof v.getAudioTracks === 'function') {
        try { v.getAudioTracks().forEach(function(t){ t.enabled = !wantMuted; }); } catch(e) {}
      }
      var p = v.play();
      if (p && p.catch) p.catch(function(){});
    } catch(e) {}
  }
  document.querySelectorAll('video,audio').forEach(applyMedia);
  // Resume any AudioContext created by the page (MSE/WebRTC players).
  try {
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (Ctx) {
      if (!window.__falcorAudioCtx) {
        try { window.__falcorAudioCtx = new Ctx(); } catch(e) {}
      }
      var ctxs = [];
      if (window.__falcorAudioCtx) ctxs.push(window.__falcorAudioCtx);
      try {
        if (typeof window.__audioContexts !== 'undefined' && window.__audioContexts && window.__audioContexts.forEach) {
          window.__audioContexts.forEach(function(c){ ctxs.push(c); });
        }
      } catch(e) {}
      ctxs.forEach(function(c){
        try { if (c && c.state === 'suspended') c.resume(); } catch(e) {}
      });
    }
  } catch(e) {}
  // Do NOT click page mute/volume buttons — that can change go2rtc/Frigate stream UI.
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun keyAndroidView(
    pageUrl: String,
    bearerToken: String?,
    showDetections: Boolean,
    allowMicrophone: Boolean,
    muted: Boolean,
    audioController: WebViewAudioController?,
    onPlaying: () -> Unit,
    onMainFrameError: () -> Unit,
    onStarted: () -> Unit
) {
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onErrorState by rememberUpdatedState(onMainFrameError)
    val showDet by rememberUpdatedState(showDetections)
    val allowMic by rememberUpdatedState(allowMicrophone)
    val mutedFlag by rememberUpdatedState(muted)
    val controller by rememberUpdatedState(audioController)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Compose overlay owns taps (Falcor mute bar); never open Frigate SPA chrome.
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
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

                controller?.attach(this)

                val token = bearerToken?.trim()?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    injectAuthCookie(pageUrl, "frigate_token", token)
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (request == null) return
                        val wanted = request.resources ?: return
                        val grant = wanted.filter {
                            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                        }.toTypedArray()
                        android.util.Log.d(
                            "FrigateLiveWebView",
                            "onPermissionRequest origin=${request.origin} resources=${wanted.toList()} grant=${grant.toList()} allowMic=$allowMic"
                        )
                        if (grant.isNotEmpty()) {
                            request.grant(grant)
                        } else {
                            request.grant(wanted)
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
                            "(function(){var s=document.createElement('style');s.innerHTML='html,body{margin:0;background:#000;overflow:hidden;width:100%;height:100%;}video{width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;}video::-webkit-media-controls{display:none!important;}';document.head.appendChild(s);$hideBoxes})();",
                            null
                        )
                        // Start unmuted unless Compose says muted; autoplay-with-sound may still
                        // fail until mute-button tap (user gesture) re-applies unmute+play.
                        view?.evaluateJavascript(muteFlagJs(mutedFlag) + PLAYER_CHROME_JS, null)
                        view?.evaluateJavascript(applyMuteJs(mutedFlag), null)
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
            @Suppress("UNUSED_EXPRESSION")
            allowMic
            controller?.attach(webView)
            if (webView.url != pageUrl) {
                val token = bearerToken?.trim()?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")?.trim().orEmpty()
                if (token.isNotEmpty()) injectAuthCookie(pageUrl, "frigate_token", token)
                val headers = mutableMapOf<String, String>()
                if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
                webView.loadUrl(pageUrl, headers)
            } else {
                webView.evaluateJavascript(muteFlagJs(mutedFlag) + PLAYER_CHROME_JS, null)
                webView.evaluateJavascript(applyMuteJs(mutedFlag), null)
            }
        },
        onRelease = { webView ->
            controller?.detach(webView)
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
