package com.falcor.viewer.data.ws

import android.util.Log
import com.falcor.viewer.data.api.LocalSsl.trustLocalSelfSigned
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.model.DetectionBox
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Frigate WebSocket client — PTZ + mirrored MQTT (events / tracked objects for boxes).
 */
class FrigateWsClient(
    private val credentials: SecureCredentialStore.Credentials,
    private val client: OkHttpClient = defaultClient()
) {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _detections = MutableSharedFlow<CameraDetections>(extraBufferCapacity = 32)
    val detections: SharedFlow<CameraDetections> = _detections.asSharedFlow()

    private var webSocket: WebSocket? = null
    private val intentionalClose = AtomicBoolean(false)
    private val reconnectAttempts = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class CameraDetections(val camera: String, val boxes: List<DetectionBox>)

    val wsUrl: String
        get() = toWsUrl(credentials.baseUrl)

    fun connect() {
        if (_state.value == ConnectionState.CONNECTED ||
            _state.value == ConnectionState.CONNECTING
        ) {
            return
        }
        intentionalClose.set(false)
        _state.value = ConnectionState.CONNECTING

        val builder = Request.Builder().url(wsUrl)
        applyAuth(builder, credentials)
        webSocket = client.newWebSocket(builder.build(), listener)
    }

    fun disconnect() {
        intentionalClose.set(true)
        reconnectAttempts.set(false)
        webSocket?.close(1000, "client leave")
        webSocket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    fun send(topic: String, payload: String, retain: Boolean = false): Boolean {
        val ws = webSocket
        if (ws == null || _state.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "send skipped — not connected (topic=$topic)")
            return false
        }
        val jsonMsg = buildJsonObject {
            put("topic", topic)
            put("payload", payload)
            put("retain", retain)
        }.toString()
        return ws.send(jsonMsg).also { ok ->
            if (!ok) Log.w(TAG, "WebSocket.send returned false for $topic")
        }
    }

    fun sendPtz(camera: String, command: String): Boolean =
        send(topic = "$camera/ptz", payload = command, retain = false)

    private fun sendOnConnect() {
        val ws = webSocket ?: return
        val jsonMsg = buildJsonObject {
            put("topic", "onConnect")
            put("message", "")
            put("retain", false)
        }.toString()
        ws.send(jsonMsg)
    }

    private fun handleInbound(text: String) {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val topic = root["topic"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payloadEl = root["payload"] ?: return
            parseDetections(topic, payloadEl)?.let { _detections.tryEmit(it) }
        }.onFailure { Log.d(TAG, "WS parse skip: ${it.message}") }
    }

    private fun parseDetections(topic: String, payload: JsonElement): CameraDetections? {
        val payloadObj: JsonObject = when (payload) {
            is JsonObject -> payload
            is JsonPrimitive -> {
                val raw = payload.contentOrNull ?: return null
                runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            }
            else -> return null
        }

        // Event-style: { type, before, after: { camera, label, box: [..] } }
        val after = payloadObj["after"]?.asObjectOrNull()
        val before = payloadObj["before"]?.asObjectOrNull()
        val eventObj = after ?: before
        if (eventObj != null) {
            val camera = eventObj["camera"]?.jsonPrimitive?.contentOrNull
                ?: topic.substringBefore('/').takeIf { it.isNotBlank() && it != "frigate" }
                ?: return null
            val box = parseBox(eventObj["box"]) ?: return null
            val label = eventObj["label"]?.jsonPrimitive?.contentOrNull ?: "object"
            val score = eventObj["score"]?.jsonPrimitive?.floatOrNull
                ?: eventObj["top_score"]?.jsonPrimitive?.floatOrNull
            // end events clear
            val type = payloadObj["type"]?.jsonPrimitive?.contentOrNull
            if (type.equals("end", true)) {
                return CameraDetections(camera, emptyList())
            }
            return CameraDetections(camera, listOf(box.copy(label = label, score = score)))
        }

        // Tracked objects / detections array on camera topic
        val cameraFromTopic = when {
            topic.endsWith("/detections") || topic.endsWith("/objects") ||
                topic.contains("tracked") || topic.endsWith("/events") ->
                topic.substringBefore('/').takeIf { it.isNotBlank() && !it.equals("frigate", true) }
            else -> null
        }
        val detectionsArr = payloadObj["detections"]?.jsonArray
            ?: payloadObj["objects"]?.jsonArray
            ?: payloadObj["tracked_objects"]?.jsonArray
        if (detectionsArr != null) {
            val camera = payloadObj["camera"]?.jsonPrimitive?.contentOrNull
                ?: cameraFromTopic
                ?: return null
            val boxes = detectionsArr.mapNotNull { el ->
                val o = el.asObjectOrNull() ?: return@mapNotNull null
                val b = parseBox(o["box"] ?: o["bbox"]) ?: return@mapNotNull null
                val label = o["label"]?.jsonPrimitive?.contentOrNull ?: "object"
                val score = o["score"]?.jsonPrimitive?.floatOrNull
                b.copy(label = label, score = score)
            }
            return CameraDetections(camera, boxes)
        }

        // Current objects map: { "id": { box, label, ... }, ... } under camera_activity
        if (topic.contains("camera_activity", true) || payloadObj.containsKey("cameras")) {
            val cameras = payloadObj["cameras"]?.asObjectOrNull() ?: return null
            // Emit per-camera — take first matching with boxes
            cameras.entries.forEach { (cam, el) ->
                val camObj = el.asObjectOrNull() ?: return@forEach
                val objs = camObj["trackedObjects"]?.asObjectOrNull()
                    ?: camObj["tracked_objects"]?.asObjectOrNull()
                    ?: camObj["objects"]?.asObjectOrNull()
                if (objs != null) {
                    val boxes = objs.values.mapNotNull { v ->
                        val o = v.asObjectOrNull() ?: return@mapNotNull null
                        val b = parseBox(o["box"] ?: o["bbox"]) ?: return@mapNotNull null
                        val label = o["label"]?.jsonPrimitive?.contentOrNull ?: "object"
                        val score = o["score"]?.jsonPrimitive?.floatOrNull
                        b.copy(label = label, score = score)
                    }
                    if (boxes.isNotEmpty()) {
                        _detections.tryEmit(CameraDetections(cam, boxes))
                    }
                }
            }
            return null
        }

        return null
    }

    private fun parseBox(el: JsonElement?): DetectionBox? {
        if (el == null) return null
        val arr: JsonArray = when (el) {
            is JsonArray -> el
            is JsonPrimitive -> runCatching {
                json.parseToJsonElement(el.content).jsonArray
            }.getOrNull() ?: return null
            else -> return null
        }
        if (arr.size < 4) return null
        val a = arr[0].jsonPrimitive.floatOrNull ?: return null
        val b = arr[1].jsonPrimitive.floatOrNull ?: return null
        val c = arr[2].jsonPrimitive.floatOrNull ?: return null
        val d = arr[3].jsonPrimitive.floatOrNull ?: return null
        // Frigate boxes are often [xmin, ymin, xmax, ymax] in pixel or normalized.
        // If values look like pixels (>1.5), normalize assuming 1920x1080 — caller scales.
        // Prefer treating as already normalized when all <= 1.5.
        return if (a <= 1.5f && b <= 1.5f && c <= 1.5f && d <= 1.5f) {
            DetectionBox(label = "object", left = a, top = b, right = c, bottom = d)
        } else {
            // pixel coords → crude normalize (updated when we know frame size; 1920x1080 default)
            DetectionBox(
                label = "object",
                left = a / 1920f,
                top = b / 1080f,
                right = c / 1920f,
                bottom = d / 1080f
            )
        }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        when (this) {
            is JsonObject -> this
            is JsonPrimitive -> runCatching {
                json.parseToJsonElement(content).jsonObject
            }.getOrNull()
            else -> null
        }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS open ${response.code}")
            _state.value = ConnectionState.CONNECTED
            reconnectAttempts.set(false)
            sendOnConnect()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "WS message: ${text.take(200)}")
            handleInbound(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed $code $reason")
            if (!intentionalClose.get()) {
                _state.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            } else {
                _state.value = ConnectionState.DISCONNECTED
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message} http=${response?.code}", t)
            _state.value = ConnectionState.FAILED
            if (!intentionalClose.get()) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (intentionalClose.get()) return
        if (!reconnectAttempts.compareAndSet(false, true)) return
        client.dispatcher.executorService.execute {
            try {
                Thread.sleep(1_500)
            } catch (_: InterruptedException) {
                return@execute
            }
            if (intentionalClose.get()) return@execute
            reconnectAttempts.set(false)
            if (_state.value != ConnectionState.CONNECTED &&
                _state.value != ConnectionState.CONNECTING
            ) {
                connect()
            }
        }
    }

    companion object {
        private const val TAG = "FrigateWsClient"

        fun toWsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val wsBase = when {
                trimmed.startsWith("https://", ignoreCase = true) ->
                    "wss://" + trimmed.removePrefix("https://").removePrefix("HTTPS://")
                trimmed.startsWith("http://", ignoreCase = true) ->
                    "ws://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
                else -> trimmed
            }
            return if (wsBase.endsWith("/ws")) wsBase else "$wsBase/ws"
        }

        fun applyAuth(builder: Request.Builder, credentials: SecureCredentialStore.Credentials) {
            val token = credentials.token?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val value =
                if (token.startsWith("Bearer ", ignoreCase = true)) token
                else "Bearer $token"
            builder.header("Authorization", value)
        }

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .trustLocalSelfSigned()
                .build()
    }
}
