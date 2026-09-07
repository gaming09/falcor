# Falcor — the Frigate Viewer

**Falcor** is an Android client for [Frigate NVR](https://frigate.video/). Browse cameras, watch smooth live video with **live audio**, pinch-zoom, press-and-hold talk-back, review clips, pin dashboards, control PTZ, and cast a single camera stream.

Package ID: `com.falcor.viewer`  
Version: **0.1.5**

## Features (0.1.5)

- **Live audio** — WebView live unmuted (autoplay + JS unmute); LibVLC live path unmuted; audio focus requested.
- **Pinch-zoom + pan** on **all** live surfaces (WebView, VLC, OkHttp preview) and fullscreen — multi-touch handled so WebView cannot steal pinches.
- **Press-and-hold talk** — hold the mic on the main camera view (Frigate-style); no overlay dialog. Releases restore normal live URLs. WebView grants `getUserMedia` mic/camera permissions.
- **Stronger PTZ** — while held, MOVE_*/ZOOM_*/FOCUS_* are **re-sent every ~300ms** until release (helps sluggish Reolink/ONVIF continuous move); then STOP once. Larger hold targets with pressed feedback; bottom-sheet gestures cannot steal Down/Left. Optional **Invert pan/tilt** toggle for reversed axes.
- **Smooth live** — authenticated WebView embedding Frigate/go2rtc WebRTC/MSE pages, then LibVLC, then OkHttp MJPEG / `latest.jpg`.
- Fullscreen, clip seek/download, detection boxes, dashboards, single-camera Cast (unchanged core).

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

1. WebView go2rtc/Frigate live player pages (smooth, with audio)
2. LibVLC on config-derived HLS/MJPEG/RTSP candidates (unmuted)
3. OkHttp MJPEG / snapshot poll (fallback)

### Two-way talk

Press and hold **Hold to talk** on the camera screen. Falcor navigates the main live WebView to go2rtc WebRTC talk URLs and grants WebView mic permission. Release returns to normal live.

**Frigate-side caveats:** Talk still requires a go2rtc stream that supports two-way audio (e.g. `onvif://…`, `reolink://…`, or a dedicated talk source with audio backchannel). If hold-to-talk never connects after mic permission, check Frigate/go2rtc talk config for that camera — Falcor cannot invent a talk path the server does not expose.

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
