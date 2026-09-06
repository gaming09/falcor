package com.falcor.viewer.data.ws

import android.util.Log
import com.falcor.viewer.data.api.LocalSsl.trustLocalSelfSigned
import com.falcor.viewer.data.prefs.SecureCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Frigate WebSocket client — same transport the official web UI uses for PTZ.
 *
 * URL: HTTP base → ws/wss + "/ws" (e.g. http://host:5000/ → ws://host:5000/ws).
 * Messages: {"topic":"…","payload":"…","retain":false} (onConnect uses "message" instead of payload).
 *
 * Auth: Bearer JWT from login (same token as HTTP). Uses permissive TLS for
 * self-signed wss:// on Frigate port 8971.
 */
class FrigateWsClient(
    private val credentials: SecureCredentialStore.Credentials,
    private val client: OkHttpClient = defaultClient()
) {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private val intentionalClose = AtomicBoolean(false)
    private val reconnectAttempts = AtomicBoolean(false)

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

    /**
     * Send a Frigate WS envelope with [topic] and string [payload].
     * @return false if the socket is not connected or the send failed.
     */
    fun send(topic: String, payload: String, retain: Boolean = false): Boolean {
        val ws = webSocket
        if (ws == null || _state.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "send skipped — not connected (topic=$topic)")
            return false
        }
        val json = buildJsonObject {
            put("topic", topic)
            put("payload", payload)
            put("retain", retain)
        }.toString()
        return ws.send(json).also { ok ->
            if (!ok) Log.w(TAG, "WebSocket.send returned false for $topic")
        }
    }

    fun sendPtz(camera: String, command: String): Boolean =
        send(topic = "$camera/ptz", payload = command, retain = false)

    private fun sendOnConnect() {
        val ws = webSocket ?: return
        // Frigate UI uses "message" (not payload) for the onConnect handshake.
        val json = buildJsonObject {
            put("topic", "onConnect")
            put("message", "")
            put("retain", false)
        }.toString()
        ws.send(json)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS open ${response.code}")
            _state.value = ConnectionState.CONNECTED
            reconnectAttempts.set(false)
            sendOnConnect()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // PTZ is fire-and-forget; ignore inbound MQTT mirror traffic.
            Log.d(TAG, "WS message: ${text.take(200)}")
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
                else -> trimmed // already ws/wss or host-only
            }
            return if (wsBase.endsWith("/ws")) wsBase else "$wsBase/ws"
        }

        /** Attach Bearer JWT only — Frigate does not use Basic for WS auth. */
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
                .readTimeout(0, TimeUnit.MILLISECONDS) // keep WS alive
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .trustLocalSelfSigned()
                .build()
    }
}
