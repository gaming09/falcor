package com.falcor.viewer.player

import android.content.Context
import android.util.Log
import android.view.ViewGroup
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Native recvonly WebRTC live viewer for go2rtc / Frigate WHEP-style signaling.
 *
 * POST full SDP offer to each [webrtcPostUrls] candidate until one answers, then
 * render [VideoTrack] on [SurfaceViewRenderer] and mute via [AudioTrack.setEnabled]/setVolume.
 */
@Composable
fun Go2rtcWebRtcPlayer(
    webrtcPostUrls: List<String>,
    okHttpClient: OkHttpClient,
    mute: Boolean,
    modifier: Modifier = Modifier,
    onPlaying: (() -> Unit)? = null,
    onAllFailed: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val onPlayingState by rememberUpdatedState(onPlaying)
    val onAllFailedState by rememberUpdatedState(onAllFailed)
    val muteState by rememberUpdatedState(mute)

    var buffering by remember { mutableStateOf(true) }
    var session by remember { mutableStateOf<WebRtcSession?>(null) }
    val rendererHolder = remember { RendererHolder() }

    DisposableEffect(Unit) {
        onDispose {
            session?.dispose()
            session = null
            rendererHolder.release()
        }
    }

    LaunchedEffect(mute) {
        session?.applyMute(mute)
    }

    LaunchedEffect(webrtcPostUrls, okHttpClient) {
        session?.dispose()
        session = null
        buffering = true
        if (webrtcPostUrls.isEmpty()) {
            buffering = false
            onAllFailedState?.invoke()
            return@LaunchedEffect
        }

        val shared = WebRtcFactoryHolder.get(context.applicationContext)
        var lastError: Throwable? = null
        for (url in webrtcPostUrls) {
            if (!isActive) return@LaunchedEffect
            try {
                Log.i(
                    TAG,
                    "WebRTC try signaling host=${runCatching { java.net.URI(url).host }.getOrNull()}"
                )
                val next = WebRtcSession.connect(
                    factory = shared.factory,
                    okHttpClient = okHttpClient,
                    webrtcPostUrl = url,
                    rendererProvider = { rendererHolder.renderer },
                    onPlaying = {
                        buffering = false
                        onPlayingState?.invoke()
                    }
                )
                next.applyMute(muteState)
                session = next
                buffering = false
                onPlayingState?.invoke()
                launch {
                    val err = next.awaitRemoteFailure() ?: return@launch
                    Log.w(TAG, "WebRTC remote failure: ${err.message}")
                    if (session === next) {
                        session?.dispose()
                        session = null
                        buffering = false
                        onAllFailedState?.invoke()
                    }
                }
                return@LaunchedEffect
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "WebRTC candidate failed: ${t.message}")
            }
        }
        buffering = false
        Log.e(TAG, "All WebRTC candidates failed", lastError)
        onAllFailedState?.invoke()
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
                SurfaceViewRenderer(ctx).also { view ->
                    view.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val shared = WebRtcFactoryHolder.get(ctx.applicationContext)
                    view.init(shared.eglBase.eglBaseContext, null)
                    view.setMirror(false)
                    view.setEnableHardwareScaler(true)
                    rendererHolder.attach(view)
                    session?.reattachRenderer(view)
                }
            },
            update = { view ->
                if (rendererHolder.renderer !== view) {
                    rendererHolder.attach(view)
                    session?.reattachRenderer(view)
                }
            },
            onRelease = { view ->
                rendererHolder.detach(view)
                runCatching {
                    session?.detachRenderer(view)
                    view.release()
                }
            }
        )
        if (buffering) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

private const val TAG = "Go2rtcWebRtcPlayer"
private val SDP_MEDIA_TYPE = "application/sdp".toMediaType()
private const val ICE_GATHER_TIMEOUT_MS = 2_500L
private const val SIGNAL_TIMEOUT_MS = 15_000L

/** Shared PeerConnectionFactory + EglBase (init once per process). */
internal object WebRtcFactoryHolder {
    @Volatile private var initialized = false
    @Volatile private var cached: Shared? = null
    private val lock = Any()

    data class Shared(
        val factory: PeerConnectionFactory,
        val eglBase: EglBase
    )

    fun get(appContext: Context): Shared {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
            val eglBase = EglBase.create()
            val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoder)
                .setVideoDecoderFactory(decoder)
                .createPeerConnectionFactory()
            return Shared(factory, eglBase).also { cached = it }
        }
    }
}

private class RendererHolder {
    @Volatile var renderer: SurfaceViewRenderer? = null
        private set

    fun attach(view: SurfaceViewRenderer) {
        renderer = view
    }

    fun detach(view: SurfaceViewRenderer) {
        if (renderer === view) renderer = null
    }

    fun release() {
        renderer = null
    }
}

private class WebRtcSession private constructor(
    private val pc: PeerConnection,
    private val videoRef: AtomicReference<VideoTrack?>,
    private val audioRef: AtomicReference<AudioTrack?>,
    private val remoteFailure: CompletableDeferred<Throwable>
) {
    private val disposed = AtomicBoolean(false)

    fun applyMute(mute: Boolean) {
        val track = audioRef.get() ?: return
        runCatching {
            track.setEnabled(!mute)
            track.setVolume(if (mute) 0.0 else 1.0)
        }
    }

    fun reattachRenderer(renderer: SurfaceViewRenderer) {
        val track = videoRef.get() ?: return
        runCatching {
            track.removeSink(renderer)
            track.addSink(renderer)
        }
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        runCatching { videoRef.get()?.removeSink(renderer) }
    }

    /** Suspends until ICE/connection fails; returns null if session was disposed cleanly. */
    suspend fun awaitRemoteFailure(): Throwable? {
        return try {
            remoteFailure.await()
        } catch (_: CancellationException) {
            null
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        // Detach sinks only — PeerConnection.dispose owns the track natives.
        runCatching {
            val v = videoRef.getAndSet(null)
            // no-op sink cleanup handled by renderer onRelease
            v?.setEnabled(false)
        }
        runCatching {
            audioRef.getAndSet(null)?.setEnabled(false)
        }
        runCatching { pc.close() }
        runCatching { pc.dispose() }
        remoteFailure.cancel()
    }

    companion object {
        suspend fun connect(
            factory: PeerConnectionFactory,
            okHttpClient: OkHttpClient,
            webrtcPostUrl: String,
            rendererProvider: () -> SurfaceViewRenderer?,
            onPlaying: () -> Unit
        ): WebRtcSession = withContext(Dispatchers.IO) {
            val iceComplete = CompletableDeferred<Unit>()
            val remoteFailure = CompletableDeferred<Throwable>()
            val videoRef = AtomicReference<VideoTrack?>(null)
            val audioRef = AtomicReference<AudioTrack?>(null)
            val playingNotified = AtomicBoolean(false)

            fun notifyPlaying() {
                if (playingNotified.compareAndSet(false, true)) {
                    onPlaying()
                }
            }

            fun bindVideo(track: VideoTrack) {
                videoRef.set(track)
                track.setEnabled(true)
                rendererProvider()?.let { sink ->
                    runCatching {
                        track.addSink(sink)
                        notifyPlaying()
                    }
                }
            }

            fun bindAudio(track: AudioTrack) {
                audioRef.set(track)
                track.setEnabled(true)
                track.setVolume(1.0)
            }

            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    when (newState) {
                        PeerConnection.IceConnectionState.FAILED -> {
                            remoteFailure.complete(IllegalStateException("ICE FAILED"))
                        }
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> notifyPlaying()
                        else -> Unit
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                    if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                        iceComplete.complete(Unit)
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    when (val track = receiver?.track()) {
                        is VideoTrack -> bindVideo(track)
                        is AudioTrack -> bindAudio(track)
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    when (val track = transceiver?.receiver?.track()) {
                        is VideoTrack -> if (videoRef.get() == null) bindVideo(track)
                        is AudioTrack -> if (audioRef.get() == null) bindAudio(track)
                    }
                }
            }

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            val pc = factory.createPeerConnection(rtcConfig, observer)
                ?: error("createPeerConnection returned null")

            try {
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    )
                )
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    )
                )

                val offer = createOffer(pc)
                setLocalDescription(pc, offer)
                withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) { iceComplete.await() }
                if (pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                    delay(300)
                }

                val localSdp = pc.localDescription?.description
                    ?: error("Missing local SDP after setLocalDescription")
                val answerSdp = postOfferForAnswer(okHttpClient, webrtcPostUrl, localSdp)
                setRemoteDescription(
                    pc,
                    SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                )

                // Allow onAddTrack to fire before returning.
                delay(100)
                rendererProvider()?.let { sink ->
                    videoRef.get()?.let { track ->
                        runCatching {
                            track.removeSink(sink)
                            track.addSink(sink)
                        }
                    }
                }

                WebRtcSession(pc, videoRef, audioRef, remoteFailure)
            } catch (t: Throwable) {
                runCatching { pc.close() }
                runCatching { pc.dispose() }
                throw t
            }
        }

        private suspend fun createOffer(pc: PeerConnection): SessionDescription =
            suspendCoroutine { cont ->
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp != null) cont.resume(sdp)
                        else cont.resumeWithException(IllegalStateException("null offer"))
                    }
                    override fun onCreateFailure(error: String?) {
                        cont.resumeWithException(IllegalStateException("createOffer: $error"))
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, MediaConstraints())
            }

        private suspend fun setLocalDescription(pc: PeerConnection, sdp: SessionDescription) =
            suspendCoroutine { cont ->
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(IllegalStateException("setLocal: $error"))
                    }
                }, sdp)
            }

        private suspend fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription) =
            suspendCoroutine { cont ->
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(IllegalStateException("setRemote: $error"))
                    }
                }, sdp)
            }

        private fun postOfferForAnswer(
            client: OkHttpClient,
            url: String,
            offerSdp: String
        ): String {
            val http = client.newBuilder()
                .connectTimeout(SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(SIGNAL_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                .build()

            // go2rtc accepts application/sdp, application/json, or raw SDP.
            val body = offerSdp.toRequestBody(SDP_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/sdp")
                .header("Accept", "application/sdp, application/json, text/plain, */*")
                .build()

            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("WHEP HTTP ${response.code}: ${text.take(180)}")
                }
                return parseAnswerSdp(text)
            }
        }

        private fun parseAnswerSdp(body: String): String {
            val trimmed = body.trim()
            if (trimmed.startsWith("v=")) return trimmed
            return runCatching {
                val obj = JSONObject(trimmed)
                obj.optString("sdp").takeIf { it.startsWith("v=") }
                    ?: obj.optString("value").takeIf { it.startsWith("v=") }
                    ?: error("JSON without sdp")
            }.getOrElse {
                error("Unrecognized SDP answer (${trimmed.take(40)})")
            }
        }
    }
}
