# Falcor — the Frigate Viewer

**Falcor** is an Android client for [Frigate NVR](https://frigate.video/). Browse cameras, watch smooth live video (Frigate/go2rtc WebView), review clips with seek + download, pin dashboard tiles, control PTZ, talk-back, and cast a single camera stream.

Package ID: `com.falcor.viewer`  
Version: **0.1.4**

## Features (0.1.4)

- **Smooth live** — camera detail prefers an authenticated **WebView** embedding Frigate/go2rtc WebRTC/MSE player pages (`/live/webrtc/…`, `/api/go2rtc/…`) with JWT cookie + `Authorization`. Falls back to LibVLC candidates, then OkHttp MJPEG / `latest.jpg`.
- **Home / dashboard tiles** — lightweight live previews (snapshot poll or MJPEG) so the grid stays responsive; open a camera for full smooth live.
- **Fullscreen** — immersive dialog; system bars hidden; back / exit button leaves fullscreen.
- **Pinch-zoom + pan** on the live/clip surface.
- **Landscape** — activity allows rotation; video uses **scale-to-fit** (`object-fit: contain` / `ContentScale.Fit`).
- **Clip playback** — ExoPlayer + OkHttp DataSource for progressive `clip.mp4` (JWT/TLS); download-to-cache fallback; **seek Slider** bound to duration/position; **Download** to public Downloads / MediaStore.
- **Detection boxes** — toggle “Show detections” (persisted). Overlay from Frigate WebSocket event / tracked-object payloads. When live WebView already draws boxes, toggle also injects CSS to hide them when off.
- **Dashboards** — bottom-nav section: create/rename/delete dashboards, pin/unpin cameras, resizable tiles (1×1 / 2×1 / 2×2), live previews. Stored in DataStore.
- **Cast** — cast the **current camera** HLS/MJPEG URL via Android Cast / Default Media Receiver when a session is connected.
  - **Limit:** multi-camera **dashboard cast is not supported** (needs a custom Cast receiver). Chromecast often cannot attach Frigate JWT cookies — unauthenticated LAN (:5000) casts more reliably than :8971.
- Persistent login, config-driven capabilities, PTZ over WebSocket, WebRTC talk (unchanged core).

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

### Live path (0.1.4)

1. WebView go2rtc/Frigate live player pages (smooth, matches Frigate web)
2. LibVLC on config-derived HLS/MJPEG/RTSP candidates
3. OkHttp MJPEG / snapshot poll (fallback)

### Clips

ExoPlayer progressive HTTP with the app OkHttp client (Bearer + trusted TLS). If progressive fails, download to cache then play locally. Seek works once duration is known; Download saves to Downloads.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Frigate API + streams |
| `RECORD_AUDIO` | Two-way talk |
| `WRITE_EXTERNAL_STORAGE` (≤28) | Legacy Downloads save |
| Cast / wake lock | Single-camera Cast session |

## Project structure

```text
app/src/main/java/com/falcor/viewer/
  FalcorApp.kt, MainActivity.kt
  cast/          # CastOptionsProvider + CastHelper (single stream)
  data/          # API, models, repo, media, prefs (DataStore + secure), WebSocket
  player/        # Live WebView, Exo clip player, OkHttp preview, LibVLC, talk WebView
  ui/            # home, camera, dashboards, alerts, navigation, player chrome
```

## Known limits

- **Dashboard Cast:** not implemented — only the focused camera stream is castable.
- **Cast + JWT:** Default Media Receiver cannot send Frigate auth headers; prefer open HTTP on the LAN for Cast.
- **Detection boxes:** depend on Frigate WS payloads mirroring events/tracked objects; some Frigate versions expose richer data in the web UI only.
- **WebView live:** uses go2rtc HTML players Frigate already ships; if those routes 404, Falcor falls back automatically.

## License

Source provided for the Falcor / gaming09 project. Frigate, LibVLC, ExoPlayer, and Cast SDK are third-party with their own licenses.
