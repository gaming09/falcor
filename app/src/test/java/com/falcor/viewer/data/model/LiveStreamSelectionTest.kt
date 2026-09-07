package com.falcor.viewer.data.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for live src / player-page selection helpers (0.1.22–0.1.23).
 * Never hardcode product camera names in production code — fixtures here are local only.
 */
class LiveStreamSelectionTest {

    private fun go2rtc(vararg pairs: Pair<String, String>): Go2RtcConfig =
        Go2RtcConfig(streams = pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun camera(
        audioEnabled: Boolean? = null,
        liveStreams: Map<String, String>? = null,
        ffmpegJson: String? = null
    ): CameraConfig = CameraConfig(
        audio = audioEnabled?.let { FeatureToggle(enabled = it) },
        live = liveStreams?.let { LiveConfig(streams = it) },
        ffmpeg = ffmpegJson?.let { JsonPrimitive(it) }
    )

    @Test
    fun opusAvRemux_isWebRtcFriendly_andSkipsAudioOnlyHelper() {
        val g = go2rtc(
            "FrontCam" to "ffmpeg:rtsp://cam/main#video=copy#audio=opus",
            "FrontCam_webrtc" to "ffmpeg:FrontCam#audio=opus"
        )
        assertTrue(streamHasOpusOrWebRtcFriendlyAudio("FrontCam", g))
        assertTrue(isAvListenCapableStream("FrontCam", g))
        assertTrue(isAudioOnlyGo2rtcStream("FrontCam_webrtc", g))
        assertFalse(isUsableLiveVideoSrc("FrontCam_webrtc", g))

        val preferred = resolvePreferredLiveStreamName(
            cameraName = "FrontCam",
            camera = camera(liveStreams = mapOf("main" to "FrontCam", "WebRTC Audio" to "FrontCam_webrtc")),
            streamNames = listOf("FrontCam", "FrontCam_webrtc"),
            go2rtcKeys = g.streamKeys,
            preferSub = false,
            go2rtc = g
        )
        assertEquals("FrontCam", preferred)
    }

    @Test
    fun plainRtsp_notWebRtcFriendly_butListenWhenAudioEnabled() {
        val g = go2rtc("YardCam" to "rtsp://192.168.1.50:554/cam/realmonitor")
        assertFalse(streamHasOpusOrWebRtcFriendlyAudio("YardCam", g))
        assertFalse(isAvListenCapableStream("YardCam", g)) // no #audio= on source

        val cam = camera(audioEnabled = true, liveStreams = mapOf("main" to "YardCam"))
        assertTrue(
            detectListenAudio(
                cameraName = "YardCam",
                camera = cam,
                go2rtc = g,
                streamNames = listOf("YardCam"),
                talkCapable = false
            )
        )
        assertTrue(
            cameraHasListenCapableStream(
                cameraName = "YardCam",
                camera = cam,
                streamNames = listOf("YardCam"),
                go2rtc = g
            )
        )
    }

    @Test
    fun plainRtsp_listenWhenFfmpegHasAacEvenIfAudioToggleOff() {
        val g = go2rtc("PorchCam" to "rtsp://10.0.0.8/stream1")
        val cam = camera(
            audioEnabled = false,
            liveStreams = mapOf("main" to "PorchCam"),
            ffmpegJson = "inputs path=rtsp://10.0.0.8/stream1 output args=-c:a aac"
        )
        assertTrue(
            detectListenAudio(
                cameraName = "PorchCam",
                camera = cam,
                go2rtc = g,
                streamNames = listOf("PorchCam"),
                talkCapable = false
            )
        )
        assertFalse(streamHasOpusOrWebRtcFriendlyAudio("PorchCam", g))
    }

    @Test
    fun sourceWithAacMarker_countsAsListenAndNotOpusFriendlyUnlessVideoRemux() {
        val g = go2rtc("GateCam" to "ffmpeg:rtsp://cam/main#video=copy#audio=aac")
        assertTrue(isAvListenCapableStream("GateCam", g))
        // A/V+#audio=aac still counts as WebRTC-friendly A/V+listen remux for page order
        assertTrue(streamHasOpusOrWebRtcFriendlyAudio("GateCam", g))
    }
}
