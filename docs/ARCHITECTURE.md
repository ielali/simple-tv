# Architecture

## Components

```
 Remote ──key events──▶ MainActivity.dispatchKeyEvent
                              │
                              ▼
                        TvViewModel ◀──────── ChannelRepository (channels.json, StateFlow)
                         │   │   │                    ▲
        ChannelTuner ◀───┘   │   └──▶ PlaybackEngine (ExoPlayer)        │ PUT /api/channels
        (digits, timeout)    │                                          │
                             ▼                                   ConfigServer (Ktor, :8080)
                        TvUiState ──▶ PlayerScreen (Compose)             ▲
                                       ├─ PlayerView (STREAM)            │ HTTP
                                       ├─ WebView (YOUTUBE embed)   phone / laptop browser
                                       ├─ ChannelBanner                 index.html
                                       └─ Settings / NoChannels overlays (URL + QR code)
```

## TvController

`TvController` (created by `SimpleTvApp`) owns the TV: current channel, digit entry, banner, last
channel, and whether a YouTube channel plays in the YouTube app. It is app-scoped and Android-free,
so it keeps working while YouTube is in front and is unit tested with virtual time. Two adapters drive
it: `TvViewModel` (Simple TV's screen, which also owns the ExoPlayer and the settings overlay) and the
home guard (keys over YouTube). `ExternalPlayer` launches YouTube or brings Simple TV forward from
the application context. `MainActivity.onStart/onStop` report screen visibility, which is how the
controller knows YouTube is in front.

## Data flow for a channel change

1. Viewer presses `1`, `2`. On Simple TV's screen `MainActivity` forwards the first DOWN of each key to
   `TvViewModel.onKeyDown`, which passes channel keys to `TvController.onRemoteKey`. While YouTube is in
   front the home guard sends the same keys straight to the controller.
2. `ChannelTuner` accumulates `"12"` and exposes it through `pendingDigits`; the banner shows the digits.
3. After 1.5 s without another digit (or OK, or a third digit) the tuner emits `12` on `tuneRequests`.
4. `TvController` resolves the number with `ChannelNavigator.resolve` against the sorted list.
5. `switchTo` stores the number as "last watched". STREAM channels play in `TvViewModel`'s engine (Simple
   TV is brought forward if YouTube was in front). YouTube channels use the WebView, or in YouTube-app
   mode the YouTube app is opened directly, with no stop on Simple TV's screen in between.
6. The banner shows number and name for 3.5 s.

## Persistence

One JSON file, `files/channels.json`, holding a `ChannelList`. The repository normalises on write:
unique numbers, non-blank names, generated ids. The list is small; whole-file rewrite is deliberate.

## Providers

`Provider` describes an IPTV subscription. `Provider.playlistUrl()` builds the Xtream `get.php`
link or returns the M3U link; `authHeaders()` yields Basic auth for M3U accounts. `ProviderImport.merge`
is pure: it appends channels not yet present for that provider, numbering after the highest existing
number, inheriting the provider icon and auth headers. `ConfigServer` fetches the playlist on the TV so
credentials never pass through the browser.

## Config server

Ktor with the CIO engine, bound to all interfaces on port 8080. Serves `assets/config/index.html`
and a JSON API. No authentication yet (backlog item). Runs for the app's lifetime from `SimpleTvApp`.

## Playback

`PlaybackEngine` wraps a single `ExoPlayer`: short start buffer for quick zapping, 6 s live offset,
per-channel HTTP headers for providers that need a User-Agent or Referer, and automatic re-prepare on
network or behind-live-window errors so a dropped stream recovers without user action.

## YouTube

`YouTubeEmbed` turns a video id, watch/live URL or channel id into an embed URL for the IFrame player
and wraps it in a full-bleed HTML page loaded into a WebView with autoplay allowed. Channel-id sources
use `embed/live_stream?channel=UC…`, which always plays that channel's current live broadcast.

## YouTube playback modes

`AppSettings.youtubePlayback` selects between the embedded player (default) and a hand-off to the
YouTube app. In `YOUTUBE_APP` mode `TvViewModel.switchTo` emits the channel on `openExternal`;
`MainActivity` builds a watch or channel-live URL with `YouTubeEmbed.watchUrl` and tries the TV
YouTube package, then the mobile package, then any URL handler. If none exists it reports back and
the UI falls back to the embed for that channel. See ADR-007 for why the device account cannot be
used by the embedded player.

## Home guard (soft kiosk)

`HomeGuardService` is an accessibility service with key filtering. For each key it asks
`HomeGuardPolicy.onKey`: HOME and TV bring `MainActivity` forward; assistant, colour and app-launch keys
(plus caregiver-blocked codes) are swallowed; everything Simple TV, YouTube or the system needs passes.
For window changes of the stock Google TV home and sponsored apps it asks `onWindow`, which brings
Simple TV back unless `GuardState.allowedExternalPackages` says Simple TV opened that app itself
(set before the YouTube hand-off, cleared in `MainActivity.onResume`). Launches are debounced.

The guard reads `AppSettings` (`guardEnabled`, `guardPausedUntil`, `guardBlockedKeys`) on every event,
so config-page changes apply immediately. `GuardSetup` edits the accessibility secure settings when the
app holds WRITE_SECURE_SETTINGS (granted once by `tools/tv-admin.sh guard-on`) and re-enables the
service at app start and boot. An enabled accessibility service also exempts the app from Android's
background-activity-start limits, which is what lets `BootReceiver` open the TV.

While the current channel plays in the YouTube app (`TvController.isExternalFront`), keys go through
`HomeGuardPolicy.onKeyDuringExternal`: digits, channel keys and up/down are routed to the controller;
INFO/GUIDE/HOME show the banner; BACK and MENU do nothing so the viewer cannot wander into YouTube's
browse screens; OK (unless digits are pending), left/right, media and volume keys still reach YouTube.
`BannerOverlay` draws the channel banner over YouTube as a TYPE_ACCESSIBILITY_OVERLAY window.

Both the guard and `MainActivity` write key presses to `KeyLog`, served at `/api/keys` for the
config page's remote audit.

## Testing strategy

Pure logic (tuner, navigator, parser, YouTube parsing, guard policy, settings merge) is JVM-tested;
`GuardKeysSdkTest` checks mirrored key codes against the SDK, and `tools/test/tv-admin.test.sh` runs
the admin script against a fake adb. Android layers are verified
on device. CI runs unit tests, assembles the debug APK and runs lint.
