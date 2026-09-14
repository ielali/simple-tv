# Simple TV — guide for AI developers

Simple TV is an Android TV app that makes a modern set-top box behave like a 1990s television for
elderly viewers with no digital background. One remote, numbered channels, nothing else on screen.
Sources are IPTV streams and YouTube live channels. A caregiver configures channels from a phone or
laptop through a web page the TV serves on the home network.

This file is the contract for any AI agent working in this repository. Read it fully before changing code.

## Product rules (non-negotiable)

1. **The viewer never sees a menu.** Every screen the viewer can reach with digits, channel up/down,
   OK, BACK or INFO must be the video or a big banner over it. Settings live behind MENU and are a
   single read-only overlay showing the config URL.
2. **The remote is the only input.** No touch, no cursor, no on-screen keyboard for the viewer.
   Volume, mute and power keys must pass through to the system untouched (`TvViewModel.handles`).
3. **BACK never exits.** It cancels pending digits, nothing more. HOME is handled by making the app
   the home screen (see docs/DEPLOYMENT.md).
4. **Never blank.** A missing number lands on the nearest real channel. Stream errors show
   "No signal" and keep retrying. An empty channel list shows the config URL in large text.
5. **Text on the TV is huge.** Banner number 96sp, names 48sp, hints 32sp minimum. Light text on
   dark, no thin weights.
6. **YouTube goes through the official embedded player or the YouTube app only.** Never add
   stream-URL extraction (yt-dlp, NewPipe, Invidious) or user-agent spoofing to get past Google's
   WebView sign-in block. Both violate the terms and break constantly. See ADR-007.
7. **Configuration happens off the TV.** New settings go into the web UI at
   `app/src/main/assets/config/index.html` and the JSON API in `ConfigServer`, not into on-TV forms.

## Layout

```
app/src/main/kotlin/com/ielali/simpletv/
  SimpleTvApp.kt          Application: owns ChannelRepository and starts ConfigServer
  MainActivity.kt         Fullscreen activity; dispatchKeyEvent routes all remote keys to TvViewModel
  tv/ChannelTuner.kt      Pure Kotlin digit-entry state machine (timeout / max digits / OK)
  tv/ChannelNavigator.kt  Pure functions: resolve number, next, previous
  tv/TvViewModel.kt       Glue: repo + tuner + player, exposes TvUiState
  tv/PlayerScreen.kt      Compose UI: video surface, YouTube WebView, banner, overlays
  player/PlaybackEngine.kt Media3 ExoPlayer wrapper tuned for live TV
  youtube/YouTubeEmbed.kt Parses YouTube links/ids and builds the iframe page
  data/Channel.kt         Channel model + ChannelList (JSON, kotlinx.serialization)
  data/ChannelRepository.kt JSON-file persistence, StateFlow of the list
  data/AppSettings.kt     App-wide settings (YouTube playback mode)
  data/Provider.kt        IPTV subscription (Xtream Codes or M3U link), URL building, Basic auth
  data/ProviderRepository.kt providers.json; blank password on write keeps the stored one
  data/ProviderImport.kt  Pure merge of a fetched playlist into the channel list
  data/JsonStore.kt       Generic one-file JSON persistence with a StateFlow
  data/SettingsRepository.kt settings.json persistence
  data/M3uParser.kt       IPTV playlist import
  config/ConfigServer.kt  Ktor CIO server on :8080, serves web UI and JSON API
  config/NetworkAddress.kt LAN IP discovery
  config/QrCode.kt        ZXing wrapper: URL -> module matrix (pure JVM)
  tv/QrCodeView.kt        Compose canvas renderer for the QR matrix
  boot/BootReceiver.kt    Start on boot
app/src/main/assets/config/index.html   Caregiver web UI (vanilla JS, no build step)
app/src/test/kotlin/...                 JVM unit tests for everything without Android deps
docs/                                   Feasibility, architecture, decisions, backlog, deployment
```

## Working agreements

- **Pick work from `docs/BACKLOG.md`**, top to bottom unless told otherwise. Move an item to "Done"
  in the same commit that finishes it. Add new items you discover rather than fixing them silently.
- **Record decisions in `docs/DECISIONS.md`** when you choose between approaches (library, protocol,
  UX behaviour). One short entry: context, decision, consequences.
- **Pure logic stays Android-free.** Anything in `tv/ChannelTuner`, `tv/ChannelNavigator`, `data/`,
  `youtube/` must compile without Android so it is unit-testable on the JVM. Put Android calls in the
  ViewModel, Activity, Compose or engine classes.
- **Every behaviour change to pure logic ships with a unit test.** Tests use JUnit 4 and
  `kotlinx-coroutines-test` with virtual time; see `ChannelTunerTest` for the pattern
  (launch collector, `runCurrent()`, act, `advanceTimeBy`, assert).
- **Compose only**, no XML layouts or Leanback fragments. Material3 `Text` is fine; do not pull in
  `androidx.tv` unless a backlog item needs its components.
- **Keep dependencies in `gradle/libs.versions.toml`.** Bump versions in one commit on their own.
- **No secrets, no analytics, no network calls except stream playback, YouTube embeds and the LAN
  config server.**
- Commit messages: imperative summary line under 72 chars, blank line, why-not-what body when
  the diff does not explain itself.

## Build and verify

```bash
./gradlew testDebugUnitTest      # JVM unit tests (fast, run before every commit)
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew lintDebug              # Android lint
adb connect <tv-ip>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ielali.simpletv/.MainActivity
adb shell input keyevent KEYCODE_1   # simulate remote keys; see docs/DEPLOYMENT.md
```

CI (`.github/workflows/android.yml`) runs tests, assembles the debug APK, runs lint and uploads the
APK as an artifact. A push that turns CI red is a bug to fix immediately.

Sandbox note: some AI sandboxes block `dl.google.com` / `maven.google.com`. If the Android Gradle
Plugin cannot be resolved, verify the pure-logic packages with a throwaway Kotlin/JVM Gradle project
(copy `data/`, `tv/ChannelTuner.kt`, `tv/ChannelNavigator.kt`, `youtube/` and their tests) and rely
on CI for the Android build. Say so in the commit or PR description.

## Definition of done for a backlog item

- Behaviour matches the product rules above.
- Unit tests for pure logic pass; CI green.
- Tested on a real Android TV device or emulator when the change touches playback, key handling or
  the WebView. Note the device in the PR.
- `docs/BACKLOG.md` updated; `docs/DECISIONS.md` updated if a choice was made.
