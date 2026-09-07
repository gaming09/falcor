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
import com.falcor.viewer.data.model.cameraHasListenCapableStream
import com.falcor.viewer.data.model.deriveCameraCapabilities
import com.falcor.viewer.data.model.isUsableLiveVideoSrc
import com.falcor.viewer.data.model.resolvePreferredLiveStreamName
import com.falcor.viewer.data.model.roleLooksListenCapable
import com.falcor.viewer.data.model.preferMseFirstLivePlayer
import com.falcor.viewer.data.model.orderedLiveEmbedPageUrls
import com.falcor.viewer.data.model.streamHasOpusOrWebRtcFriendlyAudio
import com.falcor.viewer.data.model.streamNameLooksListenCapable
import com.falcor.viewer.data.model.resolveStreamNames
import com.falcor.viewer.data.model.toUi
import com.falcor.viewer.data.prefs.AppPreferences
import com.falcor.viewer.data.prefs.PersistedCameraCapability
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.ws.FrigateWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
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
    private val credentialStore: SecureCredentialStore,
    private val appPreferences: AppPreferences? = null
) {
    private var api: FrigateApi? = null
    private var cachedConfig: FrigateConfig? = null

    /** In-memory capability map rebuilt on login / resume / refresh. */
    @Volatile
    private var capabilityMap: Map<String, CameraCapabilities> = emptyMap()

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
            val config = cachedConfig ?: requireApi().getConfig().also { cachedConfig = it }
            if (capabilityMap.isEmpty() && config.cameras.isNotEmpty()) {
                rebuildCapabilityMap(config, fetchGo2rtc = false)
            }
            config
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
                // Always rebuild camera capability map (talk/live streams) right after login.
                rebuildCapabilityMap(config, fetchGo2rtc = true)
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
        capabilityMap = emptyMap()
        _sessionReady.value = false
        runCatching {
            runBlocking { appPreferences?.clearCameraCapabilities() }
        }
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
            // Per-camera isolate: one bad derive/toUi must not blank the whole home grid.
            config.cameras.mapNotNull { (name, cam) ->
                runCatching {
                    val ptzInfo = runCatching { requireApi().getPtzInfo(name) }.getOrNull()
                    val cached = capabilityMap[name]
                    val derived = deriveCameraCapabilities(name, cam, config, base, ptzInfo)
                    val caps = if (cached != null) {
                        derived.copy(
                            showTalk = cached.showTalk || derived.showTalk,
                            hasListenAudio = cached.hasListenAudio || derived.hasListenAudio,
                            talkStreamName = cached.talkStreamName ?: derived.talkStreamName,
                            liveStreamName = cached.liveStreamName ?: derived.liveStreamName,
                            streamNames = cached.streamNames.ifEmpty { derived.streamNames }
                        )
                    } else derived
                    caps.toUi()
                }.onFailure { e ->
                    Log.e(TAG, "getCameras: skip camera=$name — ${e.message}", e)
                }.getOrNull()
            }.sortedBy { it.name }
        }
    }

    /** Apply persisted home order; unknown new cameras append alphabetically at the end. */
    fun applyCameraOrder(cameras: List<CameraUiModel>, order: List<String>): List<CameraUiModel> {
        if (order.isEmpty()) return cameras.sortedBy { it.name }
        val byName = cameras.associateBy { it.name }
        val ordered = LinkedHashSet<String>()
        order.forEach { if (it in byName) ordered.add(it) }
        cameras.map { it.name }.sorted().forEach { if (it !in ordered) ordered.add(it) }
        return ordered.mapNotNull { byName[it] }
    }

    /** Merge fresh Frigate camera list into [current] by name — preserve order & avoid grid flash. */
    fun mergeCamerasPreservingOrder(
        current: List<CameraUiModel>,
        fresh: List<CameraUiModel>
    ): List<CameraUiModel> {
        if (current.isEmpty()) return fresh
        val byName = fresh.associateBy { it.name }
        val merged = current.mapNotNull { old ->
            val neu = byName[old.name] ?: return@mapNotNull old
            old.copy(
                enabled = neu.enabled,
                supportsPtz = neu.supportsPtz,
                supportsAudio = neu.supportsAudio,
                streamNames = neu.streamNames.ifEmpty { old.streamNames },
                thumbnailUrl = neu.thumbnailUrl.ifBlank { old.thumbnailUrl },
                capabilities = neu.capabilities ?: old.capabilities
            )
        }
        val known = merged.map { it.name }.toSet()
        val appended = fresh.filter { it.name !in known }
        return merged + appended
    }

    /** Full capability snapshot for one camera (uses capability map + fresh ptz/info). */
    suspend fun getCameraCapabilities(camera: String): Result<CameraCapabilities> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = cachedConfig ?: requireApi().getConfig().also { cachedConfig = it }
                val cam = config.cameras[camera]
                    ?: error("Camera not in config: $camera")
                val ptzInfo = runCatching { requireApi().getPtzInfo(camera) }.getOrNull()
                val derived = deriveCameraCapabilities(camera, cam, config, baseUrl, ptzInfo)
                val cached = capabilityMap[camera]
                val merged = if (cached != null) {
                    derived.copy(
                        showTalk = cached.showTalk || derived.showTalk,
                        hasListenAudio = cached.hasListenAudio || derived.hasListenAudio,
                        talkStreamName = cached.talkStreamName ?: derived.talkStreamName,
                        liveStreamName = cached.liveStreamName ?: derived.liveStreamName,
                        streamNames = cached.streamNames.ifEmpty { derived.streamNames }
                    )
                } else derived
                capabilityMap = capabilityMap + (camera to merged)
                syncDetectFramesToWs(capabilityMap)
                merged
            }
        }

    /**
     * Enable/disable camera — same path as Frigate web UI.
     * Primary: WebSocket topic `{camera}/enabled/set` payload `ON`|`OFF`.
     * Fallback: PUT /api/camera/{name}/set/enabled {"value":"ON"|"OFF"}.
     */
    suspend fun setCameraEnabled(camera: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureWsConnected()
                val ws = wsClient
                val wsOk = ws != null && ws.sendEnabled(camera, enabled)
                if (!wsOk) {
                    Log.w(TAG, "WS enabled/set failed for $camera — trying HTTP fallback")
                    val value = if (enabled) "ON" else "OFF"
                    val response = runCatching {
                        requireApi().setCameraFeature(camera, "enabled", CameraSetBody(value))
                    }.getOrNull()
                    if (response == null || !response.isSuccessful) {
                        if (ws?.state?.value != FrigateWsClient.ConnectionState.CONNECTED) {
                            error("Camera enable WebSocket unavailable — check Frigate WS")
                        }
                        error(
                            "Enable/disable failed: HTTP ${response?.code() ?: "no response"}"
                        )
                    }
                }
                // Refresh config so enabled flags match Frigate.
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
            syncDetectFramesToWs(capabilityMap)
            client.connect()
            return
        }
        syncDetectFramesToWs(capabilityMap)
        if (existing.state.value == FrigateWsClient.ConnectionState.DISCONNECTED) {
            existing.connect()
        }
    }

    /** Push Frigate detect.width/height into the WS client for box normalization. */
    private fun syncDetectFramesToWs(map: Map<String, CameraCapabilities>) {
        val frames = map.mapNotNull { (name, caps) ->
            val w = caps.detectWidth
            val h = caps.detectHeight
            if (w != null && h != null && w > 0 && h > 0) name to (w to h) else null
        }.toMap()
        wsClient?.setDetectFrames(frames)
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
     * Order (ExoPlayer-primary live uses MP4 + HLS first):
     * 1. go2rtc MP4 via Frigate API (`/api/go2rtc/stream.mp4?src=`) — often carries A/V
     * 2. go2rtc HLS via Frigate API (`/api/go2rtc/stream.m3u8?src=`)
     * 3. Frigate continuous MJPEG (`/api/{camera}`)
     * 4. Optional go2rtc HTTP API listen from config (mp4 + m3u8)
     * 5. Optional RTSP listen from config (last / VLC)
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
            // 1. Progressive MP4 through Frigate (JWT) — prefer for ExoPlayer A/V
            names.forEach { n ->
                add("$base/api/go2rtc/stream.mp4?src=$n")
            }
            // 2. HLS through Frigate (same host/port/JWT as thumbnails)
            names.forEach { n ->
                add("$base/api/go2rtc/stream.m3u8?src=$n")
            }
            // 3. Continuous MJPEG — always available via Frigate API
            add("$base/api/$camera")
            // 4. Direct go2rtc HTTP only when config publishes a reachable API listen
            if (apiPort != null) {
                names.forEach { n ->
                    add("http://$host:$apiPort/api/stream.mp4?src=$n")
                    add("http://$host:$apiPort/api/stream.m3u8?src=$n&mp4")
                }
            }
            // 5. RTSP only when config publishes a reachable RTSP listen
            if (rtspPort != null) {
                names.forEach { n ->
                    add("rtsp://$host:$rtspPort/$n")
                }
            }
        }.distinct()
    }

    /**
     * Resolve live `src` with A/V+listen preference + [preferSub] every call.
     * Delegates to [resolvePreferredLiveStreamName] (skips audio-only *_webrtc helpers).
     */
    private fun resolvePreferredStreamName(
        camera: String,
        preferSub: Boolean,
        roleMap: Map<String, String>,
        streamNames: List<String>,
        go2rtcKeys: Set<String>
    ): String {
        val config = cachedConfig
        val camCfg = config?.cameras?.get(camera)
        if (camCfg != null) {
            return resolvePreferredLiveStreamName(
                cameraName = camera,
                camera = camCfg,
                streamNames = streamNames,
                go2rtcKeys = go2rtcKeys,
                preferSub = preferSub,
                go2rtc = config.go2rtc
            )
        }
        // Config miss — lightweight fallback (honors preferSub; skip audio-named sidecars).
        fun accept(name: String): Boolean =
            name.isNotBlank() && (go2rtcKeys.isEmpty() || name in go2rtcKeys)

        fun pickFromRoles(sub: Boolean): String? {
            val entry = roleMap.entries.firstOrNull { (role, value) ->
                val v = value.trim()
                if (!accept(v)) return@firstOrNull false
                // Without go2rtc sources, skip roles that look like WebRTC Audio sidecars.
                if (roleLooksListenCapable(role) && streamNameLooksListenCapable(v)) return@firstOrNull false
                if (sub) role.contains("sub", true)
                else role.contains("main", true) || role.equals("stream", true)
            }
            return entry?.value?.trim()?.takeIf { it.isNotEmpty() }
        }
        val fromRole = if (preferSub) {
            pickFromRoles(sub = true) ?: pickFromRoles(sub = false)
        } else {
            pickFromRoles(sub = false) ?: pickFromRoles(sub = true) ?: roleMap.values
                .map { it.trim() }
                .firstOrNull { accept(it) && !streamNameLooksListenCapable(it) }
        }
        if (!fromRole.isNullOrBlank() && accept(fromRole)) return fromRole
        val fromNames = when {
            preferSub && streamNames.any { it.contains("sub", true) && accept(it) } ->
                streamNames.first { it.contains("sub", true) && accept(it) }
            !preferSub && streamNames.any { it.contains("main", true) && accept(it) } ->
                streamNames.first { it.contains("main", true) && accept(it) }
            streamNames.any { accept(it) && !streamNameLooksListenCapable(it) } ->
                streamNames.first { accept(it) && !streamNameLooksListenCapable(it) }
            streamNames.any { accept(it) } -> streamNames.first { accept(it) }
            else -> null
        }
        if (!fromNames.isNullOrBlank()) return fromNames
        if (camera in go2rtcKeys) return camera
        return camera
    }

    /** True when Frigate/go2rtc exposes a listen-capable stream for [camera]. */
    fun hasListenCapableLiveSrc(camera: String): Boolean {
        val config = cachedConfig ?: return false
        val cam = config.cameras[camera] ?: return false
        val names = resolveStreamNames(camera, cam, config.go2rtc?.streamKeys.orEmpty())
        return cameraHasListenCapableStream(camera, cam, names, config.go2rtc)
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
     *
     * Order: opus / A/V+listen remux → WebRTC first (Reolink). Otherwise (plain RTSP/AAC) →
     * MSE first (Frigate web path), WebRTC as fallback. Page-order only — no hasListen gate.
     * Never hardcode camera names.
     */
    fun livePlayerPageUrls(
        camera: String,
        preferSub: Boolean = false,
        streamNames: List<String> = emptyList()
    ): List<String> {
        val base = baseUrl.trimEnd('/')
        val config = cachedConfig
        val go2rtc = config?.go2rtc
        val camCfg = config?.cameras?.get(camera)
        val roleMap = camCfg?.live?.streams.orEmpty()
        // Always resolve fresh with preferSub + A/V listen preference — never lock to stale caps.
        val preferred = resolvePreferredStreamName(
            camera = camera,
            preferSub = preferSub,
            roleMap = roleMap,
            streamNames = streamNames.ifEmpty {
                camCfg?.let { resolveStreamNames(camera, it, go2rtc?.streamKeys.orEmpty()) }.orEmpty()
            },
            go2rtcKeys = go2rtc?.streamKeys.orEmpty()
        )
        // Secondary: classic main/sub so quality chips still vary candidates.
        val qualityOnly = run {
            fun pick(sub: Boolean): String? {
                val entry = roleMap.entries.firstOrNull { (role, value) ->
                    val v = value.trim()
                    if (v.isEmpty()) return@firstOrNull false
                    if (!isUsableLiveVideoSrc(v, go2rtc)) return@firstOrNull false
                    if (sub) role.contains("sub", true)
                    else role.contains("main", true) || role.equals("stream", true)
                }
                return entry?.value?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (preferSub) pick(true) ?: pick(false) else pick(false) ?: pick(true)
        }
        val webrtcFriendly = streamHasOpusOrWebRtcFriendlyAudio(preferred, go2rtc)
        val mseFirstPreferred = preferMseFirstLivePlayer(preferred, go2rtc)
        Log.d(
            TAG,
            "livePlayerPageUrls camera=$camera preferSub=$preferSub src=$preferred " +
                "qualitySrc=$qualityOnly webrtcFriendly=$webrtcFriendly " +
                "mseFirst=$mseFirstPreferred listenCapable=${hasListenCapableLiveSrc(camera)}"
        )
        val names = linkedSetOf<String>().apply {
            add(preferred)
            qualityOnly?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(camera)
        }.filter { it.isNotBlank() }
        val enc = { s: String -> java.net.URLEncoder.encode(s, Charsets.UTF_8.name()) }
        // Request unmuted listen audio (no mic) — matches Frigate web Live player.
        // Never embed Frigate SPA (#cameras/…) — that leaks history chrome into the WebView.
        val media = "media=video%2Baudio"
        return buildList {
            names.forEach { n ->
                val e = enc(n)
                val mseFirst = preferMseFirstLivePlayer(n, go2rtc)
                addAll(orderedLiveEmbedPageUrls(base, e, media, mseFirst))
            }
        }.distinct()
    }

    /**
     * Cast-friendly stream URL. Chromecast cannot send Frigate JWT, so when the
     * app base is HTTPS :8971 we prefer unauthenticated HTTP on the same host
     * port 5000 (`http://{host}:5000/api/...`). If already on :5000, keep it.
     */
    fun castableStreamUrl(camera: String, preferSub: Boolean = true): String {
        val castBase = castUnauthenticatedBaseUrl()
        val camCfg = cachedConfig?.cameras?.get(camera)
        val go2rtc = cachedConfig?.go2rtc
        val preferred = resolvePreferredStreamName(
            camera = camera,
            preferSub = preferSub,
            roleMap = camCfg?.live?.streams.orEmpty(),
            streamNames = camCfg?.let {
                resolveStreamNames(camera, it, go2rtc?.streamKeys.orEmpty())
            }.orEmpty(),
            go2rtcKeys = go2rtc?.streamKeys.orEmpty()
        )
        val src = preferred.ifBlank { camera }
        val hls = "$castBase/api/go2rtc/stream.m3u8?src=$src"
        val mjpeg = "$castBase/api/$camera"
        return if (preferSub || src.isNotBlank()) hls else mjpeg
    }

    /** http://{host}:5000 when base is :8971 / https auth UI; else current base if already :5000. */
    fun castUnauthenticatedBaseUrl(): String {
        val raw = baseUrl.trimEnd('/')
        val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
        val host = uri.host ?: return raw
        val port = uri.port
        return when {
            port == 5000 -> raw
            port == -1 && uri.scheme.equals("http", ignoreCase = true) -> raw
            // Auth UI port or any other non-5000 → prefer open HTTP API for Cast
            else -> "http://$host:5000"
        }
    }

    /**
     * WHEP-style WebRTC signaling POST URLs (SDP offer body → SDP answer).
     * Frigate nginx proxies POST /api/go2rtc/webrtc → go2rtc /api/webrtc.
     * Optional direct go2rtc API listen when config publishes a non-loopback port.
     */
    fun webrtcLiveUrls(camera: String, preferSub: Boolean, streamNames: List<String>): List<String> {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return emptyList()
        val base = baseUrl.trimEnd('/')
        val config = cachedConfig
        val go2rtc = config?.go2rtc
        val camCfg = config?.cameras?.get(camera)
        val roleMap = camCfg?.live?.streams.orEmpty()
        // Fresh resolve each call (preferSub + listen) — do not use stale caps.liveStreamName.
        val preferred = resolvePreferredStreamName(
            camera = camera,
            preferSub = preferSub,
            roleMap = roleMap,
            streamNames = streamNames.ifEmpty {
                camCfg?.let { resolveStreamNames(camera, it, go2rtc?.streamKeys.orEmpty()) }.orEmpty()
            },
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
        val enc = { s: String -> java.net.URLEncoder.encode(s, Charsets.UTF_8.name()) }
        val apiPort = go2rtc?.apiListenPort()
        return buildList {
            names.forEach { n ->
                val e = enc(n)
                // Primary: Frigate-authenticated proxy (JWT + TLS via OkHttp client)
                add("$base/api/go2rtc/webrtc?src=$e")
                // Explicit WHEP path (may 404 on some Frigate builds — tried in order)
                add("$base/api/go2rtc/webrtc/whep?src=$e")
            }
            if (apiPort != null) {
                names.forEach { n ->
                    val e = enc(n)
                    add("http://$host:$apiPort/api/webrtc?src=$e")
                    add("http://$host:$apiPort/api/webrtc/whep?src=$e")
                }
            }
        }.distinct()
    }

    fun webrtcTalkPageUrls(camera: String, streamName: String?): List<String> {
        val base = baseUrl.trimEnd('/')
        val caps = capabilityMap[camera]
        val primary = (streamName
            ?: caps?.talkStreamName
            ?: caps?.liveStreamName
            ?: camera).trim().ifBlank { camera }
        // Prefer talk stream, then live src (same video → faster reconnect), then camera name.
        val srcs = linkedSetOf(primary)
        caps?.liveStreamName?.takeIf { it.isNotBlank() }?.let { srcs.add(it) }
        srcs.add(camera)
        // Explicit mic — go2rtc defaults to video+audio only (no microphone / ugly controls page).
        val media = "media=video%2Baudio%2Bmicrophone"
        val enc = { s: String -> java.net.URLEncoder.encode(s, Charsets.UTF_8.name()) }
        return buildList {
            srcs.forEach { src ->
                val encoded = enc(src)
                add("$base/live/webrtc/webrtc.html?src=$encoded&$media")
                add("$base/live/webrtc/index.html?src=$encoded&$media")
                add("$base/api/go2rtc/webrtc.html?src=$encoded&$media")
                add("$base/api/go2rtc/stream.html?src=$encoded&$media")
            }
        }.distinct()
    }

    fun cachedCapabilities(camera: String): CameraCapabilities? = capabilityMap[camera]

    fun allCachedCapabilities(): Map<String, CameraCapabilities> = capabilityMap

    /**
     * Always GET /api/config (and optionally go2rtc streams), rebuild per-camera
     * talk/live capability map, persist, and log what was detected.
     * Call after login and on app resume when a session exists.
     */
    suspend fun refreshCapabilities(forceConfig: Boolean = true): Result<Map<String, CameraCapabilities>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!_sessionReady.value && credentials?.isConfigured != true) {
                    return@runCatching emptyMap()
                }
                val config = if (forceConfig) {
                    requireApi().getConfig().also { cachedConfig = it }
                } else {
                    cachedConfig ?: requireApi().getConfig().also { cachedConfig = it }
                }
                rebuildCapabilityMap(config, fetchGo2rtc = true)
                capabilityMap
            }
        }

    private suspend fun rebuildCapabilityMap(config: FrigateConfig, fetchGo2rtc: Boolean) {
        // Optional: merge live go2rtc stream keys from API (keys may exist beyond config snapshot).
        var working = config
        if (fetchGo2rtc) {
            runCatching {
                val body = requireApi().go2rtcStreams().body()?.string().orEmpty()
                if (body.isNotBlank()) {
                    val apiKeys = parseGo2rtcStreamKeys(body)
                    Log.d(
                        TAG,
                        "go2rtc/streams fetched (${body.length} chars, ${apiKeys.size} keys) for capability scan"
                    )
                    if (apiKeys.isNotEmpty()) {
                        working = mergeGo2rtcStreamKeys(config, apiKeys)
                    }
                }
            }.onFailure { Log.d(TAG, "go2rtc/streams optional fetch skipped: ${it.message}") }
        }
        val base = baseUrl
        val map = LinkedHashMap<String, CameraCapabilities>()
        // Skip per-camera PTZ HTTP here (slow); ONVIF/config is enough for talk/live/audio mapping.
        // Camera screen still fetches ptz/info when opened.
        for ((name, cam) in working.cameras) {
            val caps = runCatching {
                deriveCameraCapabilities(name, cam, working, base, ptzInfo = null)
            }.onFailure { e ->
                Log.e(TAG, "rebuildCapabilityMap: skip camera=$name — ${e.message}", e)
            }.getOrNull() ?: continue
            map[name] = caps
            // Lightweight rule-based summary (no cloud AI) — helps debug talk/listen detection.
            Log.i(
                TAG,
                "Camera capabilities [$name]: talk=${caps.showTalk} listen=${caps.hasListenAudio} " +
                    "talkStream=${caps.talkStreamName} liveStream=${caps.liveStreamName} " +
                    "ptz=${caps.showPtz} audioCfg=${caps.audioEnabled} " +
                    "streams=${caps.streamNames} vendors=${caps.vendorHints}"
            )
            if (!caps.showTalk && caps.hasListenAudio) {
                Log.i(TAG, "Camera [$name]: listen audio detected without two-way talk — mute/listen OK")
            }
            if (!caps.showTalk) {
                val sources = caps.streamNames.flatMap { working.go2rtc?.sourceStrings(it).orEmpty() }
                if (sources.any { s ->
                        val l = s.lowercase()
                        l.contains("onvif://") || l.contains("reolink://") ||
                            l.contains("backchannel") || l.contains("audio=opus")
                    }
                ) {
                    Log.w(TAG, "Camera [$name] has talk-like go2rtc sources but showTalk=false — check detection")
                }
            }
        }
        capabilityMap = map
        cachedConfig = working
        syncDetectFramesToWs(map)
        val persisted = map.values.map {
            PersistedCameraCapability(
                name = it.name,
                showTalk = it.showTalk,
                talkStreamName = it.talkStreamName,
                liveStreamName = it.liveStreamName,
                showPtz = it.showPtz,
                streamNames = it.streamNames
            )
        }
        runCatching { appPreferences?.saveCameraCapabilities(persisted) }
            .onFailure { Log.w(TAG, "Failed to persist capability map: ${it.message}") }
        val talkCams = map.values.filter { it.showTalk }.map { "${it.name}->${it.talkStreamName}" }
        val listenCams = map.values.filter { it.hasListenAudio }.map { it.name }
        Log.i(
            TAG,
            "Capability scan complete: ${map.size} cameras, talk-capable: $talkCams, listen-audio: $listenCams"
        )
    }

    /**
     * Parse top-level keys from GET /api/go2rtc/streams JSON object.
     * Values are ignored — keys alone deepen live/talk stream name resolution.
     */
    private fun parseGo2rtcStreamKeys(body: String): Set<String> {
        return runCatching {
            val el = FrigateClientFactory.json.parseToJsonElement(body)
            val obj = el.jsonObject
            obj.keys.filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Merge API stream keys into config.go2rtc.streams (empty arrays for unknown keys). */
    private fun mergeGo2rtcStreamKeys(config: FrigateConfig, apiKeys: Set<String>): FrigateConfig {
        val existing = config.go2rtc?.streams.orEmpty()
        if (apiKeys.all { it in existing }) return config
        val merged = LinkedHashMap(existing)
        for (key in apiKeys) {
            if (key !in merged) {
                merged[key] = kotlinx.serialization.json.JsonArray(emptyList())
            }
        }
        val go2rtc = (config.go2rtc ?: com.falcor.viewer.data.model.Go2RtcConfig()).copy(streams = merged)
        return config.copy(go2rtc = go2rtc)
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
