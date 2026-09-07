# Falcor — the Frigate Viewer

**Falcor** is an Android client for [Frigate NVR](https://frigate.video/). Browse cameras, watch smooth live video with **live audio**, pinch-zoom, press-and-hold talk-back, review clips, pin dashboards, control PTZ, and cast a single camera stream.

Package ID: `com.falcor.viewer`  
Version: **0.1.6**

## Features (0.1.6)

- **Config capability scan on login / resume** — always `GET /api/config` (and optionally `GET /api/go2rtc/streams`) after login and when the app resumes with a saved session. Builds a per-camera map of `liveStreamName` / `talkStreamName` / talk+PTZ flags, persists it, and logs what was detected (no silent “talk unavailable” when config has talk).
- **Press-and-hold talk (Frigate-style)** — keeps the **same** live player frame (black, no HTML5 controls / play button). On hold, reconnects WebRTC with `media=video+audio+microphone` (prefer dedicated talk stream). On release, restores listen URLs with `media=video+audio`. Thin “Talking…” badge only — never a dialog.
- **Live listen audio** — default live WebRTC/MSE pages request `media=video+audio`; WebView JS removes `controls`, unmutes, and autoplays.
- **Pinch-zoom + pan** on all live surfaces and fullscreen.
- **Stronger PTZ** — hold re-sends MOVE/ZOOM/FOCUS every ~300ms; optional invert pan/tilt.
- Smooth live WebView → LibVLC → OkHttp MJPEG / snapshot fallback; fullscreen, clips, dashboards, Cast.

## Requirements

- Android Studio Ladybug / Koala or newer (AGP 8.7+)
- JDK 17+
- Android device or emulator, **API 26+**
- Frigate 0.13+ (0.14+ recommended)

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Connect to Frigate

| Setup | Example URL |
| --- | --- |
| Local HTTP (unauthenticated) | `http://192.168.1.50:5000` |
| Authenticated UI port | `https://192.168.1.50:8971` |

Frigate :8971 uses JWT (not HTTP Basic). Falcor trusts self-signed TLS for local NVR use — do not point at untrusted public hosts expecting the same.

### Live path

1. WebView go2rtc/Frigate live player pages with `media=video+audio` (smooth, unmuted)
2. LibVLC on config-derived HLS/MJPEG/RTSP candidates (unmuted)
3. OkHttp MJPEG / snapshot poll (fallback)

### Two-way talk

Press and hold **Hold to talk** on the camera screen. Falcor:

1. Keeps the same black WebView frame (no ugly HTML5 player chrome).
2. Loads `$base/live/webrtc/webrtc.html?src=<talkOrLive>&media=video+audio+microphone` (plus go2rtc fallbacks with the same `media=`).
3. Grants WebView `AUDIO_CAPTURE` via `WebChromeClient.onPermissionRequest`.
4. Injects CSS/JS to strip `controls`, unmute, and autoplay.
5. On release, restores previous live URLs with `media=video+audio` (listen only).

Talk streams are detected at login/resume from go2rtc source strings (`onvif://`, `reolink://`, backchannel, `#audio=opus`) and dedicated talk keys — see Logcat tag `FrigateRepository` for the capability scan summary.

**Frigate-side caveats:** Talk still requires a go2rtc stream that supports two-way audio. If hold-to-talk never connects after mic permission, check Frigate/go2rtc talk config for that camera.

### PTZ

WS payload matches Frigate UI: `{"topic":"<cam>/ptz","payload":"MOVE_DOWN","retain":false}` (and MOVE_UP/LEFT/RIGHT, ZOOM_*, FOCUS_*, STOP). Hold re-sends continuous moves for Reolink stacks that drop a single ContinuousMove. Use **Invert pan/tilt** in the PTZ sheet if axes are reversed.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Frigate API + streams |
| `RECORD_AUDIO` | Two-way talk |
| `MODIFY_AUDIO_SETTINGS` | Live audio focus |
| `WRITE_EXTERNAL_STORAGE` (≤28) | Legacy Downloads save |
| Cast / wake lock | Single-camera Cast session |

## Project structure

```text
app/src/main/java/com/falcor/viewer/
  FalcorApp.kt, MainActivity.kt
  cast/          # CastOptionsProvider + CastHelper (single stream)
  data/          # API, models, repo, media, prefs (DataStore + secure), WebSocket
  player/        # Live WebView, Exo clip player, OkHttp preview, LibVLC
  ui/            # home, camera, dashboards, alerts, navigation, player chrome
```

## Known limits

- **Dashboard Cast:** not implemented — only the focused camera stream is castable.
- **Cast + JWT:** Default Media Receiver cannot send Frigate auth headers; prefer open HTTP on the LAN for Cast.
- **Talk:** depends on Frigate go2rtc talk/onvif/reolink audio; app grants WebView mic but cannot fix missing server talk config.
- **Detection boxes:** depend on Frigate WS payloads; some versions expose richer data in the web UI only.
- **WebView live:** uses go2rtc HTML players Frigate already ships; if those routes 404, Falcor falls back automatically.

## License

Source provided for the Falcor / gaming09 project. Frigate, LibVLC, ExoPlayer, and Cast SDK are third-party with their own licenses.
