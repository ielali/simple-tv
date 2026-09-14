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

## Data flow for a channel change

1. Viewer presses `1`, `2`. `MainActivity` forwards the first DOWN of each key to `TvViewModel.onKeyDown`.
2. `ChannelTuner` accumulates `"12"` and exposes it through `pendingDigits`; the banner shows the digits.
3. After 1.5 s without another digit (or OK, or a third digit) the tuner emits `12` on `tuneRequests`.
4. `TvViewModel` resolves the number with `ChannelNavigator.resolve` against the sorted list.
5. `switchTo` stores the number as "last watched", starts `PlaybackEngine.play` for STREAM channels,
   or stops the engine and lets Compose swap in the YouTube WebView.
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

## Testing strategy

Pure logic (tuner, navigator, parser, YouTube parsing) is JVM-tested. Android layers are verified
on device. CI runs unit tests, assembles the debug APK and runs lint.
