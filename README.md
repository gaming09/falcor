# Falcor — the Frigate Viewer

**Falcor** is an Android client for [Frigate NVR](https://frigate.video/). Browse cameras, watch smooth live video with **live audio**, pinch-zoom, press-and-hold talk-back, rearrange the home grid, review clips, pin dashboards, control PTZ, and cast a single camera stream.

Package ID: `com.falcor.viewer`  
Version: **0.1.13**

## Features (0.1.13)

- **Tap → Falcor mute bar** — tap the live picture for a short-lived Falcor chrome bar (mute/unmute); WebView embeds never use Frigate `#cameras/` SPA (no history sidebar). Mute only toggles `video`/`audio` + `AudioContext` (no DOM mute clicks that flipped the stream).
- **WebView-primary live** — go2rtc/Frigate HTML embeds only (`live/webrtc/webrtc.html`, `api/go2rtc/webrtc.html` / `stream.html`, `mse.html` with `media=video+audio`). Native WebRTC / ExoPlayer HLS / VLC demoted after WebView exhausts; OkHttp MJPEG last. Audio path unchanged from 0.1.12.
- **Detections** — eye toggles Compose `DetectionOverlay` only (detect w/h letterbox from `/api/config`); never swaps the live embed URL to Frigate SPA.
- **Larger Hold to talk** — centered under History (not in the chip row); PTZ chip stays near stream controls. Detections eye uses on/off contentDescriptions.
- **Cast via MediaRouter** — if no Cast session, opens the system route picker with a snackbar; prefers unauthenticated `http://{host}:5000/api/...` HLS/MJPEG when Frigate base is `:8971` (Chromecast cannot send JWT).
- **Long-press + drag reorder** on the home camera grid — order persisted in DataStore; new cameras append at the end.
- **Smoother camera enable/disable** — optimistic Switch, disabled while in-flight, soft merge by name (no full-grid refresh flash / scroll jump).
- **Smarter config scan** on login / home refresh — deep rule-based analysis of `GET /api/config` (+ `GET /api/go2rtc/streams` keys): maps live / talk / PTZ / listen-audio per camera; listen audio still detected when talk is missing; prefers live stream names; heuristics documented below.
- **Home camera enable/disable** — Frigate WebSocket `{camera}/enabled/set` with `ON`/`OFF`, HTTP PUT fallback, home keeps WS connected, snackbar on failure.
- **Press-and-hold talk (Frigate-style)** — same live frame, WebRTC `media=video+audio+microphone`, no HTML player chrome.
- **Live listen audio** — native WebRTC AudioTrack mute; WebView talk uses `media=video+audio+microphone`.
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
| go2rtc source contains `onvif://`, `reolink://`, `backchannel`, `#audio=opus` | talk capable |
| `camera.audio.enabled` **or** source `#audio=` / aac / opus / pcm_* **or** talk | `hasListenAudio` (mute/listen OK even without talk) |
| `camera.onvif` / ptz/info | `showPtz` |
| Prefer `live.streams` main/sub roles; skip talk-named keys for live | `liveStreamName` |

Logcat tag `FrigateRepository` prints a short per-camera summary (`talk=… listen=… liveStream=…`).

### Live path

1. **WebView** — go2rtc/Frigate embed pages only (`live/webrtc/webrtc.html?media=video+audio`, go2rtc `webrtc.html` / `stream.html`, MSE). Never Frigate `#cameras/` SPA. Auth cookie/JWT injected. Mute via `applyMuteJs` / `WebViewAudioController` on user gesture (media elements + AudioContext only).
2. ExoPlayer authenticated HLS (`/api/go2rtc/stream.m3u8?src=`) — after WebView exhausts; mute via `player.volume`
3. LibVLC on remaining HLS/MJPEG/RTSP candidates (volume 0/100)
4. Optional native WebRTC (`POST /api/go2rtc/webrtc`) — demoted; kept for experiments / fallback flags
5. OkHttp MJPEG / snapshot poll (last resort)

**Talk/PTT** still uses WebView with `media=video+audio+microphone`. History clips still use ExoPlayer/LibVLC as before.

**WebRTC caveats (native fallback):** Frigate must proxy go2rtc WebRTC (`/api/go2rtc/webrtc`). Media path needs go2rtc WebRTC listen (typically UDP/TCP **8555**) and LAN candidates in go2rtc config for non-localhost viewers.

### Two-way talk

Press and hold **Hold to talk** (large button centered under History). Falcor:

1. Keeps the same black WebView frame (no ugly HTML5 player chrome).
2. Loads `$base/live/webrtc/webrtc.html?src=<talkOrLive>&media=video+audio+microphone` (plus go2rtc fallbacks).
3. Grants WebView `AUDIO_CAPTURE` via `WebChromeClient.onPermissionRequest`.
4. Injects CSS/JS to strip `controls` and autoplay.
5. On release, restores WebView-primary live listen (Compose mute → JS).

**Frigate-side caveats:** Talk still requires a go2rtc stream that supports two-way audio. If hold-to-talk never connects after mic permission, check Frigate/go2rtc talk config for that camera.

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
  player/        # Native WebRTC live, Exo HLS + clip, WebView talk, OkHttp preview, LibVLC
  ui/            # home, camera, dashboards, alerts, navigation, player chrome
```

## Privacy / secrets

- `.gitignore` excludes `local.properties`, `*.apk`, `*.keystore` / `*.jks`, `.env`, and similar.
- README and UI examples use placeholders only (`https://frigate.example:8971`, `camera_front`, `YOUR_TOKEN`).
- Never commit user Frigate credentials, JWTs, or screenshots that expose real camera names / LAN IPs.

### Cast (single camera)

Tap Cast on the camera screen. If no Cast session is connected, Falcor opens the system MediaRouter chooser and shows “Pick a Cast device…”. Streams prefer `http://{host}:5000/api/go2rtc/stream.m3u8?src=…` (or MJPEG) so Chromecast does not need a JWT. Use placeholders only in docs (`frigate.example`, `camera_front`).

### Live audio mute

The app-bar speaker button drives **WebRTC AudioTrack** enable/volume on the native live path (and ExoPlayer volume on HLS fallback). When talk/live is on WebView, the same click also runs `WebViewAudioController.applyMute` (user-gesture unmute+play).

## Known limits

- **Dashboard Cast:** not implemented — only the focused camera stream is castable.
- **Cast + JWT:** Default Media Receiver cannot send Frigate auth headers. Falcor rewrites `:8971` bases to `http://{host}:5000/api/...` for Cast when possible; if your Frigate API is not open on :5000, Cast may fail — use placeholders like `http://frigate.example:5000` in docs, never real LAN IPs.
- **Mute + autoplay:** Native WebRTC unmute is immediate via AudioTrack. WebView talk/fallback may still need a mute-button tap for unmuted autoplay.
- **Talk:** depends on Frigate go2rtc talk/onvif/reolink audio; app grants WebView mic but cannot fix missing server talk config.
- **Detection boxes:** depend on Frigate WS payloads; some versions expose richer data in the web UI only.
- **WebView live:** uses go2rtc HTML players Frigate already ships; if those routes 404, Falcor falls back automatically.

## License

Source provided for the Falcor / gaming09 project. Frigate, go2rtc, LibVLC, ExoPlayer, Stream WebRTC Android, and Cast SDK are third-party with their own licenses.
