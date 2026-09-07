package com.falcor.viewer.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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

    /** Flatten every source string under a stream key (string or array of strings). */
    fun sourceStrings(streamKey: String): List<String> {
        val el = streams[streamKey] ?: return emptyList()
        return flattenSources(el)
    }

    /** All source strings across all streams (for camera-wide talk scans). */
    fun allSourceStrings(): List<String> =
        streams.values.flatMap { flattenSources(it) }

    /** TCP port for go2rtc RTSP restream, or null if not configured / loopback-only. */
    fun rtspListenPort(): Int? = parseListenPort(listenString(rtsp), allowLoopback = false)

    /** TCP port for go2rtc HTTP API (HLS/WebUI), or null if not configured / loopback-only. */
    fun apiListenPort(): Int? = parseListenPort(listenString(api), allowLoopback = false)

    companion object {
        fun flattenSources(el: JsonElement): List<String> = when (el) {
            is JsonPrimitive -> listOfNotNull(el.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
            is JsonArray -> el.flatMap { flattenSources(it) }
            is JsonObject -> {
                // Some configs nest { "url": "..." } — collect string-ish values
                el.values.flatMap { flattenSources(it) }
            }
            else -> emptyList()
        }

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
    val detect: DetectConfig? = null,
    val audio: FeatureToggle? = null,
    val onvif: OnvifConfig? = null,
    val ui: JsonElement? = null
) {
    val isEnabled: Boolean get() = enabled != false
    val hasOnvifHost: Boolean
        get() = !onvif?.host.isNullOrBlank()
    val supportsPtzHint: Boolean get() = onvif != null || hasOnvifHost
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

/** Frigate camera.detect — includes frame size used for WS box normalization. */
@Serializable
data class DetectConfig(
    val enabled: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class OnvifConfig(
    val host: String? = null,
    val port: Int? = null,
    val user: String? = null,
    val password: String? = null
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

/**
 * Per-camera capabilities derived from full Frigate `/api/config` (+ optional ptz/info).
 * Drives PTZ button visibility, talk UI, stream names, and vendor hints.
 */
data class CameraCapabilities(
    val name: String,
    val enabled: Boolean,
    /** Show PTZ control even if ptz/info 404 — user can try; snackbar on failure. */
    val showPtz: Boolean,
    val ptzFromOnvif: Boolean,
    val ptzFromApi: Boolean,
    val supportsZoom: Boolean,
    val supportsFocus: Boolean,
    val ptzPresets: List<String>,
    /** Show Talk UI (WebRTC WebView). */
    val showTalk: Boolean,
    /** Best go2rtc stream for two-way talk (may equal [liveStreamName]). */
    val talkStreamName: String?,
    /** Preferred go2rtc stream for live listen (MSE/WebRTC without mic). */
    val liveStreamName: String?,
    val audioEnabled: Boolean,
    /** Listen-only audio available even when two-way talk is not. */
    val hasListenAudio: Boolean,
    val streamNames: List<String>,
    val liveRoleMap: Map<String, String>,
    /** Vendor / path hints for logging/UI only. */
    val vendorHints: List<String>,
    val thumbnailUrl: String,
    /** Frigate detect.width — pixel space for WS detection boxes. */
    val detectWidth: Int? = null,
    /** Frigate detect.height — pixel space for WS detection boxes. */
    val detectHeight: Int? = null
)

/** UI-facing camera card model. */
data class CameraUiModel(
    val name: String,
    val enabled: Boolean,
    val supportsPtz: Boolean,
    val supportsAudio: Boolean,
    val streamNames: List<String>,
    val thumbnailUrl: String,
    val capabilities: CameraCapabilities? = null
)

private val TALK_SOURCE_MARKERS = listOf(
    "onvif://",
    "reolink://",
    "backchannel",
    "#backchannel",
    "#audio=opus",
    "audio=opus",
    "codec=opus",
    "rtspx://",
    "isapi://",
    "ffmpeg:rtsp",
    "two-way",
    "twoway",
    "microphone"
)

private val TALK_KEY_MARKERS = listOf(
    "talk", "twoway", "two_way", "two-way", "doorbell", "backchannel", "speaker"
)

private val VENDOR_HINTS = listOf(
    "reolink", "amcrest", "hikvision", "dahua", "axis", "unifi", "wyze", "onvif", "rtsp://"
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

fun detectVendorHints(camera: CameraConfig, go2rtc: Go2RtcConfig?, streamNames: List<String>): List<String> {
    val hints = LinkedHashSet<String>()
    val ffmpegText = camera.ffmpeg?.toString().orEmpty().lowercase()
    VENDOR_HINTS.forEach { v ->
        if (ffmpegText.contains(v.lowercase())) hints.add(v.removeSuffix("://"))
    }
    if (camera.hasOnvifHost) hints.add("onvif")
    streamNames.forEach { name ->
        go2rtc?.sourceStrings(name)?.forEach { src ->
            val lower = src.lowercase()
            VENDOR_HINTS.forEach { v ->
                if (lower.contains(v.lowercase())) hints.add(v.removeSuffix("://"))
            }
        }
    }
    return hints.toList()
}

fun detectTalkCapability(
    cameraName: String,
    camera: CameraConfig,
    go2rtc: Go2RtcConfig?,
    streamNames: List<String>
): Pair<Boolean, String?> {
    val roleMap = camera.live?.streams.orEmpty()
    val go2rtcKeys = go2rtc?.streamKeys.orEmpty()

    // Explicit talk / twoway / doorbell in live.streams keys or values
    roleMap.entries.firstOrNull { (role, value) ->
        TALK_KEY_MARKERS.any { role.contains(it, true) || value.contains(it, true) }
    }?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { return true to it }

    streamNames.firstOrNull { name ->
        TALK_KEY_MARKERS.any { name.contains(it, true) }
    }?.let { return true to it }

    go2rtcKeys.firstOrNull { key ->
        key.contains(cameraName, true) && TALK_KEY_MARKERS.any { key.contains(it, true) }
    }?.let { return true to it }

    // Scan go2rtc source strings for talk-capable protocols / audio opus backchannel
    val candidateKeys = LinkedHashSet<String>().apply {
        addAll(streamNames)
        add(cameraName)
        go2rtcKeys.filter { it.contains(cameraName, true) }.forEach { add(it) }
        roleMap.values.forEach { add(it.trim()) }
    }

    for (key in candidateKeys) {
        val sources = go2rtc?.sourceStrings(key).orEmpty()
        val talkish = sources.any { src ->
            val lower = src.lowercase()
            TALK_SOURCE_MARKERS.any { lower.contains(it) }
        }
        if (talkish) return true to key
    }

    // Audio enabled + talk-capable go2rtc sources (opus / onvif / reolink / backchannel)
    val audioOn = camera.audio?.enabled == true
    val preferred = streamNames.firstOrNull() ?: cameraName
    val allSources = go2rtc?.sourceStrings(preferred).orEmpty() +
        candidateKeys.flatMap { go2rtc?.sourceStrings(it).orEmpty() }
    if (audioOn) {
        if (allSources.any { src ->
                val lower = src.lowercase()
                TALK_SOURCE_MARKERS.any { lower.contains(it) } ||
                    (lower.contains("ffmpeg:") && lower.contains("audio"))
            }
        ) {
            return true to preferred
        }
    }

    // ONVIF host configured + audio/listen markers → likely two-way capable (Frigate ONVIF talk).
    if (camera.hasOnvifHost || camera.onvif != null) {
        val hasOpusOrBc = allSources.any { src ->
            val lower = src.lowercase()
            lower.contains("opus") || lower.contains("backchannel") ||
                lower.contains("onvif://") || lower.contains("reolink://")
        }
        if (audioOn || hasOpusOrBc) {
            val onvifKey = candidateKeys.firstOrNull { key ->
                go2rtc?.sourceStrings(key).orEmpty().any { it.lowercase().contains("onvif://") }
            } ?: preferred
            return true to onvifKey
        }
    }

    // Reolink / doorbell-style vendor hints with any audio → show talk.
    val vendors = detectVendorHints(camera, go2rtc, streamNames).map { it.lowercase() }
    if (vendors.any { it.contains("reolink") || it.contains("doorbell") } &&
        (audioOn || allSources.any { s ->
            val l = s.lowercase()
            l.contains("audio") || l.contains("opus") || l.contains("reolink://")
        } || allSources.isEmpty())
    ) {
        val reolinkKey = candidateKeys.firstOrNull { key ->
            go2rtc?.sourceStrings(key).orEmpty().any { it.lowercase().contains("reolink://") }
        } ?: preferred
        return true to reolinkKey
    }

    return false to null
}

/** Markers in go2rtc source strings that imply listen (one-way) audio. */
val LISTEN_AUDIO_MARKERS = listOf(
    "#audio=",
    "audio=opus",
    "audio=aac",
    "audio=pcma",
    "audio=pcmu",
    "audio=any",
    "codec=opus",
    "codec=aac",
    "pcm_alaw",
    "pcm_mulaw",
    "#backchannel",
    "microphone"
)

/** live.streams role names that Frigate uses for WebRTC / listen audio (e.g. "WebRTC Audio"). */
private val LISTEN_ROLE_MARKERS = listOf("webrtc", "audio", "listen")

fun roleLooksListenCapable(role: String): Boolean {
    val r = role.lowercase()
    return LISTEN_ROLE_MARKERS.any { r.contains(it) }
}

fun streamNameLooksListenCapable(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith("_webrtc") ||
        n.contains("_webrtc") ||
        n.contains("webrtc") ||
        LISTEN_ROLE_MARKERS.any { n.contains(it) && (n.contains("webrtc") || n.contains("listen") || n.contains("audio")) }
}

fun sourcesHaveListenAudio(sources: List<String>): Boolean =
    sources.any { src ->
        val lower = src.lowercase()
        LISTEN_AUDIO_MARKERS.any { lower.contains(it) } ||
            (lower.contains("ffmpeg:") && lower.contains("audio"))
    }

/** True if role, stream key, or go2rtc sources look listen-capable. */
fun isListenCapableStream(
    role: String?,
    streamName: String,
    go2rtc: Go2RtcConfig?
): Boolean {
    if (!role.isNullOrBlank() && roleLooksListenCapable(role)) return true
    if (streamNameLooksListenCapable(streamName)) return true
    val sources = go2rtc?.sourceStrings(streamName).orEmpty()
    return sourcesHaveListenAudio(sources)
}

/**
 * Detect listen (one-way) audio even when two-way talk is missing.
 * Heuristics: camera.audio.enabled, go2rtc source audio codecs / #audio=,
 * ffmpeg input paths that demux audio, or talk-capable streams (talk implies listen).
 */
fun detectListenAudio(
    cameraName: String,
    camera: CameraConfig,
    go2rtc: Go2RtcConfig?,
    streamNames: List<String>,
    talkCapable: Boolean
): Boolean {
    if (talkCapable) return true
    if (camera.audio?.enabled == true) return true

    val roleMap = camera.live?.streams.orEmpty()
    val go2rtcKeys = go2rtc?.streamKeys.orEmpty()
    val candidateKeys = LinkedHashSet<String>().apply {
        addAll(streamNames)
        add(cameraName)
        go2rtcKeys.filter { it.contains(cameraName, true) }.forEach { add(it) }
        roleMap.values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
    }

    for (key in candidateKeys) {
        val sources = go2rtc?.sourceStrings(key).orEmpty()
        if (sources.any { src ->
                val lower = src.lowercase()
                LISTEN_AUDIO_MARKERS.any { lower.contains(it) } ||
                    (lower.contains("ffmpeg:") && lower.contains("audio")) ||
                    TALK_SOURCE_MARKERS.any { lower.contains(it) }
            }
        ) {
            return true
        }
    }

    val ffmpegText = camera.ffmpeg?.toString().orEmpty().lowercase()
    if (ffmpegText.contains("audio") || ffmpegText.contains("-c:a") || ffmpegText.contains("aac")) {
        return true
    }
    return false
}

/**
 * Build [CameraCapabilities] from cached config + optional [PtzInfo] (ptz/info may 404).
 *
 * PTZ button: ONVIF configured OR ptz/info supported OR features include pt/zoom —
 * even if ptz/info 404, onvif.host still shows the button.
 */
fun deriveCameraCapabilities(
    name: String,
    camera: CameraConfig,
    config: FrigateConfig,
    baseUrl: String,
    ptzInfo: PtzInfo? = null
): CameraCapabilities {
    val go2rtc = config.go2rtc
    val streams = resolveStreamNames(name, camera, go2rtc?.streamKeys.orEmpty())
    val onvifConfigured = camera.hasOnvifHost || camera.onvif != null
    val apiSupported = ptzInfo?.isSupported == true
    val showPtz = onvifConfigured || apiSupported
    val (talk, talkStream) = detectTalkCapability(name, camera, go2rtc, streams)
    val listen = detectListenAudio(name, camera, go2rtc, streams, talkCapable = talk)
    val vendors = detectVendorHints(camera, go2rtc, streams)
    val liveName = resolvePreferredLiveStreamName(
        name, camera, streams, go2rtc?.streamKeys.orEmpty(), preferSub = true, go2rtc = go2rtc
    )
    val detectW = camera.detect?.width?.takeIf { it > 0 }
    val detectH = camera.detect?.height?.takeIf { it > 0 }
    return CameraCapabilities(
        name = name,
        enabled = camera.isEnabled,
        showPtz = showPtz,
        ptzFromOnvif = onvifConfigured,
        ptzFromApi = apiSupported,
        supportsZoom = ptzInfo?.supportsZoom != false, // default true when unknown (ONVIF try)
        supportsFocus = ptzInfo?.supportsFocus == true,
        ptzPresets = ptzInfo?.presets.orEmpty(),
        showTalk = talk,
        talkStreamName = talkStream ?: (if (talk) liveName else null),
        liveStreamName = liveName,
        audioEnabled = camera.audio?.enabled == true || listen,
        hasListenAudio = listen,
        streamNames = streams,
        liveRoleMap = camera.live?.streams.orEmpty(),
        vendorHints = vendors,
        thumbnailUrl = "$baseUrl/api/$name/latest.jpg",
        detectWidth = detectW,
        detectHeight = detectH
    )
}

/**
 * Prefer listen-capable live src (WebRTC Audio / *_webrtc / LISTEN markers), then main/sub by
 * [preferSub]. Never hardcode camera names. Used for live WebView `?src=` and capability cache.
 */
fun resolvePreferredLiveStreamName(
    cameraName: String,
    camera: CameraConfig,
    streamNames: List<String>,
    go2rtcKeys: Set<String>,
    preferSub: Boolean = true,
    go2rtc: Go2RtcConfig? = null
): String {
    val roleMap = camera.live?.streams.orEmpty()

    fun roleMatchesQuality(role: String, value: String, sub: Boolean): Boolean {
        val blob = "$role $value"
        return if (sub) {
            blob.contains("sub", ignoreCase = true)
        } else {
            blob.contains("main", ignoreCase = true) || role.equals("stream", ignoreCase = true)
        }
    }

    fun acceptKey(name: String): Boolean =
        name.isNotBlank() && (go2rtcKeys.isEmpty() || name in go2rtcKeys)

    // --- 1a) Roles whose *name* is webrtc/audio/listen (e.g. "WebRTC Audio") — strongest ---
    val listenNamedRoles = roleMap.entries.mapNotNull { (role, raw) ->
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (!roleLooksListenCapable(role)) return@mapNotNull null
        if (!acceptKey(value)) return@mapNotNull null
        role to value
    }
    if (listenNamedRoles.isNotEmpty()) {
        val qualityHit = listenNamedRoles.firstOrNull { (role, value) ->
            roleMatchesQuality(role, value, preferSub)
        }
        if (qualityHit != null) return qualityHit.second
        // Shared "WebRTC Audio" (no main/sub in role/value) — use for both qualities.
        val generic = listenNamedRoles.firstOrNull { (role, value) ->
            !roleMatchesQuality(role, value, sub = true) &&
                !roleMatchesQuality(role, value, sub = false)
        }
        if (generic != null) return generic.second
        return listenNamedRoles.first().second
    }

    // --- 1b) Roles whose value/sources are listen-capable (markers / *_webrtc key) ---
    val listenValueRoles = roleMap.entries.mapNotNull { (role, raw) ->
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (roleLooksListenCapable(role)) return@mapNotNull null // already handled
        if (!isListenCapableStream(role = null, streamName = value, go2rtc = go2rtc)) {
            return@mapNotNull null
        }
        if (!acceptKey(value)) return@mapNotNull null
        role to value
    }
    if (listenValueRoles.isNotEmpty()) {
        val qualityHit = listenValueRoles.firstOrNull { (role, value) ->
            roleMatchesQuality(role, value, preferSub)
        }
        if (qualityHit != null) return qualityHit.second
        return listenValueRoles.first().second
    }

    // --- 2) go2rtc keys for this camera: *_webrtc / listen markers in sources ---
    val relatedKeys = LinkedHashSet<String>().apply {
        streamNames.filter { it.isNotBlank() }.forEach { add(it) }
        roleMap.values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        go2rtcKeys.filter { key ->
            key.equals(cameraName, true) ||
                key.startsWith("${cameraName}_", true) ||
                key.startsWith("$cameraName-", true) ||
                key.contains(cameraName, true)
        }.forEach { add(it) }
    }.filter { acceptKey(it) }

    val listenKeys = relatedKeys.filter { key ->
        isListenCapableStream(role = null, streamName = key, go2rtc = go2rtc)
    }
    if (listenKeys.isNotEmpty()) {
        val qualityHit = listenKeys.firstOrNull { key ->
            if (preferSub) key.contains("sub", true)
            else key.contains("main", true) && !key.contains("sub", true)
        }
        if (qualityHit != null) return qualityHit
        // Prefer explicit *_webrtc over other listen-ish keys
        listenKeys.firstOrNull { it.contains("webrtc", true) }?.let { return it }
        return listenKeys.first()
    }

    // --- 3) Classic main/sub role preference (video-only fallback) ---
    fun pickFromRoles(sub: Boolean): String? {
        val entry = roleMap.entries.firstOrNull { (role, _) ->
            if (sub) role.contains("sub", true)
            else role.contains("main", true) || role.equals("stream", true)
        }
        return entry?.value?.trim()?.takeIf { it.isNotEmpty() && acceptKey(it) }
    }
    val fromRole = if (preferSub) {
        pickFromRoles(sub = true) ?: pickFromRoles(sub = false)
    } else {
        pickFromRoles(sub = false) ?: pickFromRoles(sub = true) ?: roleMap.values.firstOrNull()?.trim()
    }
    if (!fromRole.isNullOrBlank() && acceptKey(fromRole)) return fromRole

    val fromNames = when {
        preferSub && streamNames.any { it.contains("sub", true) } ->
            streamNames.first { it.contains("sub", true) }
        !preferSub && streamNames.any { it.contains("main", true) } ->
            streamNames.first { it.contains("main", true) }
        else -> streamNames.firstOrNull { !TALK_KEY_MARKERS.any { m -> it.contains(m, true) } }
            ?: streamNames.firstOrNull()
    }
    if (!fromNames.isNullOrBlank()) return fromNames
    if (cameraName in go2rtcKeys) return cameraName
    return cameraName
}

/** Whether any listen-capable src exists for this camera (roles / keys / sources). */
fun cameraHasListenCapableStream(
    cameraName: String,
    camera: CameraConfig,
    streamNames: List<String>,
    go2rtc: Go2RtcConfig?
): Boolean {
    val roleMap = camera.live?.streams.orEmpty()
    if (roleMap.any { (role, value) -> isListenCapableStream(role, value.trim(), go2rtc) }) {
        return true
    }
    val go2rtcKeys = go2rtc?.streamKeys.orEmpty()
    val related = LinkedHashSet<String>().apply {
        addAll(streamNames)
        roleMap.values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        go2rtcKeys.filter { key ->
            key.equals(cameraName, true) ||
                key.startsWith("${cameraName}_", true) ||
                key.startsWith("$cameraName-", true) ||
                key.contains(cameraName, true)
        }.forEach { add(it) }
    }
    return related.any { isListenCapableStream(null, it, go2rtc) }
}

fun CameraCapabilities.toUi(): CameraUiModel = CameraUiModel(
    name = name,
    enabled = enabled,
    supportsPtz = showPtz,
    supportsAudio = showTalk || hasListenAudio || audioEnabled,
    streamNames = streamNames,
    thumbnailUrl = thumbnailUrl,
    capabilities = this
)

fun CameraConfig.toUi(
    name: String,
    baseUrl: String,
    ptzSupported: Boolean? = null,
    go2rtcStreamKeys: Set<String> = emptySet(),
    config: FrigateConfig? = null,
    ptzInfo: PtzInfo? = null
): CameraUiModel {
    if (config != null) {
        val caps = deriveCameraCapabilities(name, this, config, baseUrl, ptzInfo)
        val withPtz = if (ptzSupported == true) caps.copy(showPtz = true) else caps
        return withPtz.toUi()
    }
    val streamMap = live?.streams.orEmpty()
    val streams = resolveStreamNames(name, this, go2rtcStreamKeys)
    val audioOn = audio?.enabled == true
    return CameraUiModel(
        name = name,
        enabled = isEnabled,
        supportsPtz = ptzSupported ?: supportsPtzHint,
        supportsAudio = audioOn || streams.any {
            TALK_KEY_MARKERS.any { m -> it.contains(m, true) }
        } || streamMap.keys.any {
            TALK_KEY_MARKERS.any { m -> it.contains(m, true) }
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
