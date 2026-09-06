# Falcor — the Frigate Viewer

**Falcor** is an Android client for [Frigate NVR](https://frigate.video/). Browse cameras, watch live and recorded video with LibVLC, control PTZ, use talk-back when available, and review detection events — all against your self-hosted Frigate instance.

Package ID: `com.falcor.viewer`

## Features

- **Persistent login** — Frigate base URL plus username/password (`POST /api/login` → JWT) or pasted Bearer token, stored in `EncryptedSharedPreferences`. Session restores on launch. Self-signed HTTPS on :8971 is trusted for local NVR use.
- **Camera grid** — cameras from `GET /api/config`, with enable/disable toggles via Frigate’s runtime camera API (`PUT /api/camera/{name}/set/enabled`).
- **LibVLC streaming** — live RTSP (go2rtc restream on port **8554**) with main/sub stream switching, plus recording playback via Frigate VOD/clip URLs.
- **Two-way audio** — talk UI when the camera/config suggests audio support; requests `RECORD_AUDIO` and switches to a talk-capable go2rtc stream when possible. Hidden/disabled gracefully otherwise.
- **PTZ** — toolbar / chip button opens a dedicated bottom-sheet D-pad when Frigate reports PTZ (`/api/{camera}/ptz/info` or ONVIF in config). Commands use the same **WebSocket** path as the official Frigate web UI (`ws(s)://<host>/ws`, topic `{camera}/ptz`). Press-and-hold sends `MOVE_*` / `ZOOM_*` / `FOCUS_*`; release sends `STOP`. Presets listed from ptz/info. Controls hide when unsupported.
- **History scrubber** — recordings from `/api/{camera}/recordings`; scrubbing seeks the VLC player using Frigate VOD/clip endpoints.
- **Alerts** — events from `/api/events` with camera/label/clip/snapshot filters; open snapshot or clip in a review screen.
- **Dark Material 3 UI** — Jetpack Compose, fully localized English strings in `res/values/strings.xml`.

## Requirements

- Android Studio Ladybug / Koala or newer (AGP 8.7+)
- JDK 17+ (Android Studio’s embedded JDK is fine)
- Android device or emulator, **API 26+**
- A reachable Frigate instance (0.13+ recommended; camera enable API is 0.14+)

## Open in Android Studio

1. `git clone https://github.com/gaming09/falcor.git`
2. Open the project folder in Android Studio (**Open** → select the repo root with `settings.gradle.kts`).
3. Let Gradle sync. If the wrapper jar is missing, Android Studio will offer to generate it, or run the commands in [Gradle wrapper](#gradle-wrapper) below.
4. Run the **app** configuration on a device/emulator.

## Build from the CLI

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release:

```bash
./gradlew assembleRelease
```

### Gradle wrapper

This repo includes `gradle/wrapper/gradle-wrapper.properties`. If `gradlew` / the wrapper JAR are not present yet:

```bash
# With Gradle installed locally:
gradle wrapper --gradle-version 8.11.1

# Or open in Android Studio and use “Create Gradle wrapper”
```

On Linux you can install a JDK first:

```bash
sudo apt install openjdk-21-jdk   # or openjdk-17-jdk where available
```

Full Android SDK install is only required for `assembleDebug`; opening the project in Android Studio is enough to develop.

## Connect to Frigate

On first launch, enter your Frigate **base URL** (no trailing `/api`):

| Setup | Example URL |
| --- | --- |
| Local HTTP (unauthenticated) | `http://192.168.1.50:5000` |
| Authenticated UI port | `https://192.168.1.50:8971` |
| Tailscale / hostname | `https://frigate.example.com` |

### Authentication (not HTTP Basic)

Frigate’s authenticated port (**8971**) uses JWT cookies, **not** HTTP Basic:

1. Falcor `POST`s `/api/login` with JSON `{"user":"…","password":"…"}`.
2. Frigate sets a cookie (default name `frigate_token`) containing the JWT.
3. Falcor stores that JWT and sends `Authorization: Bearer <token>` on later HTTP and WebSocket calls.
4. `GET /api/config` verifies the session.

You can also paste an existing JWT / API token; that overrides username/password. With no user/pass/token (typical port **5000**), Falcor just loads config unauthenticated.

Credentials (including the JWT) are encrypted on-device and restored automatically.

### Self-signed HTTPS (LAN / local NVR)

Frigate generates a **self-signed** TLS certificate for port 8971. Falcor intentionally uses a **permissive OkHttp TrustManager + hostname verifier** (and trusts user CAs in `network_security_config`) so LAN self-signed certs work without installing the Frigate CA on the phone.

This is intentional for **self-hosted / private-network** Frigate only. Do not point Falcor at untrusted public HTTPS hosts expecting the same trust behavior — MITM protection is effectively disabled for API/WebSocket TLS.

### Streaming notes

- Live video prefers **go2rtc RTSP**: `rtsp://<frigate-host>:8554/<camera_or_stream_name>`. Ensure port **8554** is reachable from the phone (same LAN or VPN).
- Thumbnails use `GET /api/{camera}/latest.jpg`.
- History/playback uses `/api/vod/...` and `/api/{camera}/start/.../end/.../clip.mp4`.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Frigate API + streams |
| `RECORD_AUDIO` | Two-way talk (only when you enable Talk) |
| Cleartext HTTP | Allowed so local `http://` Frigate installs work (`usesCleartextTraffic` + network security config) |
| Self-signed TLS | OkHttp permissive TrustManager for local Frigate `:8971` (see above) |

## Project structure

```text
app/src/main/java/com/falcor/viewer/
  FalcorApp.kt, MainActivity.kt
  data/          # Retrofit API, models, repository, secure prefs, Frigate WebSocket client
  player/        # LibVLC Compose AndroidView
  ui/            # theme, login, home, camera, alerts, navigation
```

## Frigate API usage (summary)

| Feature | Endpoint(s) |
| --- | --- |
| Login (JWT) | `POST /api/login` body `{"user","password"}` → `frigate_token` cookie / Bearer |
| Config / cameras | `GET /api/config` |
| Enable/disable | `PUT /api/camera/{cam}/set/enabled` body `{"value":"ON"|"OFF"}` |
| Events | `GET /api/events` |
| Recordings | `GET /api/{cam}/recordings` |
| PTZ info | `GET /api/{cam}/ptz/info` |
| PTZ move | WebSocket `ws(s)://<base>/ws` — JSON `{"topic":"{cam}/ptz","payload":"MOVE_LEFT","retain":false}` (same as Frigate web UI); HTTP `GET /api/{cam}/ptz/{command}` is last-resort fallback only |
| Live | go2rtc `rtsp://host:8554/...` |
| Event media | `/api/events/{id}/snapshot.jpg`, `/clip.mp4` |

Falcor degrades gracefully when an endpoint is missing or returns 404 (e.g. PTZ or talk on cameras that do not support them).


## PTZ (WebSocket)

Falcor mirrors the Frigate web UI for pan/tilt/zoom:

1. Derive the socket URL from the saved HTTP base: `http`→`ws`, `https`→`wss`, then append `/ws`  
   (e.g. `http://192.168.1.10:5000/` → `ws://192.168.1.10:5000/ws`).
2. After `OPEN`, send `{ "topic": "onConnect", "message": "", "retain": false }`.
3. Commands: `{ "topic": "<camera>/ptz", "payload": "<CMD>", "retain": false }` where `<CMD>` is  
   `MOVE_UP` / `MOVE_DOWN` / `MOVE_LEFT` / `MOVE_RIGHT` / `ZOOM_IN` / `ZOOM_OUT` / `FOCUS_IN` / `FOCUS_OUT` / `STOP` / `preset_<name>`.
4. Auth: the same `Authorization: Bearer <JWT>` header used for HTTP is attached to the WebSocket handshake (permissive TLS for `wss://` on :8971).
5. Feature detection still uses `GET /api/{camera}/ptz/info` (features + presets). The camera screen shows a **PTZ** action in the top app bar; tapping opens a modal bottom sheet with press-and-hold controls.

## License

Source provided for the Falcor / gaming09 project. Frigate and LibVLC are third-party projects with their own licenses.
