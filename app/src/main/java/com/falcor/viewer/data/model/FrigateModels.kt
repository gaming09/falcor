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
    val streams: Map<String, JsonElement> = emptyMap()
)

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
        get() = features?.pt != null || features?.zoom != null || presets.isNotEmpty()
}

@Serializable
data class PtzFeatures(
    val pt: String? = null,
    val zoom: String? = null,
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

fun CameraConfig.toUi(name: String, baseUrl: String, ptzSupported: Boolean? = null): CameraUiModel {
    val streams = live?.streams?.keys?.toList().orEmpty()
    val audioOn = audio?.enabled == true
    return CameraUiModel(
        name = name,
        enabled = isEnabled,
        supportsPtz = ptzSupported ?: supportsPtzHint,
        supportsAudio = audioOn || streams.any { it.contains("talk", true) || it.contains("twoway", true) },
        streamNames = streams.ifEmpty { listOf(name) },
        thumbnailUrl = "$baseUrl/api/$name/latest.jpg"
    )
}

fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

fun JsonElement.asStringList(): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    else -> emptyList()
}
