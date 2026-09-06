package com.falcor.viewer.data.repo

import com.falcor.viewer.data.api.FrigateApi
import com.falcor.viewer.data.api.FrigateClientFactory
import com.falcor.viewer.data.model.CameraSetBody
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.model.FrigateConfig
import com.falcor.viewer.data.model.FrigateEvent
import com.falcor.viewer.data.model.PtzInfo
import com.falcor.viewer.data.model.RecordingSegment
import com.falcor.viewer.data.model.toUi
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.ws.FrigateWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

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
    }

    suspend fun login(creds: SecureCredentialStore.Credentials): Result<FrigateConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = FrigateClientFactory.create(creds)
                val config = client.getConfig()
                credentialStore.save(creds)
                api = client
                cachedConfig = config
                _sessionReady.value = true
                config
            }
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
                val ptz = runCatching { requireApi().getPtzInfo(name).isSupported }.getOrNull()
                cam.toUi(name, base, ptz)
            }.sortedBy { it.name }
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
                error("PTZ WebSocket unavailable")
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

    /**
     * Build live stream URLs for LibVLC.
     * Prefer go2rtc RTSP restream (port 8554), then Frigate HTTP MJPEG as last resort.
     */
    fun liveStreamUrls(camera: String, preferSub: Boolean, streamNames: List<String>): List<String> {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return emptyList()
        val schemeHost = baseUrl.trimEnd('/')
        val preferred = when {
            preferSub && streamNames.any { it.contains("sub", true) } ->
                streamNames.first { it.contains("sub", true) }
            streamNames.isNotEmpty() -> streamNames.first()
            else -> camera
        }
        val alt = streamNames.firstOrNull { it != preferred }
        return buildList {
            add("rtsp://$host:8554/$preferred")
            if (alt != null) add("rtsp://$host:8554/$alt")
            if (preferred != camera) add("rtsp://$host:8554/$camera")
            add("$schemeHost/live/$preferred/index.m3u8")
            add("$schemeHost/api/$camera")
        }.distinct()
    }

    fun talkStreamName(streamNames: List<String>, camera: String): String? {
        return streamNames.firstOrNull {
            it.contains("talk", true) || it.contains("twoway", true) || it.contains("two_way", true)
        } ?: streamNames.firstOrNull()?.takeIf {
            true
        }?.let { camera }
    }

    fun recordingPlaybackUrl(camera: String, startTs: Double, endTs: Double): String =
        "$baseUrl/api/$camera/start/$startTs/end/$endTs/clip.mp4"

    fun vodPlaylistUrl(camera: String, startTs: Double, endTs: Double): String =
        "$baseUrl/api/vod/$camera/start/$startTs/end/$endTs/index.m3u8"

    fun authHeaders(): Map<String, String> {
        val creds = credentials ?: return emptyMap()
        val token = creds.token
        return when {
            !token.isNullOrBlank() -> {
                val value = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                mapOf("Authorization" to value)
            }
            !creds.username.isNullOrBlank() -> {
                mapOf(
                    "Authorization" to okhttp3.Credentials.basic(
                        creds.username,
                        creds.password.orEmpty()
                    )
                )
            }
            else -> emptyMap()
        }
    }
}
