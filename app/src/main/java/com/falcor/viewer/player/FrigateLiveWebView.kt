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
import android.webkit.JavascriptInterface
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
import androidx.compose.runtime.key
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
 * - 0.1.20-debug: FalcorAudioProbe snackbar + Log.i (probe-only; no product audio changes)
 * - 0.1.22-debug: keep probe; live src prefers A/V+listen, skips audio-only *_webrtc
 * - 0.1.26-debug: MSE-first for plain RTSP+listen; key(cameraName) + SPA embed lock
 *   (reload-once then fail). No JS candidate auto-advance / applyMute.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FrigateLiveWebView(
    pageUrls: List<String>,
    bearerToken: String?,
    /** Forces a fresh WebView instance per camera so prior SPA/pages cannot stick. */
    cameraName: String = "",
    modifier: Modifier = Modifier,
    fillAspect: Boolean = true,
    showDetections: Boolean = true,
    /** When true, WebView may request mic/camera for go2rtc talk. */
    allowMicrophone: Boolean = false,
    /** MAIN or SUB — included in probe snackbar only. */
    qualityLabel: String = "MAIN",
    /** Caps hasListenAudio hint for probe (0/1); does not gate playback. */
    hasListenAudio: Boolean? = null,
    /** Compact probe line for Snackbar screenshots. */
    onAudioProbe: ((String) -> Unit)? = null,
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
    val qualityState by rememberUpdatedState(qualityLabel)
    val hasListenState by rememberUpdatedState(hasListenAudio)
    val onProbeState by rememberUpdatedState(onAudioProbe)
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
            // Fresh WebView when camera or candidate URL changes — never reuse SPA DOM.
            key(cameraName, pageUrl) {
                keyAndroidView(
                    pageUrl = pageUrl,
                    bearerToken = bearerToken,
                    showDetections = showDetState,
                    allowMicrophone = allowMicState,
                    qualityLabel = qualityState,
                    hasListenAudio = hasListenState,
                    candidateIndex = candidateIndex,
                    onAudioProbe = onProbeState,
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
                    // SPA / non-embed after reload-once → fail to OkHttp; do not churn candidates.
                    onSpaExhaust = {
                        exhausted = true
                        loading = false
                        onAllFailedState?.invoke()
                    },
                    onStarted = { loading = true }
                )
            }
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
 * True for Frigate SPA camera routes that must never stay loaded in the live WebView.
 * Blocks `#cameras…` and `/cameras/…` (and `/cameras` end/query).
 */
internal fun isBlockedFrigateSpaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    if (lower.contains("#cameras")) return true
    // /cameras/, /cameras?, /cameras#, or ends with /cameras
    if (Regex("""/cameras(/|\?|#|$)""").containsMatchIn(lower)) return true
    return false
}

/**
 * Accept only go2rtc/Frigate live embed pages that carry an explicit `src=`.
 * Matches webrtc.html / mse.html / stream.html with a src= query, plus
 * live/webrtc pages that carry src= (talk index).
 */
internal fun isLiveEmbedUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    if (isBlockedFrigateSpaUrl(url)) return false
    val lower = url.lowercase()
    val hasSrc = Regex("""[?&]src=[^&]+""", RegexOption.IGNORE_CASE).containsMatchIn(url)
    if (!hasSrc) return false
    if (lower.contains("webrtc.html") || lower.contains("mse.html") || lower.contains("stream.html")) {
        return true
    }
    // Talk candidate: /live/webrtc/index.html?src=…
    if (lower.contains("/live/webrtc/") && lower.contains("src=")) return true
    if (lower.contains("/api/go2rtc/") && lower.contains("src=")) return true
    return false
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
          'nav,aside,header,footer,[class*=sidebar],[class*=history],[class*=History],[class*=timeline],[class*=Timeline],[class*=review],[id*=sidebar],[class*=MuiAppBar],[class*=appBar],[class*=toolbar],[class*=Toolbar],[class*=fab],[class*=Fab],[data-testid*=menu]{display:none!important;visibility:hidden!important;width:0!important;height:0!important;pointer-events:none!important;}',
          '[class*=control-bar],[class*=ControlBar],[class*=player-bar],[class*=PlayerBar],.fixed.inset-x-0{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}'
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

/**
 * Probe-only diagnostic (0.1.20). Reports via FalcorAudioProbe JavascriptInterface.
 * Does not change muted/volume/controls.
 */
private fun audioProbeJs(qualityLabel: String, candidateIndex: Int, hasListen: Int): String {
    val q = qualityLabel.replace("'", "").replace("\\", "")
    return """
(function(){
  var QUALITY = '$q';
  var CAND = $candidateIndex;
  var HAS_LISTEN = $hasListen;
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
  function pathKind(url){
    var u = (url || '').toLowerCase();
    if (u.indexOf('#cameras') >= 0 || /\/cameras(\/|\?|#|$)/.test(u)) return 'spa';
    if (u.indexOf('webrtc') >= 0) return 'webrtc';
    if (u.indexOf('mse') >= 0) return 'mse';
    if (u.indexOf('stream.html') >= 0 || u.indexOf('/stream') >= 0) return 'stream';
    if (u.indexOf('go2rtc') >= 0) return 'go2rtc';
    return 'other';
  }
  function srcParam(url){
    try {
      var m = /[?&]src=([^&]*)/i.exec(url || '');
      return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : '';
    } catch(e) { return ''; }
  }
  function trackInfo(tracks){
    var out = [];
    try {
      if (!tracks) return out;
      for (var i = 0; i < tracks.length; i++) {
        var t = tracks[i];
        out.push({
          id: t.id || '',
          label: t.label || '',
          kind: t.kind || '',
          enabled: !!t.enabled,
          muted: !!t.muted,
          readyState: t.readyState || ''
        });
      }
    } catch(e) {}
    return out;
  }
  function describeEl(el){
    var so = null;
    var aTracks = [];
    var vTracks = [];
    try { so = el.srcObject || null; } catch(e) {}
    try {
      if (so && so.getAudioTracks) aTracks = trackInfo(so.getAudioTracks());
      if (so && so.getVideoTracks) vTracks = trackInfo(so.getVideoTracks());
    } catch(e) {}
    return {
      tag: (el.tagName || '').toLowerCase(),
      muted: !!el.muted,
      defaultMuted: !!el.defaultMuted,
      volume: (typeof el.volume === 'number') ? el.volume : -1,
      paused: !!el.paused,
      readyState: el.readyState,
      srcObjectNull: so == null,
      currentSrc: el.currentSrc || el.src || '',
      aTracks: aTracks.length,
      vTracks: vTracks.length,
      audioTracksDetail: aTracks,
      videoTracksDetail: vTracks
    };
  }
  function audioContextState(){
    try {
      if (window.__falcorAcState) return window.__falcorAcState;
      var Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return 'none';
      // Do not construct a new context — only report existing if hooked.
      return 'unknown';
    } catch(e) { return 'err'; }
  }
  function probe(reason){
    try {
      var url = location.href || '';
      var src = srcParam(url);
      var kind = pathKind(url);
      var media = collectMedia(document);
      var videos = [];
      var audios = [];
      for (var i = 0; i < media.length; i++) {
        var tag = (media[i].tagName || '').toLowerCase();
        if (tag === 'video') videos.push(media[i]);
        else if (tag === 'audio') audios.push(media[i]);
      }
      var v0 = videos.length ? describeEl(videos[0]) : null;
      var aTrackN = v0 ? v0.aTracks : 0;
      var vTrackN = v0 ? v0.vTracks : 0;
      var vMute = v0 ? (v0.muted ? 1 : 0) : -1;
      var vol = v0 ? v0.volume : -1;
      if (!src && v0 && v0.currentSrc) src = srcParam(v0.currentSrc) || v0.currentSrc;
      var hrefShort = url.length > 72 ? (url.substring(0, 72) + '…') : url;
      var snack = 'q=' + QUALITY + ' | src=' + (src || '?') + ' | ' + kind +
        ' | href=' + hrefShort +
        ' | vMute=' + vMute + ' vol=' + vol +
        ' | aTracks=' + aTrackN + ' vTracks=' + vTrackN +
        ' | audioEls=' + audios.length +
        ' | hasListen=' + HAS_LISTEN;
      var detailLines = [];
      detailLines.push('reason=' + reason);
      detailLines.push('quality=' + QUALITY);
      detailLines.push('candidateIndex=' + CAND);
      detailLines.push('url=' + url);
      detailLines.push('src=' + src);
      detailLines.push('pathKind=' + kind);
      detailLines.push('hasListenAudio=' + HAS_LISTEN);
      detailLines.push('videoCount=' + videos.length);
      detailLines.push('audioElementCount=' + audios.length);
      detailLines.push('audioContext=' + audioContextState());
      for (var vi = 0; vi < videos.length; vi++) {
        var vd = describeEl(videos[vi]);
        detailLines.push('video[' + vi + '] muted=' + vd.muted +
          ' defaultMuted=' + vd.defaultMuted +
          ' volume=' + vd.volume +
          ' paused=' + vd.paused +
          ' readyState=' + vd.readyState +
          ' srcObjectNull=' + vd.srcObjectNull +
          ' aTracks=' + vd.aTracks +
          ' vTracks=' + vd.vTracks +
          ' currentSrc=' + vd.currentSrc);
        for (var ai = 0; ai < vd.audioTracksDetail.length; ai++) {
          var at = vd.audioTracksDetail[ai];
          detailLines.push('  audioTrack[' + ai + '] id=' + at.id +
            ' label=' + at.label +
            ' enabled=' + at.enabled +
            ' muted=' + at.muted +
            ' readyState=' + at.readyState);
        }
        for (var yi = 0; yi < vd.videoTracksDetail.length; yi++) {
          var yt = vd.videoTracksDetail[yi];
          detailLines.push('  videoTrack[' + yi + '] id=' + yt.id +
            ' label=' + yt.label +
            ' enabled=' + yt.enabled +
            ' muted=' + yt.muted +
            ' readyState=' + yt.readyState);
        }
      }
      for (var ae = 0; ae < audios.length; ae++) {
        var ad = describeEl(audios[ae]);
        detailLines.push('audioEl[' + ae + '] muted=' + ad.muted +
          ' paused=' + ad.paused +
          ' volume=' + ad.volume +
          ' aTracks=' + ad.aTracks +
          ' vTracks=' + ad.vTracks);
      }
      var detail = detailLines.join('\n');
      try {
        if (window.FalcorAudioProbe && window.FalcorAudioProbe.report) {
          window.FalcorAudioProbe.report(snack, detail);
        }
      } catch(e) {}
    } catch(e) {
      try {
        if (window.FalcorAudioProbe && window.FalcorAudioProbe.report) {
          window.FalcorAudioProbe.report('probe-error', String(e));
        }
      } catch(e2) {}
    }
  }
  probe('schedule');
  try {
    collectMedia(document).forEach(function(el){
      if ((el.tagName || '').toLowerCase() !== 'video') return;
      if (el.__falcorProbePlaying) return;
      el.__falcorProbePlaying = true;
      el.addEventListener('playing', function(){ probe('playing'); }, { once: false });
    });
  } catch(e) {}
  if (window.__falcorProbeDelay) {
    try { clearTimeout(window.__falcorProbeDelay); } catch(e) {}
  }
  window.__falcorProbeDelay = setTimeout(function(){
    probe('delayed2s');
    window.__falcorProbeDelay = null;
  }, 2000);
})();
"""
}

private class FalcorAudioProbeBridge(
    private val callbackHolder: Array<((String) -> Unit)?>
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun report(snack: String?, detail: String?) {
        val d = detail ?: snack.orEmpty()
        android.util.Log.i("FalcorAudioProbe", d)
        val s = snack.orEmpty()
        if (s.isNotBlank()) {
            mainHandler.post {
                callbackHolder.getOrNull(0)?.invoke(s)
            }
        }
    }
}

/** Mutable meta passed into WebView tags: [0]=quality, [1]=candidateIndex string. */
private class ProbeMeta(var quality: String, var candidateIndex: Int, var hasListen: Int)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun keyAndroidView(
    pageUrl: String,
    bearerToken: String?,
    showDetections: Boolean,
    allowMicrophone: Boolean,
    qualityLabel: String,
    hasListenAudio: Boolean?,
    candidateIndex: Int,
    onAudioProbe: ((String) -> Unit)?,
    onPlaying: () -> Unit,
    onMainFrameError: () -> Unit,
    onSpaExhaust: () -> Unit,
    onStarted: () -> Unit
) {
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onErrorState by rememberUpdatedState(onMainFrameError)
    val onSpaExhaustState by rememberUpdatedState(onSpaExhaust)
    val showDet by rememberUpdatedState(showDetections)
    val allowMic by rememberUpdatedState(allowMicrophone)
    val qualityState by rememberUpdatedState(qualityLabel)
    val hasListenState by rememberUpdatedState(hasListenAudio)
    val candState by rememberUpdatedState(candidateIndex)
    val probeState by rememberUpdatedState(onAudioProbe)

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
                setTag(R.id.falcor_webview_mic_tag, micAllowed)

                val probeCb = arrayOf(probeState)
                setTag(R.id.falcor_webview_probe_cb_tag, probeCb)
                val hl = if (hasListenState == true) 1 else 0
                val probeMeta = ProbeMeta(qualityState, candState, hl)
                setTag(R.id.falcor_webview_probe_meta_tag, probeMeta)
                addJavascriptInterface(FalcorAudioProbeBridge(probeCb), "FalcorAudioProbe")

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (request == null) return
                        val wanted = request.resources ?: return
                        val allow = (getTag(R.id.falcor_webview_mic_tag) as? BooleanArray)
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
                // Intended embed URL for this WebView instance (SPA recovery).
                val intendedUrl = pageUrl
                val spaReloadTried = booleanArrayOf(false)
                webViewClient = object : WebViewClient() {
                    private fun recoverNonEmbed(view: WebView?, arrived: String?, reason: String): Boolean {
                        // Ignore blank/null — WebView can report these mid-navigation.
                        if (arrived.isNullOrBlank() || arrived == "about:blank") return false
                        if (isLiveEmbedUrl(arrived)) return false
                        android.util.Log.w(
                            "FrigateLiveWebView",
                            "Non-embed ($reason): $arrived — intended=$intendedUrl"
                        )
                        if (!spaReloadTried[0] && intendedUrl.isNotBlank() && isLiveEmbedUrl(intendedUrl)) {
                            spaReloadTried[0] = true
                            val tokenHdr = bearerToken?.trim()?.removePrefix("Bearer ")
                                ?.removePrefix("bearer ")?.trim().orEmpty()
                            val headers = mutableMapOf<String, String>()
                            if (tokenHdr.isNotEmpty()) headers["Authorization"] = "Bearer $tokenHdr"
                            view?.loadUrl(intendedUrl, headers)
                            return true
                        }
                        // Reload-once already used — fail to OkHttp; do not advance SPA candidate list.
                        onSpaExhaustState()
                        return true
                    }

                    private fun shouldBlockNav(url: String?): Boolean {
                        if (url.isNullOrBlank() || url == "about:blank") return false
                        if (isBlockedFrigateSpaUrl(url)) return true
                        return !isLiveEmbedUrl(url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val u = request?.url?.toString()
                        if (shouldBlockNav(u)) {
                            android.util.Log.w(
                                "FrigateLiveWebView",
                                "Blocked non-embed navigation: $u"
                            )
                            recoverNonEmbed(view, u, "shouldOverride")
                            return true
                        }
                        return false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (shouldBlockNav(url)) {
                            android.util.Log.w(
                                "FrigateLiveWebView",
                                "Blocked non-embed navigation (legacy): $url"
                            )
                            recoverNonEmbed(view, url, "shouldOverrideLegacy")
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onStarted()
                        if (shouldBlockNav(url)) {
                            recoverNonEmbed(view, url, "onPageStarted")
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Never treat Frigate SPA / missing src= as a successful live surface.
                        if (recoverNonEmbed(view, url, "onPageFinished")) return
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
                        val meta = view?.getTag(R.id.falcor_webview_probe_meta_tag) as? ProbeMeta
                        val q = meta?.quality ?: "SUB"
                        val c = meta?.candidateIndex ?: 0
                        val hl = meta?.hasListen ?: 0
                        // Short delay so video element / srcObject can attach, then probe (+2s inside JS).
                        view?.postDelayed({
                            view.evaluateJavascript(audioProbeJs(q, c, hl), null)
                        }, 400)
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
            (webView.getTag(R.id.falcor_webview_mic_tag) as? BooleanArray)
                ?.set(0, allowMic)
            @Suppress("UNCHECKED_CAST")
            (webView.getTag(R.id.falcor_webview_probe_cb_tag) as? Array<((String) -> Unit)?>)
                ?.set(0, probeState)
            (webView.getTag(R.id.falcor_webview_probe_meta_tag) as? ProbeMeta)?.let {
                it.quality = qualityState
                it.candidateIndex = candState
                it.hasListen = if (hasListenState == true) 1 else 0
            }
            val current = webView.url
            val needReload = current != pageUrl ||
                isBlockedFrigateSpaUrl(current) ||
                (current != null && current != "about:blank" && !isLiveEmbedUrl(current))
            if (needReload) {
                val token = bearerToken?.trim()?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")?.trim().orEmpty()
                if (token.isNotEmpty()) injectAuthCookie(pageUrl, "frigate_token", token)
                val headers = mutableMapOf<String, String>()
                if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
                webView.loadUrl(pageUrl, headers)
            } else {
                webView.evaluateJavascript(PLAYER_CHROME_JS, null)
                // Quality / candidate meta changed on same URL — re-probe.
                val hl = if (hasListenState == true) 1 else 0
                webView.evaluateJavascript(audioProbeJs(qualityState, candState, hl), null)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.removeJavascriptInterface("FalcorAudioProbe") }
            runCatching { webView.destroy() }
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
