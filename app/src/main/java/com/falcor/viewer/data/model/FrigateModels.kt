package com.falcor.viewer.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Partial Frigate /api/config models. Frigate config is large and version-dependent;
 * we parse flexibly and extract what Falcor needs.
 */
@Serializable
data class FrigateConfig(
    val cameras: Map<String, CameraConfig> = emptyMap(),
    val go2rtc: Go2RtcConfig? = null,
    val mqtt: JsonElement? = null,
    val version: String? = null
)

@Serializable
data class Go2RtcConfig(
    val streams: Map<String, JsonElement> = emptyMap(),
    /** e.g. { "listen": ":8554" } — JsonElement for version flexibility */
    val rtsp: JsonElement? = null,
    /** e.g. { "listen": ":1984" } */
    val api: JsonElement? = null,
    /** webrtc candidates / listen — used only as optional hints */
    val webrtc: JsonElement? = null
) {
    val streamKeys: Set<String> get() = streams.keys

    /** TCP port for go2rtc RTSP restream, or null if not configured / loopback-only. */
    fun rtspListenPort(): Int? = parseListenPort(listenString(rtsp), allowLoopback = false)

    /** TCP port for go2rtc HTTP API (HLS/WebUI), or null if not configured / loopback-only. */
    fun apiListenPort(): Int? = parseListenPort(listenString(api), allowLoopback = false)

    companion object {
        fun listenString(section: JsonElement?): String? {
            if (section == null) return null
            val obj = section as? JsonObject ?: return (section as? JsonPrimitive)?.contentOrNull
            val listen = obj["listen"] ?: return null
            return when (listen) {
                is JsonPrimitive -> listen.contentOrNull
                else -> null
            }
        }

        /**
         * Parse go2rtc listen values: ":8554", "0.0.0.0:8554", "127.0.0.1:1984", or bare "8554".
         * Returns null when missing, unparsable, or bound to loopback (unreachable from the phone)
         * unless [allowLoopback] is true.
         */
        fun parseListenPort(listen: String?, allowLoopback: Boolean = false): Int? {
            if (listen.isNullOrBlank()) return null
            val raw = listen.trim().trim('"')
            val hostPort = when {
                raw.startsWith(":") -> "0.0.0.0$raw"
                raw.contains(":") -> raw
                raw.all { it.isDigit() } -> "0.0.0.0:$raw"
                else -> return null
            }
            val host = hostPort.substringBeforeLast(':').ifBlank { "0.0.0.0" }
            val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            if (loopback && !allowLoopback) return null
            return port
        }
    }
}

@Serializable
data class CameraConfig(
    val enabled: Boolean? = true,
    val name: String? = null,
    val ffmpeg: JsonElement? = null,
    val live: LiveConfig? = null,
    val record: FeatureToggle? = null,
    val detect: FeatureToggle? = null,
    val audio: FeatureToggle? = null,
    val onvif: OnvifConfig? = null,
    val ui: JsonElement? = null
) {
    val isEnabled: Boolean get() = enabled != false
    val supportsPtzHint: Boolean get() = onvif != null
}

@Serializable
data class LiveConfig(
    val streams: Map<String, String>? = null,
    val height: Int? = null,
    val quality: Int? = null
)

@Serializable
data class FeatureToggle(
    val enabled: Boolean? = null
)

@Serializable
data class OnvifConfig(
    val host: String? = null,
    val port: Int? = null
)

@Serializable
data class CameraSetBody(
    val value: String
)

/** Body for POST /api/login — Frigate expects "user", not "username". */
@Serializable
data class LoginRequest(
    val user: String,
    val password: String
)

@Serializable
data class FrigateEvent(
    val id: String,
    val camera: String? = null,
    val label: String? = null,
    val zones: List<String> = emptyList(),
    @SerialName("start_time") val startTime: Double? = null,
    @SerialName("end_time") val endTime: Double? = null,
    val score: Double? = null,
    @SerialName("top_score") val topScore: Double? = null,
    @SerialName("has_snapshot") val hasSnapshot: Boolean? = null,
    @SerialName("has_clip") val hasClip: Boolean? = null,
    val thumbnail: String? = null,
    val data: EventData? = null
) {
    val displayScore: Int
        get() {
            val s = topScore ?: score ?: data?.score ?: data?.topScore
            return ((s ?: 0.0) * 100).toInt().coerceIn(0, 100)
        }
}

@Serializable
data class EventData(
    val score: Double? = null,
    @SerialName("top_score") val topScore: Double? = null,
    val type: String? = null,
    @SerialName("box") val box: List<Double>? = null
)

@Serializable
data class RecordingSegment(
    val id: String? = null,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
    val duration: Double? = null,
    val motion: Int? = null,
    val objects: Int? = null,
    val camera: String? = null
)

@Serializable
data class RecordingSummaryDay(
    val day: String? = null,
    val events: Int? = null,
    val hours: Map<String, JsonElement>? = null
)

@Serializable
data class PtzInfo(
    val name: String? = null,
    val features: PtzFeatures? = null,
    val presets: List<String> = emptyList()
) {
    val isSupported: Boolean
        get() = features?.pt != null ||
            features?.zoom != null ||
            features?.focus != null ||
            presets.isNotEmpty()

    val supportsPanTilt: Boolean get() = features?.pt != null
    val supportsZoom: Boolean get() = features?.zoom != null
    val supportsFocus: Boolean get() = features?.focus != null
}

@Serializable
data class PtzFeatures(
    val pt: String? = null,
    val zoom: String? = null,
    val focus: String? = null,
    val presets: Boolean? = null
)

@Serializable
data class GenericSuccess(
    val success: Boolean? = null,
    val message: String? = null
)

/** UI-facing camera card model. */
data class CameraUiModel(
    val name: String,
    val enabled: Boolean,
    val supportsPtz: Boolean,
    val supportsAudio: Boolean,
    val streamNames: List<String>,
    val thumbnailUrl: String
)

/**
 * Resolve go2rtc stream names for a camera from Frigate config:
 * 1) camera.live.streams values (role → name) that exist in go2rtc.streams
 * 2) all live.streams values
 * 3) go2rtc.streams keys matching the camera name / prefix
 * 4) camera name alone
 */
fun resolveStreamNames(
    cameraName: String,
    camera: CameraConfig,
    go2rtcStreamKeys: Set<String>
): List<String> {
    val streamMap = camera.live?.streams.orEmpty()
    val fromLive = streamMap.values.map { it.trim() }.filter { it.isNotEmpty() }
    val resolved = LinkedHashSet<String>()
    if (go2rtcStreamKeys.isNotEmpty()) {
        fromLive.filter { it in go2rtcStreamKeys }.forEach { resolved.add(it) }
    }
    if (resolved.isEmpty()) fromLive.forEach { resolved.add(it) }
    if (resolved.isEmpty() && go2rtcStreamKeys.isNotEmpty()) {
        go2rtcStreamKeys.filter { key ->
            key.equals(cameraName, true) ||
                key.startsWith("${cameraName}_", true) ||
                key.startsWith("$cameraName-", true) ||
                key.contains(cameraName, true)
        }.sortedBy { it.length }.forEach { resolved.add(it) }
    }
    if (resolved.isEmpty()) resolved.add(cameraName)
    return resolved.toList()
}

fun CameraConfig.toUi(
    name: String,
    baseUrl: String,
    ptzSupported: Boolean? = null,
    go2rtcStreamKeys: Set<String> = emptySet()
): CameraUiModel {
    val streamMap = live?.streams.orEmpty()
    val streams = resolveStreamNames(name, this, go2rtcStreamKeys)
    val audioOn = audio?.enabled == true
    return CameraUiModel(
        name = name,
        enabled = isEnabled,
        supportsPtz = ptzSupported ?: supportsPtzHint,
        supportsAudio = audioOn || streams.any {
            it.contains("talk", true) || it.contains("twoway", true) || it.contains("two_way", true)
        } || streamMap.keys.any {
            it.contains("talk", true) || it.contains("twoway", true) || it.contains("two_way", true)
        },
        streamNames = streams,
        thumbnailUrl = "$baseUrl/api/$name/latest.jpg"
    )
}

fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

fun JsonElement.asStringList(): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    else -> emptyList()
}
