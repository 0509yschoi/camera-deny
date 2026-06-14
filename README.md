# StudyCaptureHelper

An Android reference project for a user-visible, consent-based study and
accessibility workflow:

1. The user starts a visible session from the app.
2. A camera foreground service captures from a USB UVC adapter.
3. A vision model summarizes the study material.
4. Android TextToSpeech reads the summary through the active media route.

This project intentionally does not implement covert capture, hidden operation,
or automated exam-answer delivery.

## Architecture

```text
Compose UI -> MainViewModel -> SettingsRepository (DataStore)
                   |
                   +-> CaptureForegroundService
                         |- CameraCapture (UVC SDK adapter)
                         |- ImageAnalyzer (OpenAI Responses API)
                         |- ThermalPolicy
                         `- SpeechOutput (Android TTS)

WorkManager -> delayed cache maintenance only
```

The 15-minute minimum for periodic WorkManager requests makes it unsuitable for
a 50-second capture timer. The foreground service owns the active-session loop;
WorkManager handles deferrable maintenance.

## Required Integration Work

### UVC camera

`UvcCameraCapture` is a hardware boundary, not a fake implementation. Android's
USB host APIs enumerate and communicate with USB devices but do not provide a
general UVC frame decoder. Select a maintained UVC SDK, then implement:

- USB device discovery and permission
- supported preview/capture size negotiation
- JPEG capture with bounded buffers
- disconnect and reattach handling
- tests on each intended sensor/bridge combination

Keep vendor SDK objects inside `data/UvcCameraCapture.kt` so the domain and
service layers remain independent.

### OpenAI authorization

Never ship a long-lived OpenAI API key in the APK. Implement
`ApiTokenProvider` with an authenticated backend that returns a short-lived
credential, or proxy the entire analysis request through that backend.

The sample uses `POST /v1/responses` with `input_text` and a base64
`input_image`. The prompt summarizes study material and excludes personal data.

### Bluetooth audio

TTS uses `USAGE_ASSISTANCE_ACCESSIBILITY`. Android routes it through the active
system output, including A2DP when the user selected a Bluetooth media device.
The app does not silently force or change the user's audio route.

## Runtime Notes

- Minimum SDK: 26
- Target/compile SDK: 35
- Capture starts only from the visible activity.
- The foreground notification remains visible and includes a Stop action.
- Thermal status increases the delay to 2x or 4x.
- Exceptions and API payloads are not logged.
- `START_NOT_STICKY` avoids restarting camera access without a fresh user action.
- Doze and OEM power management can still delay work; exact 50-second timing is
  not guaranteed.

## Build

Open the directory in Android Studio with JDK 17, sync Gradle, then run:

```shell
./gradlew test
./gradlew assembleDebug
```

Before an end-to-end run, bind a UVC SDK and a backend token provider. Until
then, the service intentionally stops with a minimal error notification.

## GitHub Releases Update

GitHub-hosted builds automatically embed `${owner}/${repository}` and check the
latest non-draft, non-prerelease GitHub Release when the app opens. When a newer
semantic version is available, the app downloads its APK through Android
DownloadManager. The completion notification opens Android's package installer.

Android accepts the update only when:

- the new APK uses the same application ID and signing key
- its `versionCode` is greater than the installed build
- the user allows installs from this app on Android 8 or newer

Configure these GitHub repository secrets:

```text
RELEASE_KEYSTORE_BASE64
RELEASE_STORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

`RELEASE_KEYSTORE_BASE64` is the base64-encoded contents of a permanent release
keystore. Back up that keystore securely; losing it prevents future updates over
an installed release.

Push a version tag to publish:

```shell
git tag v1.1.0
git push origin v1.1.0
```

The workflow uses the tag as `versionName`, the GitHub run number as
`versionCode`, signs the release APK, and attaches it to the GitHub Release.
Local builds do not contain a repository name unless supplied explicitly:

```shell
./gradlew assembleDebug -PGITHUB_REPOSITORY=owner/repository
```
