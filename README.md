# Falcor — the Frigate Viewer

**Falcor** is an Android client for [Frigate NVR](https://frigate.video/). Browse cameras, watch smooth live video with **live audio**, pinch-zoom, press-and-hold talk-back, rearrange the home grid, review clips, pin dashboards, control PTZ, and cast a single camera stream.

Package ID: `com.falcor.viewer`  
Version: **0.1.27**

## Features (0.1.27-debug)

- **MSE-first page-order (0.1.27)** — when live `src` lacks opus/A/V+listen remux, prefer `mse.html?src=` first (Frigate web path for plain RTSP/AAC), then webrtc fallback. **No hasListen gate** on page order — Amcrest/plain RTSP gets MSE without expanding detectListen on home load. Opus/A/V+listen remux stays **webrtc-first** (Reolink). detectListenAudio / cameraHasListenCapableStream restored to **0.1.25**. Per-camera `runCatching` in getCameras/rebuildCapabilityMap so one bad derive cannot blank the home grid. No camera-name hardcodes; no JS silentAvAdvance.
- **Thin embed lock (0.1.27)** — `key(cameraName)` fresh WebView per camera; block `#cameras` / `/cameras/`; require embed `?src=`; on non-embed navigate reload intended once then fail to OkHttp.
- **Probe href (0.1.26+)** — FalcorAudioProbe snackbar includes truncated `location.href` for FrontYard→roaming3 embed proof.
- **Prefer A/V+listen live src (0.1.22)** — live WebView `?src=` prefers go2rtc/main keys with **both** video and audio (`#video=` / plain `rtsp://` **and** `#audio=`/opus/aac). Skips audio-only helpers (`ffmpeg:…#audio=opus` without video — typical `*_webrtc` / "WebRTC Audio"). Main/Sub chips bind to `state.quality` (default MAIN); probe `q=` matches. Video-only cams keep video src + no-listen snackbar.
- **Diagnostic probe for listen audio (0.1.20)** — after live WebView playing (+ ~2s), Snackbar + `Log.i("FalcorAudioProbe")` report src/quality/pathKind/muted/volume/tracks (probe-only; no product mute changes). Kept to verify A/V src + `vTracks=1`.
- **Native HTML5 controls (0.1.19)** — live WebView leaves the native control bar **visible** (`controls=true`); do not CSS-hide `::-webkit-media-controls*`. Default muted is fine; user unmutes via the HTML5 bar. Removed AppBar mute IconButton, Tap-for-sound overlay, `__falcorMuted` / `applyMute` storms, and volumechange re-sync that fought the page.
- **Live listen audio** — WebView play + `STREAM_MUSIC` / `AudioFocusRequest`; mute state owned by native controls only. Minimal chrome JS (black background / object-fit) does not strip controls or force mute.
- **Push-to-talk** — broader Reolink/ONVIF/backchannel/opus talk detection; mic grant only while talking; while talking never falls through to ExoPlayer.
- **WebView-primary live (kept)** — single go2rtc embed (`webrtc.html` / mse); one surface only. No ExoPlayer on the open-camera path.
- **Fallback** — if WebView exhausts all page URLs → OkHttp MJPEG/snapshot (replaces WebView). ExoPlayer demoted / unused on live open.
- **Version in UI** — home app bar and connect screen show `Falcor {VERSION_NAME}` from `BuildConfig`.
- **Detections** — eye toggles Compose `DetectionOverlay` only (detect w/h letterbox from `/api/config`); never reloads live URLs.
- **Larger Hold to talk** — centered under History (not in the chip row); PTZ chip stays near stream controls.
- **Cast via MediaRouter** — if no Cast session, opens the system route picker with a snackbar; prefers unauthenticated `http://{host}:5000/api/...` HLS/MJPEG when Frigate base is `:8971` (Chromecast cannot send JWT).
- **Long-press + drag reorder** on the home camera grid — order persisted in DataStore; new cameras append at the end.
- **Smoother camera enable/disable** — optimistic Switch, disabled while in-flight, soft merge by name (no full-grid refresh flash / scroll jump).
- **Smarter config scan** on login / home refresh — deep rule-based analysis of `GET /api/config` (+ `GET /api/go2rtc/streams` keys): maps live / talk / PTZ / listen-audio per camera; listen audio still detected when talk is missing; prefers live stream names; heuristics documented below.
- **Home camera enable/disable** — Frigate WebSocket `{camera}/enabled/set` with `ON`/`OFF`, HTTP PUT fallback, home keeps WS connected, snackbar on failure.
- **Press-and-hold talk (Frigate-style)** — WebView WebRTC `media=video+audio+microphone`; release restores WebView live.
- Pinch-zoom, stronger PTZ, clips, dashboards, Cast.

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
| Local HTTP (unauthenticated) | `http://frigate.example:5000` |
| Authenticated UI port | `https://frigate.example:8971` |

Use placeholders in docs and screenshots (`camera_front`, `YOUR_TOKEN`). Never commit real LAN IPs, camera names from your site, JWTs, or passwords.

Frigate :8971 uses JWT (not HTTP Basic). Falcor trusts self-signed TLS for local NVR use — do not point at untrusted public hosts expecting the same.

### Config capability scan (heuristics)

On login and home refresh Falcor always fetches `/api/config` and optionally `/api/go2rtc/streams`, then builds a per-camera map (persisted in DataStore). No cloud AI — solid rule-based “understanding”:

| Signal | Result |
| --- | --- |
| `camera.live.streams` role/value contains `talk` / `twoway` / `doorbell` | `showTalk`, `talkStreamName` |
| go2rtc key matching camera + talk markers | talk stream |
| go2rtc source contains `onvif://`, `reolink://`, `backchannel`, `#audio=opus`, `codec=opus`, etc. | talk capable |
| ONVIF host + audio / Reolink vendor + audio | talk capable (broadened in 0.1.17) |
| `camera.audio.enabled` **or** source `#audio=` / aac / opus / pcm_* **or** talk | `hasListenAudio` (mute/listen OK even without talk) |
| `camera.onvif` / ptz/info | `showPtz` |
| Prefer A/V+listen (`#video=`/rtsp + `#audio=`), skip audio-only `*_webrtc` helpers; then main/sub by quality | `liveStreamName` / live `?src=` |

Logcat tag `FrigateRepository` prints a short per-camera summary (`talk=… listen=… liveStream=…`).

### Live path

1. **WebView go2rtc/Frigate embeds (primary)** — `webrtc.html` / mse pages (never Frigate `#cameras/` SPA). Single surface; native HTML5 controls visible (unmute via the bar).
2. OkHttp MJPEG / snapshot poll when WebView exhausts all page URLs (replaces WebView)
3. LibVLC on remaining candidates only if neither WebView nor OkHttp is active
4. ExoPlayer / native WebRTC — **demoted**; not started on open-camera

**Talk/PTT** uses the same WebView stack with `media=video+audio+microphone`. History clips still use ExoPlayer.

**WebRTC caveats (native fallback):** Frigate must proxy go2rtc WebRTC (`/api/go2rtc/webrtc`). Media path needs go2rtc WebRTC listen (typically UDP/TCP **8555**) and LAN candidates in go2rtc config for non-localhost viewers.

### Two-way talk

Press and hold **Hold to talk** (large button centered under History). Falcor:

1. Switches the live surface to a minimal talk WebView.
2. Loads `$base/live/webrtc/webrtc.html?src=<talkOrLive>&media=video+audio+microphone` (plus go2rtc fallbacks).
3. Grants WebView `AUDIO_CAPTURE` via `WebChromeClient.onPermissionRequest`.
4. Injects CSS that suppresses HTML5 media controls + autoplay (AppBar mute via JS; no DOM mute-button clicking).
5. On release, restores WebView-primary live (native HTML5 controls own mute).

**Frigate-side caveats:** Two-way audio needs a go2rtc source that supports talk-back (`onvif://` / `reolink://` with `#backchannel` / `#audio=opus`, or an explicit talk stream). Listen audio (`media=video+audio`) still works without a mic path. If hold-to-talk never connects after mic permission, verify Frigate/go2rtc talk config for that camera and that WebRTC (often UDP/TCP **8555**) is reachable from the phone.

### Camera enable / disable

Home grid Switch uses Frigate WS: `{"topic":"<cam>/enabled/set","payload":"ON"|"OFF","retain":false}` (same as Frigate web `useEnabledState`). HTTP `PUT /api/camera/{cam}/set/enabled` is fallback only. Failures show a snackbar and revert the Switch. While a toggle is in-flight the Switch is disabled; success merges only that camera’s flags so previews/scroll stay put.

### Home reorder

Long-press a camera card, then drag to rearrange. Order is stored in DataStore (`camera_order_json`). Refreshing cameras re-applies the saved order; unknown new cameras append at the end.

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
  player/        # WebView live/talk, Exo clip (+ demoted live), OkHttp preview, LibVLC, native WebRTC
  ui/            # home, camera, dashboards, alerts, navigation, player chrome
```

## Privacy / secrets

- `.gitignore` excludes `local.properties`, `*.apk`, `*.keystore` / `*.jks`, `.env`, and similar.
- README and UI examples use placeholders only (`https://frigate.example:8971`, `camera_front`, `YOUR_TOKEN`).
- Never commit user Frigate credentials, JWTs, or screenshots that expose real camera names / LAN IPs.

### Cast (single camera)

Tap Cast on the camera screen. If no Cast session is connected, Falcor opens the system MediaRouter chooser and shows “Pick a Cast device…”. Streams prefer `http://{host}:5000/api/go2rtc/stream.m3u8?src=…` (or MJPEG) so Chromecast does not need a JWT. Use placeholders only in docs (`frigate.example`, `camera_front`).

### Live audio mute

**Native HTML5 mute/volume bar** on the WebView live surface is the mute control. Falcor does not hide it or force `video.muted` from Compose — unmute via the bar to hear audio.

## Known limits

- **Dashboard Cast:** not implemented — only the focused camera stream is castable.
- **Cast + JWT:** Default Media Receiver cannot send Frigate auth headers. Falcor rewrites `:8971` bases to `http://{host}:5000/api/...` for Cast when possible; if your Frigate API is not open on :5000, Cast may fail — use placeholders like `http://frigate.example:5000` in docs, never real LAN IPs.
- **Mute + autoplay:** Live aims to start unmuted (audio focus + JS unmute retries). Some WebView/OS combos still need one tap on the video surface (user gesture) before audio is audible — AppBar mute then owns the state.
- **Talk:** depends on Frigate go2rtc talk/onvif/reolink backchannel; app grants WebView mic only while PTT is held but cannot invent missing server talk config.
- **Detection boxes:** depend on Frigate WS payloads; some versions expose richer data in the web UI only.
- **WebView live:** uses go2rtc HTML players Frigate already ships; if those routes 404, Falcor falls back automatically.

## License

Source provided for the Falcor / gaming09 project. Frigate, go2rtc, LibVLC, ExoPlayer, Stream WebRTC Android, and Cast SDK are third-party with their own licenses.
