package com.falcor.viewer.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * Holds a stable [WebView] reference so AppBar mute can run
 * [WebView.evaluateJavascript] synchronously on the click path
 * (user gesture → unmuted autoplay). Sole owner of listen mute — no HTML5 controls.
 */
class WebViewAudioController {
    @Volatile
    var webView: WebView? = null
        private set

    /** One-shot: WebView asks Compose to show "Tap for sound" (not a second mute control). */
    @Volatile
    var onNeedsTapForSound: (() -> Unit)? = null

    fun attach(view: WebView) {
        webView = view
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    /**
     * Apply mute/unmute + play immediately (call from AppBar onClick / user gesture).
     * Sets video/audio.muted, volume, audioTracks + play + AudioContext.resume.
     * Never clicks page mute UI. Optional [onReadBack] is a single probe after apply
     * for icon honesty — callers must NOT treat page muted as Compose source of truth
     * when wanting unmuted (go2rtc autoplay-muted must not flip AppBar to muted).
     */
    fun applyMute(muted: Boolean, onReadBack: ((Boolean) -> Unit)? = null) {
        val wv = webView ?: return
        val js = applyMuteJs(muted)
        val after: ((String?) -> Unit)? = if (onReadBack != null) {
            {
                wv.evaluateJavascript(READ_MUTED_JS) { raw ->
                    onReadBack(parseJsBoolean(raw, fallback = muted))
                }
            }
        } else null
        try {
            wv.evaluateJavascript(js, after)
        } catch (_: Throwable) {
            wv.post {
                runCatching { wv.evaluateJavascript(js, after) }
            }
        }
    }
}

private fun parseJsBoolean(raw: String?, fallback: Boolean): Boolean {
    if (raw == null) return fallback
    val t = raw.trim().trim('"')
    return when (t) {
        "true", "1" -> true
        "false", "0" -> false
        else -> fallback
    }
}

private const val READ_MUTED_JS = """
(function(){
  function collect(root){
    var list = [];
    try { root.querySelectorAll('video,audio').forEach(function(v){ list.push(v); }); } catch(e) {}
    try {
      root.querySelectorAll('iframe').forEach(function(f){
        try {
          var doc = f.contentDocument || (f.contentWindow && f.contentWindow.document);
          if (doc) collect(doc).forEach(function(v){ list.push(v); });
        } catch(e) {}
      });
    } catch(e) {}
    return list;
  }
  var media = collect(document);
  if (!media.length) return String(!!window.__falcorMuted);
  return String(!!media[0].muted);
})();
"""

private const val SILENT_PLAYING_JS = """
(function(){
  if (!!window.__falcorMuted) return 'ok';
  function collect(root){
    var list = [];
    try { root.querySelectorAll('video,audio').forEach(function(v){ list.push(v); }); } catch(e) {}
    try {
      root.querySelectorAll('iframe').forEach(function(f){
        try {
          var doc = f.contentDocument || (f.contentWindow && f.contentWindow.document);
          if (doc) collect(doc).forEach(function(v){ list.push(v); });
        } catch(e) {}
      });
    } catch(e) {}
    return list;
  }
  var playing = false, silent = false;
  collect(document).forEach(function(v){
    try {
      if (!v.paused && v.readyState >= 2) playing = true;
      if (v.muted || v.volume === 0) silent = true;
      if (v.srcObject && v.srcObject.getAudioTracks) {
        var tracks = v.srcObject.getAudioTracks();
        if (tracks && tracks.length && tracks.every(function(t){ return !t.enabled; })) silent = true;
      }
    } catch(e) {}
  });
  return (playing && silent) ? 'silent' : 'ok';
})();
"""

/**
 * Authenticated WebView hosting Frigate/go2rtc MSE or WebRTC live / talk player pages.
 *
 * - Autoplay + unmuted HTML5 video (live audio); native media controls suppressed
 * - Grants CAMERA / AUDIO_CAPTURE for getUserMedia (two-way talk)
 * - AppBar mute is the single owner via [WebViewAudioController.applyMute]
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
    /** AppBar-driven mute — injects JS on <video>/<audio>; HTML5 controls hidden. */
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
    var showTapForSound by remember(pageUrls) { mutableStateOf(false) }

    LaunchedEffect(pageUrls) {
        candidateIndex = 0
        exhausted = false
        loading = true
        showTapForSound = false
        attempt.set(0)
    }

    // Keep controller mute in sync when StateFlow changes (e.g. fullscreen / talk).
    LaunchedEffect(muted) {
        controllerState?.applyMute(muted)
        if (muted) showTapForSound = false
    }

    // Request STREAM_MUSIC audio focus so live WebView audio is audible.
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
                muted = mutedState,
                audioController = controllerState,
                onPlaying = {
                    loading = false
                    onPlayingState?.invoke()
                    // After media starts: unmute+play once so streams are not stuck muted.
                    if (!mutedState) {
                        controllerState?.applyMute(false)
                        val wv = controllerState?.webView
                        if (wv != null) {
                            val handler = Handler(Looper.getMainLooper())
                            // After delayed retries, if still silent-but-playing → one-shot tap hint.
                            handler.postDelayed({
                                if (mutedState) return@postDelayed
                                runCatching {
                                    wv.evaluateJavascript(SILENT_PLAYING_JS) { raw ->
                                        val t = raw?.trim()?.trim('"')
                                        if (t == "silent" && !mutedState) {
                                            showTapForSound = true
                                            controllerState?.onNeedsTapForSound?.invoke()
                                        }
                                    }
                                }
                            }, 2800L)
                        }
                    }
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
        // One-shot "Tap for sound" when silent-but-playing after retries — not a second mute.
        if (showTapForSound && !mutedState && !exhausted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0x66000000))
                    .clickable {
                        controllerState?.applyMute(false)
                        showTapForSound = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Tap for sound",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

/** Hide go2rtc/Frigate page chrome + suppress native HTML5 media controls (AppBar owns mute). */
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
  function applyOne(v){
    try {
      var muted = !!window.__falcorMuted;
      v.controls = false;
      v.removeAttribute('controls');
      v.setAttribute('playsinline','');
      v.setAttribute('webkit-playsinline','');
      v.muted = muted;
      v.defaultMuted = muted;
      try { v.volume = muted ? 0.0 : 1.0; } catch(e) {}
      if (v.srcObject && v.srcObject.getAudioTracks) {
        v.srcObject.getAudioTracks().forEach(function(t){ try { t.enabled = !muted; } catch(e) {} });
      }
      if (typeof v.getAudioTracks === 'function') {
        try { v.getAudioTracks().forEach(function(t){ t.enabled = !muted; }); } catch(e) {}
      }
      if (!v.__falcorMuteHooked) {
        v.__falcorMuteHooked = true;
        ['loadedmetadata','playing','play','canplay'].forEach(function(ev){
          v.addEventListener(ev, function(){ applyOne(v); });
        });
        try {
          if (v.srcObject && v.srcObject.addEventListener) {
            v.srcObject.addEventListener('addtrack', function(){ applyOne(v); });
          }
        } catch(e) {}
        v.addEventListener('volumechange', function(){
          try {
            // AppBar owns mute via __falcorMuted — re-apply if page flips; do not push into Compose.
            if (!!v.muted !== !!window.__falcorMuted) {
              applyOne(v);
            }
          } catch(e) {}
        });
      }
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
          'video::-webkit-media-controls,video::-webkit-media-controls-enclosure,video::-webkit-media-controls-panel,video::-webkit-media-controls-mute-button,video::-webkit-media-controls-volume-slider,video::-webkit-media-controls-timeline,video::-webkit-media-controls-current-time-display,video::-webkit-media-controls-time-remaining-display,video::-webkit-media-controls-play-button,video::-webkit-media-controls-overlay-play-button{display:none!important;opacity:0!important;visibility:hidden!important;pointer-events:none!important;width:0!important;height:0!important;}',
          'nav,aside,header,footer,[class*=sidebar],[class*=history],[class*=History],[class*=timeline],[class*=Timeline],[class*=review],[id*=sidebar]{display:none!important;visibility:hidden!important;width:0!important;height:0!important;}'
        ].join('');
        (document.head || document.documentElement).appendChild(s);
      }
      collectMedia(document).forEach(applyOne);
    } catch(e) {}
  }
  stylePlayer();
  if (!window.__falcorPlayerTimer) {
    window.__falcorPlayerTimer = setInterval(stylePlayer, 800);
  }
  if (!window.__falcorFirstTap) {
    window.__falcorFirstTap = true;
    var once = function(){
      try {
        if (!window.__falcorMuted) {
          window.__falcorMuted = false;
          collectMedia(document).forEach(applyOne);
          try {
            var Ctx = window.AudioContext || window.webkitAudioContext;
            if (Ctx && window.__falcorAudioCtx && window.__falcorAudioCtx.state === 'suspended') {
              window.__falcorAudioCtx.resume();
            }
          } catch(e) {}
        }
      } catch(e) {}
    };
    document.addEventListener('touchend', once, {passive:true, once:true});
    document.addEventListener('click', once, {passive:true, once:true});
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
  function applyMedia(v){
    try {
      v.controls = false;
      v.removeAttribute('controls');
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
      if (!v.__falcorMuteHooked) {
        v.__falcorMuteHooked = true;
        ['loadedmetadata','playing','play','canplay'].forEach(function(ev){
          v.addEventListener(ev, function(){ applyMedia(v); });
        });
        try {
          if (v.srcObject && v.srcObject.addEventListener) {
            v.srcObject.addEventListener('addtrack', function(){ applyMedia(v); });
          }
        } catch(e) {}
      }
      var p = v.play();
      if (p && p.catch) p.catch(function(){});
    } catch(e) {}
  }
  collectMedia(document).forEach(applyMedia);
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
                // Touches reach the video surface (first-tap unmute gesture); HTML5 controls are hidden.
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

                controller?.attach(this)

                // Optional bridge: page may request one-shot "Tap for sound" (not mute SoT).
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun needsTapForSound() {
                        Handler(Looper.getMainLooper()).post {
                            controller?.onNeedsTapForSound?.invoke()
                        }
                    }
                }, "FalcorAudio")

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
                        // AppBar mute state → video/audio; controls suppressed; late media retries.
                        view?.evaluateJavascript(muteFlagJs(mutedFlag) + PLAYER_CHROME_JS, null)
                        view?.evaluateJavascript(applyMuteJs(mutedFlag), null)
                        // Retry apply after load — MSE/WebRTC / iframe media often attaches late.
                        if (view != null) {
                            val handler = Handler(Looper.getMainLooper())
                            val want = mutedFlag
                            listOf(400L, 1200L, 2500L, 5000L).forEach { delayMs ->
                                handler.postDelayed({
                                    runCatching {
                                        view.evaluateJavascript(applyMuteJs(want), null)
                                    }
                                }, delayMs)
                            }
                        }
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
