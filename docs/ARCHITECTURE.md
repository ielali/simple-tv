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

`Provider` describes an IPTV subscription. `ProviderLoader` downloads its channels: for Xtream
accounts the `player_api.php` interface first (account check, categories, live streams via
`XtreamApi`), then the `get.php` playlist; for M3U providers the link itself with Basic auth from
`authHeaders()`. Each step retries with a player User-Agent after a refusal, and the accepted one is
stored on the channels for playback. Channels are added from the cached catalog (see below):
`/api/catalog/{pid}/categories` and `/items` browse it, `/api/channels/catalog` adds the ticked
channels or whole categories. `ProviderImport.merge` is pure: it appends channels not yet present for that provider, numbering after the highest existing
number, inheriting the provider icon and auth headers. `ConfigServer` fetches the playlist on the TV so
credentials never pass through the browser.

## Config server

Ktor with the CIO engine, bound to all interfaces on port 8080. Serves `assets/config/index.html`
(one self-contained page built from docs/design/handoff) and a JSON API. Runs for the app's lifetime
from `SimpleTvApp`. `ConfigServer` holds the shell, PIN sign-in (`PinAuth`), status, settings, the
home guard and the key log; `ContentRoutes` holds lineup, subscriptions, catalog, film channels,
YouTube preview, now playing and Play on TV. Responses are built from `ApiViews`, which never carry a
stream address or a password (ADR-015).

API outline: `GET /api/auth`, `POST /api/auth/login|logout`, `PUT /api/auth/pin`; `GET /api/status`,
`GET /api/qr`; `GET|PUT /api/settings`; `GET|PUT /api/channels`, `POST /api/channels/youtube|link|
catalog|film`, `GET /api/channels/{id}/queue`, `POST /api/channels/{id}/restart`,
`POST /api/films/queue-preview`; `GET|POST /api/providers`, `POST /api/providers/test|refresh-all`,
`POST /api/providers/{id}/refresh`, `DELETE /api/providers/{id}`; `GET /api/catalog/{pid}/categories|
items`, `GET /api/catalog/{pid}/series/{sid}`; `POST /api/youtube/preview`, `PUT /api/youtube/key`;
`GET /api/nowplaying`, `POST /api/play`; plus the guard, key log and debug routes.

## Catalogs, film and series channels

`CatalogService` keeps each subscription's catalog in `CatalogRepository` (files in `files/catalog/`)
and refreshes it daily at `catalogRefreshTime` and on demand (ADR-016). `CatalogQuery` pages and
searches it for the page; `LineupFlags` and `LineupRepair` keep the lineup in step. Film and series
channels carry a `PlaylistSpec`; `QueuePlayback` turns it into the item to play and its address,
`TvViewModel` plays it with a start position, moves on at the end and saves progress.

Film details and ratings (ADR-018): `MetadataService` asks the provider's details call, then TMDB
when the household has a key, caches the result per subscription, and checks every film of a film
channel with rating rules in the background. `RatingRules` decides what a channel may play;
`QueuePlayback` filters the queue with it. Routes: `GET /api/details/{pid}/{type}/{id}`,
`GET /api/ratings`, `PUT /api/tmdb/key`. Queue lists are paged: `GET /api/channels/{id}/queue?offset=
&limit=&fromResume=1` (series lists start where the channel stopped) and `offset`/`limit` in the
queue-preview body; the page loads the next page as the end of a list scrolls into view.

## Playback

`PlaybackEngine` wraps a single `ExoPlayer`: short start buffer for quick zapping, 6 s live offset,
per-channel HTTP headers (User-Agent applied through the data source factory), and retries for any
playback error: at once for behind-live-window, otherwise 3 s doubling to 30 s, for as long as the
channel stays on. A pure `TuneTracker` turns player events into what the viewer sees: TUNING (card
with number, name and spinner until the first picture), REBUFFERING (small spinner over the frozen
frame), ERROR ("No signal. Trying again…" until the picture plays) and PLAYING.

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
`BootReceiver` also runs after Simple TV is updated (`MY_PACKAGE_REPLACED`): installing stops the app
and the guard. When the guard starts, it checks once what is in front and leaves the Google TV home
if that is where the update or a crash left the TV.
Going into standby, Google TV puts its own home in front; starting an app then switches the TV back
on. So the guard never brings Simple TV forward while the screen is off (or for 10 s after it goes
off), and checks what is in front again when it comes back on. `ExternalPlayer` opens nothing in
standby either (a night-time catalog refresh could otherwise reopen YouTube).

While the current channel plays in the YouTube app (`TvController.isExternalFront`), keys go through
`HomeGuardPolicy.onKeyDuringExternal`: digits, channel keys and up/down are routed to the controller;
INFO/HOME show the banner; BACK and MENU do nothing so the viewer cannot wander into YouTube's
browse screens; OK (unless digits are pending), left/right, media and volume keys still reach YouTube.
`BannerOverlay` draws the channel banner over YouTube as a TYPE_ACCESSIBILITY_OVERLAY window.

Buttons given a job in settings (`listButtons`, `previousButtons`; `ButtonRoles`) are decided first,
on every screen: the guard returns `CHANNEL_LIST` or `PREVIOUS_CHANNEL` and calls the controller.
The channel list is app-scoped state in `TvController` (`listIndex`), so opening it over YouTube
brings Simple TV forward, `onAppVisible` holds YouTube back while it is open, and closing it without
choosing sends a YouTube-app channel back to YouTube (ADR-022). With the guard off, `MainActivity`
reaches the same controller calls through `TvViewModel.onKeyDown`.

Both the guard and `MainActivity` write key presses to `KeyLog`, served at `/api/keys` for the
config page's remote audit.

## Testing strategy

Pure logic (tuner, navigator, parser, YouTube parsing, guard policy, settings merge) is JVM-tested;
`GuardKeysSdkTest` checks mirrored key codes against the SDK, and `tools/test/tv-admin.test.sh` runs
the admin script against a fake adb. Android layers are verified
on device. CI runs unit tests, assembles the debug APK and runs lint.

## TV guide and YouTube suggestions

`GuideService` (ADR-020) keeps now/next for live channels: Xtream `get_short_epg` per channel, and
XMLTV guides (a playlist's own, free presets, caregiver links) streamed through `XmltvReader` and
trimmed to the lineup. `GuideMatch` pairs lineup channels with guide channels; manual choices are
stored in `files/guide/overrides.json`. Routes: `GET /api/guide/now`, `GET /api/guide`,
`PUT /api/guide/sources`, `POST /api/guide/refresh`, `GET /api/guide/channels?q=`,
`GET /api/guide/match/{id}`, `PUT /api/guide/match`.

`LogoService` (ADR-027) finds logos for live channels without one: the matched XMLTV channel's icon
(`GuideService.iconFor`), else a weekly index of iptv-org's public channel database (`ChannelLogos`),
else Wikidata (`WikiLogos`); YouTube channels get their channel picture (`YouTubeLookup.pictures`).
Results live in `files/logos/found.json` and reach the page as `foundLogo`;
`PUT /api/channels/{id}/found-logo` turns one off or looks again.

`YouTubeSuggestions` (ADR-021) signs the TV in with the device flow through `YouTubeAccount` and caches
subscriptions and live-now. Routes: `GET /api/youtube/account`, `PUT /api/youtube/client`,
`POST /api/youtube/connect|disconnect`, `GET /api/youtube/suggestions`.

