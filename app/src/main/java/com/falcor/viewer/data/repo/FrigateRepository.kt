package com.falcor.viewer.data.repo

import android.util.Log
import com.falcor.viewer.data.api.FrigateApi
import com.falcor.viewer.data.api.FrigateClientFactory
import com.falcor.viewer.data.model.CameraCapabilities
import com.falcor.viewer.data.model.CameraSetBody
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.model.FrigateConfig
import com.falcor.viewer.data.model.FrigateEvent
import com.falcor.viewer.data.model.LoginRequest
import com.falcor.viewer.data.model.PtzInfo
import com.falcor.viewer.data.model.RecordingSegment
import com.falcor.viewer.data.model.deriveCameraCapabilities
import com.falcor.viewer.data.model.resolveStreamNames
import com.falcor.viewer.data.model.toUi
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.ws.FrigateWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class FrigateRepository(
    private val credentialStore: SecureCredentialStore
) {
    private var api: FrigateApi? = null
    private var cachedConfig: FrigateConfig? = null

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    @Volatile
    private var wsClient: FrigateWsClient? = null
    private val wsRefCount = AtomicInteger(0)

    val credentials: SecureCredentialStore.Credentials?
        get() = credentialStore.load()

    val baseUrl: String
        get() = credentials?.baseUrl.orEmpty()

    init {
        credentials?.let { restore(it) }
    }

    fun hasSavedSession(): Boolean = credentialStore.load()?.isConfigured == true

    private fun restore(creds: SecureCredentialStore.Credentials) {
        api = FrigateClientFactory.create(creds)
        _sessionReady.value = true
        // Config is fetched on first API use (getCameras / ensureConfig); keep session ready.
    }

    /** Ensure [cachedConfig] is populated from GET /api/config (login + session restore path). */
    suspend fun ensureConfig(): Result<FrigateConfig> = withContext(Dispatchers.IO) {
        runCatching {
            cachedConfig ?: requireApi().getConfig().also { cachedConfig = it }
        }
    }

    fun cachedFrigateConfig(): FrigateConfig? = cachedConfig

    /**
     * Connect to Frigate:
     * 1. Normalize base URL
     * 2. If username+password and no token → POST /api/login, extract JWT from Set-Cookie
     * 3. Rebuild client with Bearer JWT
     * 4. GET /api/config to verify
     * 5. Persist credentials (including token)
     *
     * Token-only → Bearer directly. No auth → unauthenticated GET config (port 5000).
     */
    suspend fun login(creds: SecureCredentialStore.Credentials): Result<FrigateConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = normalizeBaseUrl(creds.baseUrl)
                var working = creds.copy(baseUrl = normalized)

                val hasUserPass =
                    !working.username.isNullOrBlank() && !working.password.isNullOrBlank()
                val hasToken = !working.token.isNullOrBlank()

                if (hasUserPass && !hasToken) {
                    val jwt = obtainJwt(normalized, working.username!!, working.password!!)
                    working = working.copy(token = jwt)
                    Log.d(TAG, "Login obtained JWT (len=${jwt.length})")
                }

                val client = FrigateClientFactory.create(working)
                val config = try {
                    client.getConfig()
                } catch (e: HttpException) {
                    throw mapHttpException(e)
                }
                credentialStore.save(working)
                api = client
                cachedConfig = config
                _sessionReady.value = true
                // Refresh WS with new token on next connect
                disconnectPtzWs(force = true)
                config
            }.recoverCatching { t ->
                Log.e(TAG, "login failed: ${t.message}", t)
                throw classify(t)
            }
        }

    private suspend fun obtainJwt(baseUrl: String, user: String, password: String): String {
        // Unauthenticated client for login only (no Bearer / Basic).
        val loginApi = FrigateClientFactory.create(baseUrl, bearerToken = null)
        val response = try {
            loginApi.login(LoginRequest(user = user, password = password))
        } catch (e: HttpException) {
            throw mapHttpException(e)
        }

        if (response.code() == 401 || response.code() == 403) {
            throw FrigateConnectException(
                FrigateConnectException.Kind.AUTH,
                "Login rejected: HTTP ${response.code()}"
            )
        }
        if (!response.isSuccessful) {
            throw FrigateConnectException(
                FrigateConnectException.Kind.HTTP,
                "Login failed: HTTP ${response.code()}"
            )
        }

        val fromCookie = extractJwtFromSetCookie(response.headers().values("Set-Cookie"))
        if (!fromCookie.isNullOrBlank()) return fromCookie

        val bodyText = response.body()?.string().orEmpty()
        val fromBody = extractJwtFromJsonBody(bodyText)
        if (!fromBody.isNullOrBlank()) return fromBody

        throw FrigateConnectException(
            FrigateConnectException.Kind.AUTH,
            "Login succeeded but no JWT cookie/token found in response"
        )
    }

    fun logout() {
        disconnectPtzWs(force = true)
        credentialStore.clear()
        api = null
        cachedConfig = null
        _sessionReady.value = false
    }

    private fun requireApi(): FrigateApi =
        api ?: credentials?.let {
            FrigateClientFactory.create(it).also { created -> api = created }
        } ?: error("Not logged in")

    suspend fun refreshConfig(): Result<FrigateConfig> = withContext(Dispatchers.IO) {
        runCatching {
            requireApi().getConfig().also { cachedConfig = it }
        }
    }

    suspend fun getCameras(): Result<List<CameraUiModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val config = cachedConfig ?: requireApi().getConfig().also { cachedConfig = it }
            val base = baseUrl
            config.cameras.map { (name, cam) ->
                val ptzInfo = runCatching { requireApi().getPtzInfo(name) }.getOrNull()
                deriveCameraCapabilities(name, cam, config, base, ptzInfo).toUi()
            }.sortedBy { it.name }
        }
    }

    /** Full capability snapshot for one camera (uses cached config). */
    suspend fun getCameraCapabilities(camera: String): Result<CameraCapabilities> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = cachedConfig ?: requireApi().getConfig().also { cachedConfig = it }
                val cam = config.cameras[camera]
                    ?: error("Camera not in config: $camera")
                val ptzInfo = runCatching { requireApi().getPtzInfo(camera) }.getOrNull()
                deriveCameraCapabilities(camera, cam, config, baseUrl, ptzInfo)
            }
        }

    /**
     * Enable/disable camera via Frigate runtime API.
     * Primary: PUT /api/camera/{name}/set/enabled {"value":"ON"|"OFF"}
     */
    suspend fun setCameraEnabled(camera: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val value = if (enabled) "ON" else "OFF"
                val response = requireApi().setCameraFeature(camera, "enabled", CameraSetBody(value))
                if (!response.isSuccessful) {
                    error("Enable/disable failed: HTTP ${response.code()}")
                }
                runCatching { refreshConfig() }
                Unit
            }
        }

    suspend fun getEvents(
        camera: String? = null,
        label: String? = null,
        limit: Int = 50,
        hasClip: Boolean? = null,
        hasSnapshot: Boolean? = null
    ): Result<List<FrigateEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            requireApi().getEvents(
                cameras = camera,
                labels = label,
                limit = limit,
                hasClip = hasClip?.let { if (it) 1 else 0 },
                hasSnapshot = hasSnapshot?.let { if (it) 1 else 0 }
            )
        }
    }

    suspend fun getEvent(id: String): Result<FrigateEvent> = withContext(Dispatchers.IO) {
        runCatching { requireApi().getEvent(id) }
    }

    suspend fun getRecordings(camera: String, after: Double, before: Double): Result<List<RecordingSegment>> =
        withContext(Dispatchers.IO) {
            runCatching { requireApi().getRecordings(camera, after, before) }
        }

    suspend fun getPtzInfo(camera: String): Result<PtzInfo> = withContext(Dispatchers.IO) {
        runCatching { requireApi().getPtzInfo(camera) }
    }

    /**
     * Acquire + connect the Frigate WebSocket used for PTZ (same as the official web UI).
     * Reference-counted so multiple camera screens can share one socket.
     */
    fun connectPtzWs() {
        val creds = credentials ?: return
        synchronized(this) {
            wsRefCount.incrementAndGet()
            ensurePtzWsLocked(creds)
        }
    }

    /** Reconnect if needed without bumping the refcount. */
    fun ensurePtzWs() {
        val creds = credentials ?: return
        synchronized(this) {
            if (wsRefCount.get() <= 0) wsRefCount.set(1)
            ensurePtzWsLocked(creds)
        }
    }

    private fun ensurePtzWsLocked(creds: SecureCredentialStore.Credentials) {
        val existing = wsClient
        if (existing == null ||
            existing.state.value == FrigateWsClient.ConnectionState.FAILED
        ) {
            existing?.disconnect()
            val client = FrigateWsClient(creds)
            wsClient = client
            client.connect()
            return
        }
        if (existing.state.value == FrigateWsClient.ConnectionState.DISCONNECTED) {
            existing.connect()
        }
    }

    fun disconnectPtzWs(force: Boolean = false) {
        synchronized(this) {
            if (force) {
                wsRefCount.set(0)
                wsClient?.disconnect()
                wsClient = null
                return
            }
            val remaining = wsRefCount.decrementAndGet().coerceAtLeast(0)
            if (remaining == 0) {
                wsClient?.disconnect()
                wsClient = null
            }
        }
    }

    fun ptzWsState(): StateFlow<FrigateWsClient.ConnectionState>? = wsClient?.state

    fun detectionsFlow(): SharedFlow<FrigateWsClient.CameraDetections>? = wsClient?.detections

    fun wsClientOrNull(): FrigateWsClient? = wsClient

    /**
     * Send a PTZ command via Frigate WebSocket (primary path used by the web UI).
     * Falls back to legacy HTTP GET /api/{cam}/ptz/{cmd} only if the socket is unavailable.
     */
    suspend fun ptz(camera: String, command: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureWsConnected()
            val ws = wsClient
            if (ws != null && ws.sendPtz(camera, command)) {
                return@runCatching
            }
            // Last-resort HTTP fallback (not Frigate's primary mechanism).
            val response = runCatching { requireApi().ptzCommand(camera, command) }.getOrNull()
            if (response != null && response.isSuccessful) {
                return@runCatching
            }
            if (ws?.state?.value != FrigateWsClient.ConnectionState.CONNECTED) {
                error("PTZ WebSocket unavailable — check Frigate WS and ONVIF config")
            }
            error("PTZ command failed: HTTP ${response?.code() ?: "no response"}")
        }
    }

    private fun ensureWsConnected() {
        ensurePtzWs()
        // Brief wait for handshake so the first press isn't dropped.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val s = wsClient?.state?.value
            if (s == FrigateWsClient.ConnectionState.CONNECTED) return
            if (s == FrigateWsClient.ConnectionState.FAILED) return
            Thread.sleep(50)
        }
    }

    fun thumbnailUrl(camera: String): String = "$baseUrl/api/$camera/latest.jpg"

    fun eventSnapshotUrl(eventId: String): String = "$baseUrl/api/events/$eventId/snapshot.jpg"

    fun eventThumbnailUrl(eventId: String): String = "$baseUrl/api/events/$eventId/thumbnail.jpg"

    fun eventClipUrl(eventId: String): String = "$baseUrl/api/events/$eventId/clip.mp4"

    /** Frigate continuous MJPEG feed (same auth/TLS path as thumbnails). */
    fun mjpegLiveUrl(camera: String): String = "$baseUrl/api/$camera"

    /**
     * Authenticated OkHttp client that trusts Frigate's self-signed cert — reuse for
     * Coil, MJPEG preview, and snapshot polling.
     */
    fun authenticatedHttpClient(): okhttp3.OkHttpClient {
        val token = credentials?.token
        return FrigateClientFactory.okHttpClient(token)
    }

    /**
     * Build live stream URL candidates from cached Frigate config.
     *
     * Prefer authenticated HTTPS on the user baseUrl. Only add direct go2rtc
     * RTSP / :API ports when [Go2RtcConfig] exposes a non-loopback listen address —
     * never invent closed Docker ports.
     *
     * Order:
     * 1. go2rtc HLS via Frigate API (`/api/go2rtc/stream.m3u8?src=`)
     * 2. Frigate continuous MJPEG (`/api/{camera}`)
     * 3. Optional go2rtc HTTP API listen from config
     * 4. Optional RTSP listen from config (last)
     */
    fun liveStreamUrls(camera: String, preferSub: Boolean, streamNames: List<String>): List<String> {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return emptyList()
        val base = baseUrl.trimEnd('/')
        val config = cachedConfig
        val go2rtc = config?.go2rtc
        val camCfg = config?.cameras?.get(camera)
        val roleMap = camCfg?.live?.streams.orEmpty()

        val preferred = resolvePreferredStreamName(
            camera = camera,
            preferSub = preferSub,
            roleMap = roleMap,
            streamNames = streamNames,
            go2rtcKeys = go2rtc?.streamKeys.orEmpty()
        )
        val names = LinkedHashSet<String>().apply {
            add(preferred)
            addAll(streamNames)
            if (camCfg != null) {
                addAll(resolveStreamNames(camera, camCfg, go2rtc?.streamKeys.orEmpty()))
            }
            add(camera)
        }.filter { it.isNotBlank() }

        val apiPort = go2rtc?.apiListenPort()
        val rtspPort = go2rtc?.rtspListenPort()

        return buildList {
            // 1. HLS through Frigate (same host/port/JWT as thumbnails)
            names.forEach { n ->
                add("$base/api/go2rtc/stream.m3u8?src=$n")
            }
            // 2. Continuous MJPEG — always available via Frigate API
            add("$base/api/$camera")
            // 3. Direct go2rtc HTTP only when config publishes a reachable API listen
            if (apiPort != null) {
                names.forEach { n ->
                    add("http://$host:$apiPort/api/stream.m3u8?src=$n&mp4")
                    add("http://$host:$apiPort/api/stream.mp4?src=$n")
                }
            }
            // 4. RTSP only when config publishes a reachable RTSP listen
            if (rtspPort != null) {
                names.forEach { n ->
                    add("rtsp://$host:$rtspPort/$n")
                }
            }
        }.distinct()
    }

    private fun resolvePreferredStreamName(
        camera: String,
        preferSub: Boolean,
        roleMap: Map<String, String>,
        streamNames: List<String>,
        go2rtcKeys: Set<String>
    ): String {
        fun pickFromRoles(sub: Boolean): String? {
            val entry = roleMap.entries.firstOrNull { (role, _) ->
                if (sub) role.contains("sub", true)
                else role.contains("main", true) || role.equals("stream", true)
            }
            return entry?.value?.trim()?.takeIf { it.isNotEmpty() }
        }
        val fromRole = if (preferSub) {
            pickFromRoles(sub = true) ?: pickFromRoles(sub = false)
        } else {
            pickFromRoles(sub = false) ?: roleMap.values.firstOrNull()?.trim()
        }
        if (!fromRole.isNullOrBlank()) {
            if (go2rtcKeys.isEmpty() || fromRole in go2rtcKeys) return fromRole
        }
        val fromNames = when {
            preferSub && streamNames.any { it.contains("sub", true) } ->
                streamNames.first { it.contains("sub", true) }
            !preferSub && streamNames.any { it.contains("main", true) } ->
                streamNames.first { it.contains("main", true) }
            streamNames.isNotEmpty() -> streamNames.first()
            else -> null
        }
        if (!fromNames.isNullOrBlank()) return fromNames
        if (camera in go2rtcKeys) return camera
        return camera
    }

    fun talkStreamName(streamNames: List<String>, camera: String): String? {
        val config = cachedConfig
        val cam = config?.cameras?.get(camera)
        if (config != null && cam != null) {
            val caps = deriveCameraCapabilities(camera, cam, config, baseUrl, null)
            if (!caps.talkStreamName.isNullOrBlank()) return caps.talkStreamName
        }
        val go2rtcKeys = config?.go2rtc?.streamKeys.orEmpty()
        val talkFromNames = streamNames.firstOrNull {
            it.contains("talk", true) || it.contains("twoway", true) || it.contains("two_way", true)
        }
        if (talkFromNames != null) return talkFromNames
        val talkFromGo2rtc = go2rtcKeys.firstOrNull { key ->
            (key.contains(camera, true)) &&
                (key.contains("talk", true) || key.contains("twoway", true) || key.contains("two_way", true))
        }
        if (talkFromGo2rtc != null) return talkFromGo2rtc
        return streamNames.firstOrNull() ?: camera.takeIf { it in go2rtcKeys || go2rtcKeys.isEmpty() }
    }

    /**
     * Candidate Frigate/go2rtc WebRTC talk pages (in-app WebView).
     * True Frigate talk is WebRTC — not swapping live to RTSP.
     */

    /**
     * Candidate Frigate/go2rtc live player pages (MSE/WebRTC) for smooth WebView live.
     * Prefer these over LibVLC; OkHttp MJPEG remains fallback.
     */
    fun livePlayerPageUrls(camera: String, preferSub: Boolean = true, streamNames: List<String> = emptyList()): List<String> {
        val base = baseUrl.trimEnd('/')
        val config = cachedConfig
        val go2rtc = config?.go2rtc
        val camCfg = config?.cameras?.get(camera)
        val roleMap = camCfg?.live?.streams.orEmpty()
        val preferred = resolvePreferredStreamName(
            camera = camera,
            preferSub = preferSub,
            roleMap = roleMap,
            streamNames = streamNames.ifEmpty {
                camCfg?.let { resolveStreamNames(camera, it, go2rtc?.streamKeys.orEmpty()) }.orEmpty()
            },
            go2rtcKeys = go2rtc?.streamKeys.orEmpty()
        )
        val names = linkedSetOf(preferred, camera).filter { it.isNotBlank() }
        val enc = { s: String -> java.net.URLEncoder.encode(s, Charsets.UTF_8.name()) }
        return buildList {
            names.forEach { n ->
                val e = enc(n)
                add("$base/live/webrtc/webrtc.html?src=$e")
                add("$base/api/go2rtc/webrtc.html?src=$e")
                add("$base/api/go2rtc/stream.html?src=$e")
                add("$base/live/mse/mse.html?src=$e")
            }
            // Frigate camera hash route (SPA) — last resort embed
            add("$base/#$camera")
        }.distinct()
    }

    /** Best-effort castable URL (HLS via Frigate, else MJPEG). */
    fun castableStreamUrl(camera: String, preferSub: Boolean = true): String {
        val urls = liveStreamUrls(camera, preferSub, emptyList())
        return urls.firstOrNull { it.contains("m3u8", true) }
            ?: mjpegLiveUrl(camera)
    }

    fun webrtcTalkPageUrls(camera: String, streamName: String?): List<String> {
        val base = baseUrl.trimEnd('/')
        val src = (streamName ?: camera).trim().ifBlank { camera }
        val encoded = java.net.URLEncoder.encode(src, Charsets.UTF_8.name())
        return listOf(
            "$base/live/webrtc/webrtc.html?src=$encoded",
            "$base/live/webrtc/index.html?src=$encoded",
            "$base/api/go2rtc/webrtc.html?src=$encoded",
            "$base/api/go2rtc/stream.html?src=$encoded"
        ).distinct()
    }

    /** Bearer token value without "Bearer " prefix, for WebView cookie injection. */
    fun jwtTokenRaw(): String? {
        val token = credentials?.token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return token.removePrefix("Bearer ").removePrefix("bearer ").trim().takeIf { it.isNotEmpty() }
    }

    /** Talk / live RTSP URL only when config exposes RTSP listen; else null (live only — not talk). */
    fun rtspUrlForStream(streamName: String): String? {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return null
        val port = cachedConfig?.go2rtc?.rtspListenPort() ?: return null
        return "rtsp://$host:$port/$streamName"
    }

    fun recordingPlaybackUrl(camera: String, startTs: Double, endTs: Double): String =
        "$baseUrl/api/$camera/start/$startTs/end/$endTs/clip.mp4"

    fun vodPlaylistUrl(camera: String, startTs: Double, endTs: Double): String =
        "$baseUrl/api/vod/$camera/start/$startTs/end/$endTs/index.m3u8"

    /** Auth headers for media requests — Bearer JWT only (no Basic). */
    fun authHeaders(): Map<String, String> {
        val creds = credentials ?: return emptyMap()
        val token = creds.token?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val value = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        return mapOf("Authorization" to value)
    }

    companion object {
        private const val TAG = "FrigateRepository"

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.contains("://")) {
                // Prefer https for authenticated UI port when scheme omitted.
                url = if (url.substringAfterLast(':').substringBefore('/').all { it.isDigit() } &&
                    url.substringAfterLast(':').substringBefore('/').toIntOrNull() == 8971
                ) {
                    "https://$url"
                } else {
                    "http://$url"
                }
            }
            return url.trimEnd('/')
        }

        /**
         * Parse JWT from Set-Cookie headers. Prefers `frigate_token=` but also
         * accepts any cookie whose name contains "token" / "jwt" / "frigate".
         */
        fun extractJwtFromSetCookie(setCookieHeaders: List<String>): String? {
            val preferredNames = listOf("frigate_token", "jwt", "token")
            for (header in setCookieHeaders) {
                val first = header.substringBefore(';').trim()
                val eq = first.indexOf('=')
                if (eq <= 0) continue
                val name = first.substring(0, eq).trim()
                val value = first.substring(eq + 1).trim()
                if (value.isEmpty() || value.equals("deleted", ignoreCase = true)) continue
                if (preferredNames.any { name.equals(it, ignoreCase = true) }) return value
            }
            for (header in setCookieHeaders) {
                val first = header.substringBefore(';').trim()
                val eq = first.indexOf('=')
                if (eq <= 0) continue
                val name = first.substring(0, eq).trim().lowercase()
                val value = first.substring(eq + 1).trim()
                if (value.isEmpty()) continue
                if (name.contains("token") || name.contains("jwt") || name.contains("frigate")) {
                    return value
                }
            }
            return null
        }

        fun extractJwtFromJsonBody(body: String): String? {
            if (body.isBlank()) return null
            return runCatching {
                val obj = FrigateClientFactory.json.parseToJsonElement(body).jsonObject
                sequenceOf("token", "access_token", "jwt", "frigate_token")
                    .mapNotNull { key -> obj[key]?.jsonPrimitive?.content }
                    .firstOrNull { it.isNotBlank() }
            }.getOrNull()
        }

        private fun mapHttpException(e: HttpException): FrigateConnectException {
            val code = e.code()
            return when (code) {
                401, 403 -> FrigateConnectException(
                    FrigateConnectException.Kind.AUTH,
                    "HTTP $code: ${e.message()}"
                )
                else -> FrigateConnectException(
                    FrigateConnectException.Kind.HTTP,
                    "HTTP $code: ${e.message()}"
                )
            }
        }

        private fun classify(t: Throwable): FrigateConnectException {
            if (t is FrigateConnectException) return t
            val cause = generateSequence(t) { it.cause }.toList()
            if (cause.any {
                    it is SSLHandshakeException ||
                        it is SSLPeerUnverifiedException ||
                        it is SSLException ||
                        it.javaClass.name.contains("CertPath", ignoreCase = true) ||
                        (it.message?.contains("Certificate", ignoreCase = true) == true) ||
                        (it.message?.contains("SSL", ignoreCase = true) == true &&
                            it.message?.contains("handshake", ignoreCase = true) == true)
                }
            ) {
                return FrigateConnectException(
                    FrigateConnectException.Kind.SSL,
                    t.message ?: "SSL/certificate failure"
                )
            }
            if (t is HttpException) return mapHttpException(t)
            if (cause.any {
                    it is ConnectException ||
                        it is SocketTimeoutException ||
                        it is UnknownHostException ||
                        it.javaClass.name.contains("ConnectException")
                }
            ) {
                return FrigateConnectException(
                    FrigateConnectException.Kind.NETWORK,
                    t.message ?: "Connection failed"
                )
            }
            return FrigateConnectException(
                FrigateConnectException.Kind.UNKNOWN,
                t.message ?: "Unknown error"
            )
        }
    }
}

/** Typed connection failure for login UI string mapping. */
class FrigateConnectException(
    val kind: Kind,
    message: String
) : Exception(message) {
    enum class Kind { SSL, AUTH, NETWORK, HTTP, UNKNOWN }
}
