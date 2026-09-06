# Falcor — the Frigate Viewer

**Falcor** is an Android client for [Frigate NVR](https://frigate.video/). Browse cameras, watch live and recorded video with LibVLC, control PTZ, use talk-back when available, and review detection events — all against your self-hosted Frigate instance.

Package ID: `com.falcor.viewer`

## Features

- **Persistent login** — Frigate base URL plus optional username/password or JWT/API token stored in `EncryptedSharedPreferences`. Session restores on launch.
- **Camera grid** — cameras from `GET /api/config`, with enable/disable toggles via Frigate’s runtime camera API (`PUT /api/camera/{name}/set/enabled`).
- **LibVLC streaming** — live RTSP (go2rtc restream on port **8554**) with main/sub stream switching, plus recording playback via Frigate VOD/clip URLs.
- **Two-way audio** — talk UI when the camera/config suggests audio support; requests `RECORD_AUDIO` and switches to a talk-capable go2rtc stream when possible. Hidden/disabled gracefully otherwise.
- **PTZ** — on-screen pad when Frigate reports PTZ (`/api/{camera}/ptz/info`); sends move commands best-effort over HTTP (`/api/{camera}/ptz/{MOVE_*|STOP|ZOOM_*}`). Controls hide when unsupported.
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
| Local HTTP (common) | `http://192.168.1.50:5000` |
| Authenticated UI port | `https://192.168.1.50:8971` |
| Tailscale / hostname | `https://frigate.example.com` |

Optional:

- **Username / password** — Frigate auth (Basic)
- **JWT / API token** — sent as `Authorization: Bearer …` (overrides user/pass when set)

Credentials are encrypted on-device and restored automatically.

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

## Project structure

```text
app/src/main/java/com/falcor/viewer/
  FalcorApp.kt, MainActivity.kt
  data/          # Retrofit API, models, repository, secure prefs
  player/        # LibVLC Compose AndroidView
  ui/            # theme, login, home, camera, alerts, navigation
```

## Frigate API usage (summary)

| Feature | Endpoint(s) |
| --- | --- |
| Config / cameras | `GET /api/config` |
| Enable/disable | `PUT /api/camera/{cam}/set/enabled` body `{"value":"ON"|"OFF"}` |
| Events | `GET /api/events` |
| Recordings | `GET /api/{cam}/recordings` |
| PTZ info | `GET /api/{cam}/ptz/info` |
| PTZ move | `GET /api/{cam}/ptz/{command}` (best-effort; MQTT is Frigate’s primary PTZ path) |
| Live | go2rtc `rtsp://host:8554/...` |
| Event media | `/api/events/{id}/snapshot.jpg`, `/clip.mp4` |

Falcor degrades gracefully when an endpoint is missing or returns 404 (e.g. PTZ or talk on cameras that do not support them).

## License

Source provided for the Falcor / gaming09 project. Frigate and LibVLC are third-party projects with their own licenses.
