# Telly (formerly Simple TV) — guide for AI developers

Telly (code name Simple TV, package `com.ielali.simpletv`) is an IPTV player for Android TV and
Google TV. Each TV runs in one of two modes (ADR-033):

- **Simple mode**, the original product: a modern set-top box behaves like a 1990s television for
  elderly viewers with no digital background. One remote, numbered channels, nothing else on screen.
- **Full mode** (being built, backlog M1–M8): a household IPTV player with Live TV, a guide, films,
  series and search, driven by the remote.

Sources are IPTV subscriptions (Xtream Codes or M3U) and YouTube channels. The TV also serves a web
page on the home network where a caregiver or householder can set it up from a phone or laptop.
The page is optional in Full mode and the main way to configure Simple mode.

**"My channels"** is the numbered, hand-picked list the code calls the lineup (`ChannelList`). It is
what Simple mode plays, the first category of Full mode's Live TV, and what the number keys tune in
both modes.

This file is the contract for any AI agent working in this repository. Read it fully before changing code.

## Product rules (non-negotiable)

### All modes

A1. **The remote is the TV's input.** No touch, no cursor. Volume, mute and power keys pass through
    to the system untouched (`TvViewModel.handles`).
A2. **Never blank on a stream error.** Stream errors show "No signal" and keep retrying.
A3. **Every word on the TV screen is translated.** Put on-screen text in `values/strings.xml` and the
    same key in every `values-<lang>/strings.xml` (Arabic today). `TranslationsTest` fails the build
    when one is missing. Layouts must work right to left: use start/end, never left/right, except
    where the TV language must override the system direction (see `BannerOverlay`).
A4. **YouTube goes through the official embedded player or the YouTube app only.** Never add
    stream-URL extraction (yt-dlp, NewPipe, Invidious) or user-agent spoofing to get past Google's
    WebView sign-in block. Both violate the terms and break constantly. See ADR-007.
A5. **Never disable, hide or suspend the stock Google TV home** (`com.google.android.apps.tv.launcherx`),
    by `pm`, device-owner policy or debloat list. On Android 14 Google TV it can loop the device into
    black-screen recovery (RescueParty). In Simple mode's `sideload` build the home guard covers it
    (S7); elsewhere it is simply left alone. `tools/tv-admin.sh` enforces this; keep its protected
    list in step with any new tooling.
A6. **Configuration is possible off the TV.** Every setting can be changed from the web UI at
    `app/src/main/assets/config/index.html` through the JSON API in `ConfigServer`. In Full mode
    what a household needs day to day (add a subscription, browse, edit My channels, PIN, mode) can
    also be done with the remote; bulk editing and expert settings may stay web-only.
A7. **No content.** Telly ships with no channels and never suggests or links to a provider. Public
    guide listings are fine. This is what Google Play allows of an IPTV player.
A8. **The web page signs in with the PIN** (`PinAuth`). From M7 the PIN is required in both modes:
    it is set at first run, can be changed but not removed, and a TV updated without one keeps
    working while the page asks for one on its next visit. From M3 the TV never shows the PIN; the
    page's "Forgot PIN?" makes the TV show a one-time code for 2 minutes instead (ADR-033).

### Simple mode

S1. **The viewer never sees a menu.** Every screen the viewer can reach with digits, channel up/down,
    OK, BACK or INFO must be the video or a big banner over it. The one exception is the channel list
    on the Live TV button (ADR-022): channels by number with a preview window, big text, no settings.
    Settings live behind MENU and are a single read-only overlay showing the config URL.
S2. **No on-screen keyboard for the viewer.**
S3. **BACK never exits.** It cancels pending digits, nothing more. HOME is handled by making the app
    the home screen (see docs/DEPLOYMENT.md).
S4. **Never blank.** A missing number lands on the nearest real channel. An empty channel list shows
    the config URL in large text.
S5. **Text on the TV is huge.** Banner number 96sp, names 48sp, hints 32sp minimum. Light text on
    dark, no thin weights.
S6. **Leaving Simple mode needs the household** (from M3). Only from the web page, or by holding MENU
    for 5 s and typing the PIN with the number keys, also over YouTube. With no PIN set, the hold-MENU
    route is off. Nothing a viewer can stumble into leaves Simple mode; entering Simple mode needs no
    PIN, and turns the web server back on.
S7. **The home guard runs only in Simple mode and only in the `sideload` build** (from M1). The `play`
    build has no accessibility service; there Simple mode relies on Telly being the home app (ADR-033).
S8. **An update never changes a viewer's TV.** Existing installs open in Simple mode with their
    lineup, PIN and guard as they were.

### Full mode

F1. **Remote first, typing allowed.** The system keyboard may appear. Every typing field also shows a
    QR code that opens the web page at that form, and the TV fills in when the phone sends it.
F2. **Normal TV text sizes**: about 18–24sp for body text, 28–32sp for titles. Light text on dark,
    no thin weights.
F3. **Number keys tune My channels**, with the same tuner and banner as Simple mode. Other categories
    show the provider's numbering and cannot be tuned by number.
F4. **Adult categories are hidden by default** (recognised by name, or marked on the web page) and
    shown for the session after the PIN (which the TV never displays, A8).
F5. **Telly is an ordinary app in Full mode**: no home guard, not the home screen.

ADRs before ADR-033 cite the old single list of rules: 1 is S1, 2 is A1 and S2, 3 is S3, 4 is A2 and
S4, 5 is A3, 5b is S5, 6 is A4, 7 is A5, 8 is A6.
## Layout

```
app/src/main/kotlin/com/ielali/simpletv/
  SimpleTvApp.kt          Application: owns ChannelRepository and starts ConfigServer
  MainActivity.kt         Fullscreen activity; dispatchKeyEvent routes all remote keys to TvViewModel
  tv/ChannelTuner.kt      Pure Kotlin digit-entry state machine (timeout / max digits / OK)
  tv/ChannelNavigator.kt  Pure functions: resolve number, next, previous
  tv/ChannelListNav.kt    Pure: the channel list's keys, wrap and paging, visible window (ADR-022)
  guard/ButtonRoles.kt    Pure: which remote buttons open the list or go to the previous channel
  data/TextClean.kt       Pure: decodes HTML entities in provider names, drops junk guide titles
  data/Years.kt           Pure: a title's production year and film channels' year range
  data/CatalogSort.kt     Pure: sort orders for film/series lists and film channels (ADR-025)
  data/ChannelLogos.kt    Pure: iptv-org logo index and lookup by guide id or name (ADR-027)
  data/LogoService.kt     Finds logos for channels without one: guide, iptv-org, Wikidata, YouTube (ADR-027)
  data/WikiLogos.kt       Pure: Wikidata look-ups by name or brand and Commons picture addresses (ADR-027)
  tv/TvController.kt      App-scoped TV state: current channel, digits, banner, YouTube-app hand-off.
                          Android-free; driven by the screen and, over YouTube, by the guard
  tv/ExternalPlayer.kt    Opens the YouTube app / brings Telly forward from app context
  tv/TvViewModel.kt       Screen adapter: video surface + settings overlay, exposes TvUiState
  tv/LocaleSupport.kt     Applies the config page's TV language to Telly's screen only
  data/TvLanguage.kt      Supported TV languages (English, Arabic) and RTL detection
  tv/PlayerScreen.kt      Compose UI: video surface, YouTube WebView, banner, overlays
  player/PlaybackEngine.kt Media3 ExoPlayer wrapper tuned for live TV; never stops retrying
  player/QueueKeys.kt     Pure: Right/Left/OK on film and series channels (next, start or previous, pause)
  player/TrackPicker.kt   Pure: which subtitle and soundtrack to show for a film channel's wishes
  player/TuneTracker.kt   Pure: tuning / rebuffering / no-signal state and retry backoff
  player/PlaybackStats.kt Pure: first-picture time and freezes per channel, for the playback log (ADR-028)
  youtube/YouTubeEmbed.kt Parses YouTube links/ids (videos, live, playlists) and builds the player pages
  data/YouTubeProgress.kt Where each YouTube playlist channel stopped (ADR-023)
  data/Channel.kt         Channel model + ChannelList (JSON, kotlinx.serialization)
  data/ChannelRepository.kt JSON-file persistence, StateFlow of the list
  data/AppSettings.kt     App-wide settings (YouTube playback mode)
  data/Provider.kt        IPTV subscription (Xtream Codes or M3U link), URL building, Basic auth
  data/ProviderRepository.kt providers.json; blank password on write keeps the stored one
  data/ProviderImport.kt  Pure merge of a fetched playlist into the channel list
  data/ProviderLoader.kt  Downloads a provider: Xtream API first, then M3U; User-Agent retry
  data/XtreamApi.kt       player_api.php URLs and lenient JSON parsing (account, categories, streams)
  data/FetchErrors.kt     Caregiver-readable failure messages; redact() strips credentials
  data/HttpFetcher.kt     HTTP GET that returns status codes instead of throwing
  data/JsonStore.kt       Generic one-file JSON persistence with a StateFlow
  data/SettingsRepository.kt settings.json persistence
  data/M3uParser.kt       IPTV playlist import
  config/ConfigServer.kt  Ktor CIO server on :8080: web UI, PIN sign-in, status, settings, guard
  config/ContentRoutes.kt Lineup, subscriptions, catalog, film channels, YouTube, Play on TV routes
  config/ApiViews.kt      What the page may see: no stream addresses, no passwords (pure, tested)
  config/PreviewRoutes.kt The page's preview player (ADR-029): token, relay route, the bundled hls.js
  config/PreviewRelay.kt  Fetches a previewed stream on the TV and passes it on (Android-free, tested)
  data/HlsRewrite.kt      Pure: swaps every address in an HLS playlist for a made-up name
  data/PreviewSessions.kt Pure: the one running preview, its token, names and time limits
  data/PreviewRules.kt    Pure: the TV always wins; when a preview would compete with its stream
  data/PreviewChecks.kt   What each preview showed about its channel (worked, failed, not for
                          browsers), kept in preview_checks.json for the lists' marks
  config/PinAuth.kt       PIN rules: 4-6 digits, 5 tries then 5-minute lock, in-memory sessions
  data/Catalog*.kt        Cached catalogs, refresh service and schedule, queries, lineup flags
  data/PlaylistQueue.kt   Film/series queue rules (pure); data/QueuePlayback.kt plays and resumes
  data/SecretsStore.kt    Page PIN, YouTube key and TMDB key, never returned by the API
  data/FilmMeta.kt        Film details model and RatingRules (pure); TmdbApi/TmdbClient parse TMDB
  data/Guide.kt           TV guide (pure): XMLTV reader and times, Xtream short guide, matching, now/next
  data/GuideService.kt    Fetches, trims and caches guides; now/next per lineup channel; manual matches
  data/YouTubeSuggestions.kt Device-flow sign-in state, subscriptions and live-now cache (ADR-021)
  youtube/YouTubeAccount.kt Pure client for Google's device flow and the YouTube calls suggestions use
  data/MetadataService.kt Caches details per subscription, checks film channels' ratings in the background
  youtube/YouTubeLookup.kt YouTube link preview (oEmbed, optional Data API key)
  config/NetworkAddress.kt LAN IP discovery
  config/MdnsMessage.kt   Pure: reads and writes the mDNS packets for telly.local, picks the name (ADR-032)
  config/MdnsResponder.kt Answers for telly.local on the home network (Android-free)
  config/QrCode.kt        ZXing wrapper: URL -> module matrix (pure JVM)
  tv/QrCodeView.kt        Compose canvas renderer for the QR matrix
  boot/BootReceiver.kt    Start on boot (re-enables the guard first; its exemption allows the start)
  guard/HomeGuardService.kt Accessibility service: HOME/TV keys -> Telly, swallows shortcut keys,
                          covers the stock home and sponsored apps (soft kiosk, no factory reset)
  guard/HomeGuardPolicy.kt  Pure decisions for keys and windows; GuardKeys.kt mirrors KeyEvent codes
  guard/GuardSetup.kt     Enables/disables the service via WRITE_SECURE_SETTINGS (granted over adb)
  guard/KeyLog.kt         Recent key presses for the config page's "Remote buttons" card
  guard/BannerOverlay.kt  Channel banner drawn over the YouTube app (accessibility overlay window)
  guard/AccountPicker.kt  Pure detector for YouTube's "Who's watching?" + throttle + screen log
  data/SettingsMerge.kt   Partial PUT /api/settings; also sanitizes blocked keys
app/src/main/assets/config/index.html   Caregiver web UI (vanilla JS, one file, no build step);
                                        built from docs/design/handoff/Main.dc.html
app/src/main/assets/config/hls.min.js   hls.js 1.7.4 for the page's preview player (ADR-029), as
                                        published; its licence is beside it
tools/tv-admin.sh                       adb admin: status, install, guard-on/off, stock, debloat, restore
tools/tv-playback.py                    adb measurements: past freezes on the TV, stream shapes,
                                        provider timings, a model of the player's buffering (ADR-028);
                                        prints no stream address, host or credential
tools/debloat/*.txt                     Reversible debloat lists (disable, never uninstall)
tools/test/tv-admin.test.sh             Runs tv-admin.sh against a fake adb (CI runs it)
app/src/test/kotlin/...                 JVM unit tests for everything without Android deps
docs/                                   Feasibility, architecture, decisions, backlog, deployment
```

## Working agreements

- **Pick work from `docs/BACKLOG.md`**, top to bottom unless told otherwise. Move an item to "Done"
  in the same commit that finishes it. Add new items you discover rather than fixing them silently.
- **Record decisions in `docs/DECISIONS.md`** when you choose between approaches (library, protocol,
  UX behaviour). One short entry: context, decision, consequences.
- **Pure logic stays Android-free.** Anything in `tv/ChannelTuner`, `tv/ChannelNavigator`, `tv/TvController`, `data/`,
  `youtube/`, `guard/HomeGuardPolicy`, `guard/GuardKeys`, `guard/AccessibilityServiceList`, `guard/KeyLog`
  must compile without Android so it is unit-testable on the JVM. Put Android calls in the
  ViewModel, Activity, Compose or engine classes.
- **Unit tests compile against android.jar, not the full JDK.** JDK-only APIs such as
  `com.sun.net.httpserver` fail in CI even when a plain JVM run passes. Fake `TextFetcher` for HTTP
  logic; use `java.net.ServerSocket` when real sockets are needed (see `HttpFetcherTest`).
- **Every behaviour change to pure logic ships with a unit test.** Tests use JUnit 4 and
  `kotlinx-coroutines-test` with virtual time; see `ChannelTunerTest` for the pattern
  (launch collector, `runCurrent()`, act, `advanceTimeBy`, assert).
- **Compose only**, no XML layouts or Leanback fragments. Simple mode's screens use plain Compose and
  Material3. Full mode's screens may use `androidx.tv:tv-material` for focus, rails and cards
  (ADR-034); keep it out of Simple mode's screens.
- **Two build flavours from M1** (ADR-033): `sideload` carries the home guard, `play` leaves out the
  accessibility service and anything that needs adb-granted permissions. Everything else is shared.
  From M1 release builds are signed with a key held only in CI; debug builds keep the committed
  shared key and install as a separate app (`.debug` package suffix), so a laptop build never has
  to replace the TV's release install.
- **Keep dependencies in `gradle/libs.versions.toml`.** Bump versions in one commit on their own.
- **No secrets, no analytics, no network calls except stream playback, YouTube embeds and the LAN
  config server.**
- **Shell scripts:** `set -euo pipefail`, and never pipe into `grep -q` (an early exit fails the pipe
  under pipefail). Capture output, then search it, as `tv-admin.sh` does.
- Commit messages: imperative summary line under 72 chars, blank line, why-not-what body when
  the diff does not explain itself.

## Build and verify

```bash
./gradlew testDebugUnitTest      # JVM unit tests (fast, run before every commit)
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew lintDebug              # Android lint
bash tools/test/tv-admin.test.sh # admin script against a fake adb
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
