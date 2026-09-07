package com.falcor.viewer.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.1.26 — open-camera SPA guard: only (webrtc|mse|stream).html?src= embeds are live.
 */
class LiveEmbedUrlTest {

    @Test
    fun blocksFrigateSpaHashesAndPaths() {
        assertTrue(isBlockedFrigateSpaUrl("https://nvr.example:8971/#cameras"))
        assertTrue(isBlockedFrigateSpaUrl("https://nvr.example:8971/#cameras/FrontYard"))
        assertTrue(isBlockedFrigateSpaUrl("https://nvr.example:8971/cameras"))
        assertTrue(isBlockedFrigateSpaUrl("https://nvr.example:8971/cameras/FrontYard"))
        assertTrue(isBlockedFrigateSpaUrl("https://nvr.example:8971/cameras?foo=1"))
        assertFalse(isBlockedFrigateSpaUrl("https://nvr.example:8971/live/mse/mse.html?src=cam1"))
        assertFalse(isBlockedFrigateSpaUrl("https://nvr.example:8971/api/go2rtc/webrtc.html?src=cam1"))
    }

    @Test
    fun liveEmbedRequiresPlayerHtmlAndSrc() {
        assertTrue(
            isLiveEmbedUrl("https://nvr.example:8971/live/mse/mse.html?src=roaming3&media=video%2Baudio")
        )
        assertTrue(
            isLiveEmbedUrl("https://nvr.example:8971/live/webrtc/webrtc.html?src=cam_a")
        )
        assertTrue(
            isLiveEmbedUrl("https://nvr.example:8971/api/go2rtc/stream.html?src=cam_a")
        )
        assertTrue(
            isLiveEmbedUrl("https://nvr.example:8971/live/webrtc/index.html?src=cam_a&media=video%2Baudio%2Bmicrophone")
        )
        // Missing src=
        assertFalse(isLiveEmbedUrl("https://nvr.example:8971/live/mse/mse.html"))
        // SPA / home
        assertFalse(isLiveEmbedUrl("https://nvr.example:8971/"))
        assertFalse(isLiveEmbedUrl("https://nvr.example:8971/#cameras/FrontYard"))
        assertFalse(isLiveEmbedUrl("https://nvr.example:8971/cameras/roaming3"))
        // Empty / null
        assertFalse(isLiveEmbedUrl(null))
        assertFalse(isLiveEmbedUrl(""))
    }
}
