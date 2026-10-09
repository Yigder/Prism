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
- Release signing: `keystore.properties` + `prism-release.jks` in the repo root (both gitignored; back them up —
  losing the key means users must reinstall again). Without them, or with `-PdebugSign`, release falls back to the debug key.
  The move off the debug key: 1.3.0 is the last debug-signed release (adds Settings → About → Backup and a
  "needs a reinstall" flow in the update banner when the next APK's signer differs); 1.3.1+ are release-key signed.
  Backup format is `data/Backup.kt` (zip + `prism-backup.json`; restore is staged and applied in `PrismApp.onCreate`).
- Lyrics priority: word-synced → line-synced → plain; user can switch source per song and offset timing.
- Privacy: all history/Replay/settings stay on device; no Prism server. Don't add telemetry.
- Update banner (`data/Updates.kt`): Home asks GitHub's `releases/latest` (≤ every 6 h, always on; Settings → About checks on tap)
  and compares its tag to `versionName`. So every release must bump `versionName`/`versionCode`, use a `vX.Y.Z` tag,
  and attach the `.apk` asset; pre-releases are never offered. Tapping the banner downloads the APK in-app and
  hands it to `PackageInstaller` (one-time "Install unknown apps" permission, then Android's confirm); the APK
  must be Prism with a higher versionCode, signed with the same (debug) key, or it's refused. Shipped in 1.2.1.
- Playlist import (`ui/screens/ImportScreen.kt`, Library → Playlists → "Import playlists") opens TuneMyMusic's
  `tunemymusic.com/transfer/{spotify,apple-music}-to-youtube-music` pages in the browser; the user signs in there and it
  writes to their YT Music account. Prism syncs the library when the user returns. Never pass Prism's YT cookies to it.
  (A built-in link/CSV importer was written first and replaced by this, unreleased, at the owner's request.)
- Fresh-install defaults in `AppSettings` / `HomeSections.initial` are the owner's own settings (smart downloads 1 GB).
- Streams (`data/stream/StreamResolver.kt`): NewPipe resolves anonymously first; when signed in, songs it can't play
  (age-restricted, Music Premium-only, no usable audio) are re-asked via `InnerTube.signedInPlayer` as the TV client
  (`TVHTML5`, cookies + SAPISIDHASH, no PO token), then WEB_REMIX; ciphered URLs go through NewPipe's
  `YoutubeJavaScriptPlayerManager`. Bump `InnerTube.TV_CLIENT_VERSION` if TV playback starts being refused.
  **Broken as of 1.4.1**: YouTube accepts the TV request, but NewPipe (v0.26.5, latest; `dev` too) can't find the
  signature function in the current player JS ("Could not find deobfuscation function with any of the known patterns"),
  so these songs fail with "Couldn't unlock this track's stream". Every cookie-capable client returns ciphered URLs.
  The next queue item's stream is resolved while the current one plays (`PlaybackService.prefetchNext`).
- Lossless sync (`download/LosslessSync.kt`, 1.4.1): with lossless playback + the lossless add-on on, FLAC/WAV/AIFF
  files on the phone are looked up on YT Music and shown in Downloads (`DownloadInfo.lossless`), playing from the file.
  Runs on launch, ~15 s after MediaStore changes, and every 3 h (`LosslessSyncWorker`). Removing one only hides it
  (`lossless_hidden` prefs); Prism never deletes or moves the user's files.
- Not affiliated with Google/YouTube — keep the disclaimer in README.
- `*.apk`, `local.properties`, keystores are gitignored.

## Current status / Next steps
- **v1.4.1 (versionCode 11) is GitHub "Latest"** (2026-10-09): lossless sync (lossless files on the phone show up in Downloads,
  rescanned on media changes and every 3 h) and the next song's stream is resolved ahead (both checked on the phone).
  Signed-in playback of age-restricted / Premium-only songs shipped in the code but doesn't work (see Streams above), so
  the release notes leave it out. 1.4.0 (versionCode 10) was skipped: bumped but never released.
- v1.3.3 (versionCode 9, 2026-10-08) was the previous release: plain outline icons on the Settings hub (no coloured badges),
  sign-in leaves the WebView the moment YouTube's session cookies appear (account info + library sync run after Prism opens),
  and update checks are always on: the `checkUpdates` setting is gone; Settings → About → "Check for updates" asks GitHub now
  (`UpdateChecker.checkNow()`, also un-dismisses the banner).
  v1.3.2 (versionCode 8, 2026-10-07): Library → Import playlists (TuneMyMusic hand-off), and long-press
  any album/playlist/artist card or Home shortcut tile to play or shuffle it (`ui/components/PlayActions.kt`).
  v1.3.1 (versionCode 7): first release signed with the release key (cert SHA-256 `cbc462bc…`).
  v1.3.0 (versionCode 6): backup & restore, last debug-signed build. v1.2.2 (versionCode 5): drag to reorder Customize Home sections, "Greeting & shortcuts"
  renamed "Shortcut tiles" (greyed-out tile settings when that section is off), long-press a Library playlist to delete it.
  v1.2.1 (versionCode 4, 2026-10-07): in-app update install. v1.2.0 (`f3656fa`) added music videos as songs,
  cleaner playlists/Customize Home and the update banner. v1.1.0 (2026-10-06, `70b6251`) was the first official release;
  v1.0.0 (2026-10-04) is marked pre-release.
- Release flow: build `assembleRelease`, upload the APK as `Prism.apk` (README links to
  `releases/latest/download/Prism.apk`), notes include the Android Auto "parked/passengers only" warning.
- README screenshots (`docs/screenshots/`) were retaken for 1.1 on a Pixel 10 Pro XL with personal info
  and the mini player blurred (no way to hide the mini player in release builds). Keep blurring on retakes.
  `video.jpg` (music video playing, Kid Cudi) was added 2026-10-06 with the README "Music videos" feature notes.
  The player title is a looping marquee: take a burst of screencaps and pick the frame where it rests at the start.
  Use `adb shell screencap` + `adb pull` (PowerShell `exec-out >` redirection corrupts PNGs).
- README has a "Credits and inspiration" section (Apple Music, BitChord, Spotify Wrapped, lyric/canvas
  sources, libraries) — update it when borrowing new ideas or sources.
- Donations: Ko-fi at https://ko-fi.com/yigder — linked from a README header badge, a "Support Prism"
  section (before "Building from source"), and `.github/FUNDING.yml` (`ko_fi: yigder`, GitHub Sponsor button).
  Keep it optional/no-pressure; no in-app donation prompts unless the user asks.
- On the user's Wi-Fi, `gh` API calls fail (`invalid character '<'`); git push works. Ask them to switch to hotspot.
- An untracked `Prism/` subfolder duplicates the project (with build output and logs) — decide whether to delete it.
- Next steps: a signature solver of Prism's own so signed-in restricted songs play (NewPipe's regexes no longer match;
  yt-dlp now runs the player JS in a real JS engine; a headless WebView is one option).
