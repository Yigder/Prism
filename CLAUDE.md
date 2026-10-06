# Prism

Unofficial YouTube Music client for Android (Kotlin + Jetpack Compose). Talks directly to
YouTube Music and ~10 lyric services from the phone; no backend. Features word-synced lyrics,
offline downloads, EQ/spatial audio, on-device "Replay" stats, and full Android Auto support.

## Stack
- Kotlin, Compose (Material 3), Media3 ExoPlayer + MediaSession, Room (KSP), DataStore,
  OkHttp, kotlinx.serialization, Coil 3, WorkManager, NewPipe Extractor, Haze (blur).
- JDK 17, compileSdk 37, targetSdk 36, minSdk 26. Package/applicationId: `com.prism.music`.
- Single Gradle module `:app`.

## Commands (Windows: use `.\gradlew.bat`)
- Build release APK: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/`
- Debug build: `./gradlew :app:assembleDebug`
- Unit tests: `./gradlew :app:testDebugUnitTest` (some hit live lyric/YT Music endpoints — needs network, can flake)

## Map (`app/src/main/java/com/prism/music/`)
- `MainActivity.kt` — entry point
- `AppContainer.kt` — manual dependency wiring (no Hilt/Dagger)
- `data/innertube/` — YouTube Music API, response parsing, search ranking
- `data/stream/` — stream URL resolving via NewPipe Extractor
- `data/lyrics/` — lyric providers + TTML/LRC/QRC/YRC/KRC parsers
- `data/db/`, `data/local/` — Room database / local storage
- `data/prefs/` — DataStore settings
- `data/model/`, `data/meta/`, `data/canvas/` — models, metadata, animated covers
- `playback/` — playback service, audio effects, Android Auto browse tree, car lyrics
- `download/` — downloads and smart downloads
- `ui/` — Compose: `screens/`, `player/`, `components/`, `theme/`
- `app/src/test/` — JUnit 4 unit tests
- `docs/screenshots/` — README images only

## Conventions / decisions
- Release builds are **unminified** on purpose: NewPipeExtractor + Rhino use reflection. Don't enable R8.
- Release is signed with the debug key so the APK installs directly.
- Lyrics priority: word-synced → line-synced → plain; user can switch source per song and offset timing.
- Privacy: all history/Replay/settings stay on device; no Prism server. Don't add telemetry.
- Not affiliated with Google/YouTube — keep the disclaimer in README.
- `*.apk`, `local.properties`, keystores are gitignored.

## Current status / Next steps
- **v1.1.0 (versionCode 2) is the first official release** — published 2026-10-06 as GitHub "Latest",
  tag `v1.1.0` → `70b6251` (UX rework), asset `Prism.apk`. v1.0.0 (2026-10-04) is now marked pre-release.
- Release flow: build `assembleRelease`, upload the APK as `Prism.apk` (README links to
  `releases/latest/download/Prism.apk`), notes include the Android Auto "parked/passengers only" warning.
- README screenshots (`docs/screenshots/`) were retaken for 1.1 on a Pixel 10 Pro XL with personal info
  and the mini player blurred (no way to hide the mini player in release builds). Keep blurring on retakes.
- README has a "Credits and inspiration" section (Apple Music, BitChord, Spotify Wrapped, lyric/canvas
  sources, libraries) — update it when borrowing new ideas or sources.
- On the user's Wi-Fi, `gh` API calls fail (`invalid character '<'`); git push works. Ask them to switch to hotspot.
- An untracked `Prism/` subfolder duplicates the project (with build output and logs) — decide whether to delete it.
- Next steps: _(fill in)_
