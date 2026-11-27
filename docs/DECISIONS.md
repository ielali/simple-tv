# Decisions

Short architecture decision records. Newest at the bottom.

The app was called Simple TV until ADR-026 renamed it Telly. Earlier records keep the old name as
written at the time; the package, code names and release file still use it.

## ADR-001 Android TV, native Kotlin + Compose

Context: Target is a set-top box driven by an IR/Bluetooth remote; playback must be hardware-decoded
and start fast. Decision: native Android TV app in Kotlin with Jetpack Compose (no Leanback
fragments, no web wrapper). Consequences: full control of key handling and ExoPlayer; Kotlin rather
than Java is the cost for a Java-first team, offset by Compose and coroutine ergonomics.

## ADR-002 Media3 ExoPlayer for IPTV streams

Context: HLS, DASH and MPEG-TS from IPTV providers, some needing custom headers. Decision: Media3
ExoPlayer with `DefaultMediaSourceFactory` and a `DefaultHttpDataSource` whose headers are set per
channel. Consequences: no VLC dependency; codec coverage depends on the box's hardware decoders.

## ADR-003 YouTube via the official embedded player only

Context: The YouTube Android Player API is discontinued; stream extraction violates the terms.
Decision: play YouTube channels in a WebView using the IFrame player with `autoplay=1&controls=0`.
Fallback to evaluate: launching the YouTube TV app by intent. Consequences: some channels refuse
embedding; playback is heavier than native; app remains publishable.

## ADR-004 Configuration through a LAN web page served by the app

Context: Elderly viewers cannot configure anything; caregivers should not type on a D-pad.
Decision: embedded Ktor (CIO) server on port 8080 serving a single vanilla-JS page and a JSON API.
Consequences: zero hosting, works offline on the home network, needs a PIN before wider distribution.

## ADR-005 Missing channel numbers land on the nearest real channel

Context: Old TVs showed static on unused numbers, which confuses and frightens. Decision:
`ChannelNavigator.resolve` picks the exact match, else the next higher number, else wraps to the
lowest. Consequences: the viewer always ends up watching something; the banner shows what was chosen.

## ADR-006 Whole-list JSON file instead of a database

Context: Tens of channels, edited by one caregiver, replaced wholesale from the web UI.
Decision: one JSON file rewritten on each change, exposed as a StateFlow. Consequences: trivial
import/export and API contract; revisit only if per-channel history or EPG storage is added.

## ADR-007 Google account access for YouTube

Context: Viewers may hold YouTube Premium (no ads) or follow channels that block anonymous or
embedded playback, and caregivers asked whether the TV's signed-in Google account can be used.

Facts that constrain the options:
- The embedded IFrame player runs in the app's own WebView with its own cookie jar. It cannot see
  the Android account on the box, and an OAuth token cannot be handed to it.
- Google refuses sign-in pages inside Android WebViews ("this browser or app may not be secure").
  Working around that means spoofing the user agent, which breaks Google's policies; not an option.
- The YouTube app on the box IS signed in with the device account, and it accepts `ACTION_VIEW`
  intents for watch and channel-live URLs.
- The device account can grant an OAuth token for the **YouTube Data API** (subscriptions, live
  broadcasts of a channel). That helps the caregiver pick channels; it does not change playback.

Decision: add a per-install setting `youtubePlayback` with two modes. `EMBEDDED` (default) keeps the
TV illusion and plays anonymously. `YOUTUBE_APP` hands the channel to the YouTube app, which uses the
signed-in account. The Data API integration is a separate backlog item because it needs a Google
Cloud project and OAuth client owned by the operator.

Consequences: with `YOUTUBE_APP` the remote drives YouTube while it is in front and the viewer must
press BACK to return; that trade-off is stated in the config UI. No credentials are stored by the
app in either mode.

## ADR-008 IPTV providers as first-class records

Context: Subscriptions are almost always Xtream Codes accounts (server, username, password). Asking
a caregiver to assemble a `get.php` URL by hand is error-prone, and channel lists need re-importing
when the provider changes them.

Decision: store providers separately from channels (`providers.json`) with name, icon, kind
(XTREAM or M3U), URL, username and password. Import fetches the playlist server-side on the TV and
merges new channels, tagging each with `providerId`. The config API never returns passwords; a blank
password on write keeps the stored one. M3U providers with credentials use HTTP Basic auth for the
playlist and the streams; Xtream credentials travel in the URL as the protocol requires.

Consequences: removing a provider removes its channels in one step; re-import is additive only
(full sync is a backlog item); the LAN config page still needs a PIN before the password-holding
endpoints are safe on shared networks.

## ADR-009 Soft kiosk through an accessibility service; never disable the stock home

Context: The Kogan dongle is already set up with a Google account, which the YouTube hand-off needs.
Device-owner kiosk mode requires removing every account or a factory reset. The open-source project
tv-vibehack (MIT, github.com/toomramcharan/tv-vibehack), built for the same audience, documents that
disabling the stock Google TV home on Android 14 can trap the device in RescueParty recovery, and uses
an accessibility service to cover the home screen instead.

Decision: adopt that approach, rewritten in Kotlin with these changes:
- Key decisions live in a pure `HomeGuardPolicy` with JVM tests; key codes are mirrored in `GuardKeys`
  and checked against the SDK in CI.
- HOME is consumed on DOWN, repeat and UP (the system acts on UP).
- Sponsored apps are bounced back only when Simple TV did not open them, so the YouTube hand-off works.
- The service list is edited, not overwritten, so other accessibility services survive.
- The guard can be paused (15 min) or switched off from the config page, so a caregiver can always
  reach Google TV settings, for example to fix Wi-Fi, without a computer.
- A key log and per-key block list on the config page replace adb logcat for the remote audit.
- `tools/tv-admin.sh` refuses to disable protected packages and re-enables the stock home if it finds
  it disabled.
Not adopted: their launcher UI, HDMI input switching (Realtek TV sets only), targetSdk 29, and
direct-boot awareness (it would start Simple TV before credential storage unlocks).

Consequences: works on a signed-in device and is fully reversible (`tv-admin.sh stock`). It is a
soft lock: a determined user can still reach Settings through the guard's pause. Device-owner mode
remains the option for a hard lock on a fresh device, under the same never-disable rule.

## ADR-010 Remote control continues inside the YouTube app

Context: First device test on the Kogan. YouTube-app mode avoids ads with the signed-in Premium
account, but once YouTube was in front the remote drove YouTube, so changing channel meant BACK to
Simple TV's holding screen and then a number. IPTV channels were not reachable from YouTube at all.

Decision: move channel state out of the activity into an app-scoped, Android-free `TvController`. The
home guard, which already sees every key, routes channel keys to it while YouTube is in front, and
draws the channel banner over YouTube with an accessibility overlay window (no extra permission).
YouTube-to-YouTube changes open the next video in the YouTube app directly; YouTube-to-stream brings
Simple TV forward. BACK and MENU are swallowed over YouTube so the viewer stays in the video. The
YouTube app stays allowed by the guard for as long as the current channel is a YouTube-app channel.

Consequences: YouTube-app mode now depends on the home guard; with the guard off or paused the remote
drives YouTube as before. Whether the YouTube TV app replaces the playing video on a new VIEW intent
must be confirmed on the device; if it does not, ExternalPlayer can add FLAG_ACTIVITY_CLEAR_TASK.

## ADR-011 Provider import: Xtream API first, plain-language errors, pick categories

Context: First real subscription on the Kogan. Import failed with an error that printed the playlist
URL, password included, on the config page. The same account works in IPTVX. Many providers disable
the `get.php` playlist download or refuse unknown app names, while IPTV apps use the Xtream
`player_api.php`. Real subscriptions also carry thousands of channels, unusable with up/down only.

Decision:
- `ProviderLoader` tries `player_api.php` first (it also reports wrong logins, expiry and bans), then
  the M3U playlist. Each step is tried with Simple TV's User-Agent, then with a widely recognised
  player one (VLC); the accepted one is stored on the imported channels and used for playback.
- Failures become caregiver-readable messages from `FetchErrors` and never contain a URL.
- Import is two steps: preview (download once, show categories with counts and the account expiry),
  then add only the ticked categories. The download is cached for ten minutes between the two.
- Xtream streams use HLS (.m3u8) when the account allows it, else MPEG-TS.

Consequences: works with providers that block playlist downloads or unknown apps. The VLC
User-Agent is a compatibility fallback, used only after a refusal. EPG from the API is still to do.

## ADR-012 TV screen in English and Arabic, chosen on the config page

Context: The household needs Arabic as well as English. The Kogan's system language may be set for
whoever else uses the TV, so Simple TV's language should be chosen independently.

Decision: Android string resources with a `values-ar` translation, and an app setting
(`AppSettings.language`: device language, English or Arabic) chosen on the config page.
`MainActivity.attachBaseContext` wraps its context in that locale, which also turns on right-to-left
layout, and the activity recreates when the setting changes (the playing channel survives). The
banner drawn over YouTube follows the same setting. Channel names are shown as the provider sends
them. Remote digits and channel numbers stay Western (0-9), matching the remote's keys.

Consequences: works on every Android version without the per-app-language API. The config page
itself is still English; translating it is on the backlog. The Arabic strings should be reviewed by
a native speaker.

## ADR-013 Choose the first account on YouTube's "Who's watching?" screen

Context: On the Kogan, YouTube channels sometimes stopped on YouTube's account picker. Likely
cause: on a 2 GB device Android closes the YouTube app while IPTV plays, and a cold start asks
"Who's watching?". The household's main account is the first entry.

Decision: the home guard reads YouTube's screen (canRetrieveWindowContent) only within 30 s after
Simple TV opened YouTube for a channel, and only while that hand-off is active. A pure
`AccountPickerDetector` recognises the picker by its own words (English and Arabic titles, or
"Add account" plus "Guest") and picks the first pressable entry that is not an action such as Add
account, Guest, Back or Search. If no entry can be pressed it sends OK (Android 13+), which chooses
the focused first account. `PickerThrottle` allows at most three tries a minute. What the guard saw
and did is served at `/api/debug/youtube-screen`, and the config page shows the last choice.

Consequences: depends on YouTube exposing its screen to accessibility services and on its wording.
If YouTube changes either, the guard does nothing rather than press the wrong thing. The debug
endpoint shows what it saw so the detector can be tuned. Reading window content is a broader
accessibility capability; it is used only for this, inside the 30-second window.

### ADR-013 addendum: recognise the account screen by its look

Device result: YouTube on TV (its own rendering engine) exposes 14 empty placeholder nodes and no
text, so the word-based detector can never fire there. A forced cold start also did not show the
picker; YouTube most likely asks after a period of inactivity, so it cannot be summoned on demand.

Decision: the caregiver teaches the screen once from the config page while it is showing. The guard
stores only a 48x27 greyscale thumbnail (`ScreenFingerprint`; too small to read names). For 30 s after
each hand-off it takes a screenshot every 1.5 s (`canTakeScreenshot`, Android 11+), compares it
(mean difference <= 12 and correlation >= 0.85, blank frames never match) and presses OK (Android 13+)
on a match, which chooses the highlighted first account. The word-based detector stays for other
YouTube builds. Screenshots are never stored or sent; only the taught thumbnail is kept on the TV.

## ADR-014 Optional YouTube Data API key, stored on the TV

Context: The redesigned "Add channel" flow (docs/design/claude-design-prompt.md) should accept YouTube
`@handle` links. Resolving a handle to a channel ID needs the YouTube Data API; scraping the handle
page would break YouTube's terms. Claude Design asked whether a key is acceptable and where it lives.

Decision:
- The key is optional. Without it, video and live links still get a preview from YouTube's public
  oEmbed endpoint (title, thumbnail, channel name), and channel IDs / `/channel/UC...` links work.
  With it, `@handle` links resolve (`channels.list?forHandle=`, 1 quota unit) with name and avatar,
  and a "Live now" badge is checked once when adding (`search.list eventType=live`, 100 units).
- Each household supplies its own key from a free Google Cloud project, restricted to the YouTube
  Data API v3. No key is built into the APK, where it could be extracted and its quota shared.
- The key is stored only on the TV in app-private storage, like provider passwords. The config API
  never returns it (`hasYoutubeKey` instead), it is never logged or put in error messages, and all
  YouTube API calls are made by the TV, so the key never reaches the phone.
- UI: Settings > YouTube > Advanced, "YouTube API key (optional)"; after saving, "Key saved" with
  Replace and Remove. Without a key, pasting an @handle shows how to add one or to paste the
  channel link instead. Errors in plain words: key not accepted, daily limit reached, handle not found.

Consequences: @handle support costs the caregiver a one-time Google Cloud setup; everything else in
the YouTube path works with no key. Until HTTPS exists (backlog), entering the key crosses the LAN
once in plain text, the same as provider passwords.

## ADR-015 Caregiver API that never returns secrets, with an optional PIN

Updated by ADR-033: from M3 the TV no longer shows the PIN (a one-time code replaces it), and from
M7 the PIN is required and cannot be removed.

Context: The redesigned caregiver page (docs/design/handoff) is served over plain HTTP on the home
network. IPTV stream addresses and M3U links embed the account password, and the old API returned
whole channel lists, so any device on the Wi-Fi could read them.

Decision:
- The page only ever sees views built in `config/ApiViews.kt`: lineup rows without stream addresses
  or headers (YouTube sources stay, they are not secret), subscriptions with `hasUrl`/`hasPassword`
  instead of the address or password, catalog items without addresses. New channels arrive only
  through add endpoints; `PUT /api/channels` takes ids, numbers and names, so the page cannot inject
  or read an address. A blank address or password on save keeps the stored one.
- Optional PIN (`config/PinAuth.kt`): 4 to 6 digits, 5 wrong tries lock sign-in for 5 minutes, random
  session tokens kept in memory (signed out when the TV restarts) and sent as `Authorization: Bearer`.
  With a PIN set every `/api/*` route except `GET /api/auth` and `POST /api/auth/login` needs a token.
  The PIN shows on the TV's own settings overlay (MENU), so whoever is in the room can sign in.
- Error messages are plain words and never contain an address.

Consequences: The PIN guards against casual access only; without HTTPS (backlog) it crosses the LAN
in plain text, as do passwords entered once. The page stores the token in session storage and the
appearance choice in local storage; nothing else is kept in the browser.

## ADR-016 Cached catalogs and film or series channels

Context: Caregivers want to pick channels from categories without waiting for a 10,000-channel
download each time, and to offer films and series as numbered channels without a library to browse.

Decision:
- Each subscription's catalog (live, films, series) is cached on the TV (`data/CatalogRepository`,
  one JSON file per provider, written atomically) and refreshed by `CatalogService` once a day at
  `catalogRefreshTime` (default 21:00, catching up after the TV was off) and on demand. A failed
  refresh keeps the last good copy and is recorded in a 10-entry history. A good refresh gives lineup
  channels fresh addresses by the provider's stream id and flags channels the provider dropped
  ("missing"); channels that fail to play for 20 s are flagged "failed" until they play.
- Film and series channels (`SourceType.FILMS`/`SERIES`) store a `PlaylistSpec`, not addresses. The
  queue is rebuilt from the cached catalog (`PlaylistQueue`), shuffled with a stored seed, with
  caregiver-pinned first items and left-out items. `QueuePlayback` builds the film or episode
  address from the provider's credentials at play time, moves on when an item ends and saves the
  position every 15 s and on channel change, so a series resumes at the right episode.
- "Play on TV now" plays a lineup channel, or a preview that is not in the lineup (number 0, never
  stored as the last channel).

Consequences: Very large providers mean a large catalog file and a slow first download (watch this
on the Kogan, backlog item 0). Film and series channels need the Xtream API; plain M3U providers
offer live channels only.

## ADR-017 Subtitles and soundtrack per film or series channel

Context: Films and episodes from Xtream providers often carry several subtitle and audio tracks
(mostly in .mkv files). Caregivers want subtitles on without the viewer touching a menu.

Decision:
- Each film or series channel stores `PlaylistSpec.subtitles` (off, "any", or a language) and
  `PlaylistSpec.audio` (as it comes, or a language), set on the config page.
- When the file's tracks are known, `PlaybackEngine` applies the choice made by the pure
  `player/TrackPicker`: full subtitles beat forced ones, the file's default breaks ties, and language
  tags are normalised ("ara", "ar-SA" -> "ar"). A missing language means no subtitles, or the
  soundtrack the file comes with (caregiver's choice); the TV never guesses another language.
- Subtitles are drawn large (about 1.6 times the usual size, bold white on a dark box) unless the
  TV's own caption settings are on; the file's own fonts and colours are ignored.
- The page's "On the TV now" card says when the item on screen lacks the chosen language and which
  languages it has.

Consequences: Subtitles only appear when the file carries them; separate subtitle files offered by
some providers are not used yet (backlog). Live channels are unchanged.

## ADR-018 Film details and official ratings from the provider and TMDB

Context: Caregivers want posters, trailers, descriptions and family ratings for films and series,
and film channels that only play what suits the household (grandchildren visiting, for example).

Decision:
- Details come first from the provider's own Xtream details call (`get_vod_info`,
  `get_series_info`: poster, plot, cast, genre, year, score, YouTube trailer, often a TMDB id), which
  needs no key. With the household's optional free TMDB key, TMDB adds official ratings for every
  country, better posters, trailers and cast. The provider's TMDB id is used when present; otherwise
  the film is matched by its cleaned title and year (shown as "matched by title").
- The key is stored only on the TV (SecretsStore), never returned (`hasTmdbKey`). A read access
  token travels in the Authorization header; an older v3 key has to be a query parameter, which only
  goes from the TV to TMDB over HTTPS and is redacted from logs. The page shows TMDB's required credit.
- Rating country: chosen on the page (`ratingCountry`), defaulting to the TV's own region. Each
  country's rating scale comes from TMDB's certification lists, ordered mildest to strongest.
- Options for the whole TV: show the rating in the banner (`ratingOnBanner`), never play adult titles
  (`hideAdult`, on by default). Per film channel: highest rating (`maxRating`), lowest score
  (`minScore`), and whether films without a rating or score play (`skipUnrated`).
- `MetadataService` caches details per subscription for 30 days and checks every film of a
  rule-based film channel in the background, about two titles a second, so the provider is never
  flooded. Unchecked films never play on a channel with rules, so a family channel only shows what it
  has checked; the editor shows "Checking ratings: 340 of 1,172".

Consequences: Rating rules need a TMDB key. Older and foreign films often lack a rating in a given
country, hence the per-channel choice for them. Big film channels take minutes to check the first
time. TMDB is free for non-commercial use with attribution.

Update: Film channels can also keep to production years ("From" and "To", either open, with quick
choices such as "Before 2000"). A film's year comes from the catalog, then its details, then its
name ("Heat (1995)"); films of unknown year play unless the channel leaves them out, and then their
details are looked up for a year. Hand-picked films always play. The page's film and series search
takes the same years (`Years`).

## ADR-019 Moving within film and series channels with the remote

Context: Film and series channels played their queue with no way to skip, go back or pause. The
household's remote (Kogan, Google TV style) has a D-pad with OK, Back, Home, Live TV, a channel
rocker beside the volume buttons, and app buttons; no number or media keys. Left and Right were free.

Decision:
- On a film or series channel playing on Simple TV's screen: Right plays the next film or episode,
  Left goes back to the start of this one (or to the previous one within its first 10 seconds), and
  OK pauses with a large "Paused" card and carries on. Media keys do the same where a remote has them
  (`player/QueueKeys`, pure). Up/down and the channel rocker still change channel; live and YouTube
  channels are unchanged, and pending digits still take OK first.
- The banner shows the new title after a skip. A pause saves the position and survives the screen
  going to standby; changing channel ends it.
- The config page can do the same: Start of this one, Pause / Carry on and Next on the "On the TV
  now" card (`POST /api/nowplaying/control`), and "Play this now" on any film or episode in the
  editor's queue (`POST /api/channels/{id}/play-item`); the channel then carries on from there.

Consequences: A viewer can no longer wander through the D-pad's Left and Right on these channels,
which did nothing before. Nothing is shown that needs a cursor.

## ADR-020 TV guide: the provider's guide first, free XMLTV guides as a fallback

Context: Viewers and caregivers want to know what is on now and next on live channels. Research
(Nov 2026): Xtream panels offer a per-channel guide (`get_short_epg`, titles base64-encoded) and a
full `xmltv.php` that can list tens of thousands of channels; M3U playlists may name an XMLTV guide in
their header; free XMLTV guides exist for Australia (epgshare01, epg.pw, xmltv.net) and some Arabic
channels (epgshare01), all volunteer-run with no explicit permission for apps to fetch them.

Decision:
- Xtream channels: `get_short_epg` per lineup channel when tuned and in a gentle sweep every 10
  minutes (cached until the current programme ends or 30 minutes). Epoch timestamps only; base64
  decoded defensively. The huge `xmltv.php` is not used.
- XMLTV (a playlist's own guide link, the free guides chosen in TV settings, or a link the caregiver
  adds): downloaded at most twice a day, streamed through XmlPullParser (gzip detected by its magic
  bytes) and trimmed to the lineup's channels for the next 36 hours. Default free guide follows the
  TV's region (epgshare01 Australia for AU); the caregiver can change it.
- Matching: a manual choice first, then the provider's guide id (`epg_channel_id` / `tvg-id`), then
  tidied names (case, HD/4K, country prefixes, brackets, city names, aliases like Seven/7).
  The lineup's edit panel shows the match and lets the caregiver search the guide or turn it off.
- The TV banner shows Now (time span, title, progress bar) and Next; the page shows the same on the
  "On the TV now" card and a "Now:" line on each live channel.

Consequences: Guides have gaps and wrong ids; free guides can disappear or block, and none grants
redistribution rights, so they are a household-level fallback fetched sparingly. The preset guide
links could not be checked from the build sandbox and need confirming on the TV (backlog item 0).

Update (ADR-022 release): Xtream providers' whole guide (`xmltv.php`) is now a guide source of its
own, read like the other XMLTV guides and matched by the streams' `epg_channel_id`, for channels
whose short guide is empty. Channels loaded through the provider's playlist get their stream id
from the end of their address for the short guide.

## ADR-021 YouTube suggestions from subscriptions only

Context: The caregiver asked for YouTube suggestions based on watch history and the home page.
Google returns empty watch-history and watch-later lists to apps since 2016, offers no API for home
recommendations, retired "related videos" search in 2023, and scraping would break YouTube's terms
and this project's rule 6.

Decision:
- Suggestions come from the household's subscriptions, live ones first. The TV signs in with
  Google's device flow ("go to google.com/device and enter the code") using the household's own
  OAuth client of the type "TVs and Limited Input devices", scope `youtube.readonly`. The consent
  screen is set to "In production" so the sign-in does not expire weekly; Google shows an
  "unverified app" warning once.
- The refresh token, client secret and client id stay in SecretsStore; only the client id (shown
  on Google's own sign-in page) is returned. Credentials travel in POST bodies, never addresses.
- Subscriptions are refreshed daily; "live now" at most every 30 minutes for up to 100 channels,
  via each channel's uploads playlist and a batched `videos.list` (1 quota unit each), never
  `search.list`. Cached YouTube data is dropped after 30 days and on disconnect (token revoked).
- No watch-history import (declined by the caregiver).

Update: Home shows "Live now on YouTube": up to four subscriptions that are live, with Play on TV
(the lineup channel when it is in the lineup) and Add. Hidden when the TV isn't signed in or nothing
is live; the page asks at most once a minute, and the TV checks live state at most every 30 minutes.

Consequences: Suggestions only cover channels the household already follows. Whether every live
stream shows in its channel's uploads playlist is unconfirmed; check on real channels.

## ADR-022 A channel list on the TV, opened by the Live TV button

Context: The caregiver asked for a way to browse the channels on the TV for quick access. Rule 1
kept the viewer away from menus; typing numbers and channel up/down were the only ways to find a
channel, which is hard once a lineup has dozens of channels.

Decision:
- One channel list, the kind old TVs had: every lineup channel by number and name, in large text
  (48sp rows, 96sp number for the highlighted channel), with what is on it now and next from the
  TV guide. Up and Down move one channel and wrap; channel up/down and Left/Right move a page; OK
  watches; BACK closes; a digit closes it and starts typing a number. It closes by itself after
  30 seconds untouched. It is the only list the viewer can reach, and it has no settings in it.
- The blue "Live TV" button opens and closes it. Remotes send that button as KEYCODE_TV (170) or
  KEYCODE_GUIDE (172), so both do it by default. The home guard handles these buttons before HOME,
  so the list opens from YouTube or any other screen and brings Simple TV forward. HOME keeps its
  job and can never be given another.
- A lone 0 goes back to the previous channel (there is no channel 0); with no previous channel it
  lands on the first channel as before.
- On the config page's Remote buttons card the caregiver can give any spare button one job: open
  the channel list, previous channel, or nothing (blocked). Buttons with a fixed job (digits,
  arrows, OK, BACK, volume, power, Home, Menu, media keys) cannot take one (`ButtonRoles`).
- A clock shows beside the banner and in the list header, in the TV's 12- or 24-hour style.

Update: The Kogan remote's Live TV button sends KEYCODE_FEATURED_APP_1 (297), so that code opens
the list by default too. OK shows the banner again (opening the list with OK paused YouTube-app
channels, because OK reaches YouTube there).

Update: The list is laid out like a satellite receiver's. Channels on one side; on the other a
preview window, 16:9, with the highlighted channel's now and next under it. Resting on a live
channel for 0.8 s plays it in the window, using the one player: most IPTV accounts allow a single
stream, so a second player would cut the main picture off. The video view moves into the window
rather than being rebuilt. OK on a previewed channel carries on without tuning again; BACK puts the
channel that was on back (a film resumes where it stopped). YouTube, film and series channels show
their logo (Coil) and kind instead of a picture; the embedded YouTube player stops while the list
is open. Left untouched for a minute, the list closes, and when it rests on another live channel
that channel stays on, since the viewer has been watching it.
The video view is placed at the window's absolute screen position (left/top), never a start/end
offset: in Arabic the screen runs right to left and a mirrored offset put the picture over the list.

Update: At the caregiver's request, after seeing it on the TV, the list uses smaller type than rule
5b so that useful text fits: rows 40sp (about six channels), the highlighted channel's full name
scrolls; the window's guide shows the times at 24sp, the programme's name at 30sp on up to two lines,
next at 24sp and the description at 22sp in the room left. The banner keeps rule 5b's sizes.
Provider text is tidied (`TextClean`): HTML entities in names and guide titles are decoded, and guide
titles that are only a path or link ("home/1/1...") are dropped. The tuning screen shows the
channel's logo.

Consequences: Rule 1 now allows this one list besides the video and the banner. Which code the
Kogan remote's Live TV button sends is unconfirmed; the key log shows it. While a YouTube-app
channel is on, the list shows over Simple TV and YouTube reopens when it closes.

## ADR-023 YouTube playlist channels play in the embedded player

Context: The caregiver wants channels that play a public YouTube playlist. A playlist link used to
play only the video in it.

Decision:
- A playlist link (`watch?v=…&list=…`, `playlist?list=…`, or a playlist id) makes a YouTube channel
  that plays the list in order, then round again. The caregiver chooses to start at the linked
  video or the beginning, and in order or shuffled; the choices live in the stored link
  (`&shuffle=1`). Only real playlists (PL, UU, FL, albums); YouTube Mixes (`RD…`) stay single videos.
- Playlist channels always use YouTube's official IFrame Player API inside Simple TV, even when
  other YouTube channels open in the YouTube app. Only there can the TV move through the list and
  know what plays: Right/Left next or previous video (Left restarts in the first 10 s, as on series
  channels), OK pauses, and the video's title shows in the banner and the channel list.
- The page reports the playing video through a `SimpleTv` JavaScript bridge that only receives
  (index, video id, title, seconds, state). Where the channel stopped is kept per channel
  (`YouTubeProgress`) and the page resumes there, finding the video again if the list has changed.
- Videos whose owner blocks embedding are skipped. Playback is signed out: public playlists only,
  and YouTube may show ads. Rule 6 holds: no stream extraction, nothing but the official player.

Consequences: The household's YouTube account and Premium don't apply to playlist channels. The
WebView is now destroyed when the channel or the list replaces it, which also stops stray sound.

## ADR-024 Changes after two outside reviews

Context: Two independent reviews of the TV and the config page (November 2026) agreed on most
points. The household's remote has no number keys, no ★ and no media keys: channel up/down, the
arrows, OK, Back and the blue Live TV button are the viewer's whole remote.

Decision (TV):
- The channel list never changes channel by itself: left alone for a minute, or closed with Back,
  the channel that was on comes back; only OK keeps another. The preview starts after the
  highlight has rested 1.5 s. The list's guide is at 32sp (times, the programme on up to two lines,
  next); the description is gone; channel names stay at 40sp.
- "No signal" adds "Press channel up for another channel." Channel up/down steps over a channel
  that failed in the last 10 minutes (never when every other channel would be skipped); after 10
  minutes it gets another try.
- The banner stays 6 s. Film, series and playlist channels say once a day "Right: next · OK:
  pause"; after Right, "Skipped · Left goes back", and Left within 5 s brings back what was skipped,
  where it was. A film channel with nothing to play says "Checking the films…" (or that there is
  nothing yet) and looks again every minute.
- A clock in the top corner while watching, switchable on the page.
- YouTube's embedded-player rules forbid drawing over the player: on embedded YouTube channels the
  banner and the paused line get room beside the player instead (the player shrinks while they
  show), the settings card and the channel list stop the player, and the corner clock shows only
  with the banner. Playlist videos are skipped only for "can't play here" errors (removed,
  private, embedding off), never for ads; after five in a row, or any other error, the TV says
  "YouTube isn't playing. Press channel up for another channel."
- Holding Back for 5 seconds pauses the home guard for 15 minutes and opens Android's Wi-Fi
  settings, so the caregiver can fix the network without the page; the settings card says so.
  While the guard is paused the TV says "Simple TV is paused"; when the pause ends, or is resumed
  early, Simple TV comes back. The PIN on the settings card is 48sp.
- The daily catalog update defaults to 04:00, when nobody watches (existing choices are kept).

Decision (page):
- Words for a remote without number keys: the lineup is "what channel up and down move through";
  Remote buttons explains channel up/down, Live TV, holding Back, and giving a spare button
  "previous channel".
- "Play on TV" says what it replaces on the TV and asks first.
- Adding more than 10 channels at once asks first; categories whose names look adult get their own
  warning, since the adult filter only covers film channels.
- TV settings are grouped: Viewing (language, the clock switch and whether adult films are left
  out, how YouTube plays, home guard), This TV (address, PIN, remote buttons, page appearance) and a
  closed Advanced section (daily update, TV guide, YouTube key and account, film details and
  ratings, erase). The film editor's years, rating and score sit behind "Limit what plays".
- "Erase Simple TV" (Advanced, asked twice, behind the PIN) signs out of YouTube, deletes all of
  Simple TV's data on the box and restarts it as new.

Update: Moving channels on the Lineup page uses pointer events instead of the browser's
drag-and-drop, so it works with a finger as well as a mouse; near the window's top or bottom the
page scrolls (faster closer to the edge) and more channels are shown as it reaches the end. The
Lineup also has a card view (List / Cards, remembered per device): each channel's logo large, or
its initials, with number, name, type and what's on; cards can be dragged and edited too.

Update (25 November 2026): The corner clock has no box behind it. It stays over the picture all the
time, and at 48sp in a dark box it was too large on the Kogan and covered part of the picture. The
time is now drawn straight on the picture, with a dark edge around each digit and a soft shadow so
it reads over a bright scene; it hides only what its digits cover. The page chooses Off, Small
(32sp, the default) or Large (48sp) (`AppSettings.clockSize`). The clock keeps the corner opposite
the banner: top left when the TV language is Arabic, top right in English, so the two never
overlap. The clock in the channel list's header is unchanged (48sp, on the list's own background).

Not adopted: Back as "previous channel" (it stays close/cancel; previous channel goes on a spare
button the caregiver chooses), keeping channel up/down to live channels only (film channels stay
in the order, like film channels on a real TV), spoken channel names (later).

## ADR-025 Sorting films and series

Context: The caregiver wants film and series lists sortable by year, rating, name and other useful
measures.

Decision:
- The TV sorts (`CatalogSort`), so long lists sort as a whole before they are paged: name A to Z,
  newest or oldest year, highest score, official age rating (youngest audience first, on the
  household country's scale) and recently added. Titles without the value go last; ties go by name.
- The provider's own score ("rating", or "rating_5based" doubled) and the date it added each film
  ("added") or series ("last_modified") are now kept from the catalog. A score from TMDB, once a
  title is checked, wins over the provider's; so do a checked title's year and rating.
- The page's film and series lists (Add, and the film editor's search) have a Sort menu, remembered
  per kind on that device. A film channel's Order is A to Z, Newest first, Oldest first, Best score
  first, Recently added or Shuffled (`PlaylistSpec.sort`). Shuffled ignores the sort; hand-picked
  films keep the order they were picked in.

Consequences: Sorting by score or rating changes as the TV checks more films' details. Scores and
dates appear after the next catalog refresh.

## ADR-026 The product is called Telly

Context: The Telly design hand-off (docs/design/telly-handover) renames the product and gives it a
logo mark (a smiling TV with antennae), a favicon and an Android TV banner. Screens and behaviour are
unchanged from the first hand-off.

Decision:
- Everything a person reads says Telly: the TV app's name, its home-guard entry in Android's
  accessibility settings, the TV's own messages (English and Arabic, where the brand stays in Latin
  letters), and the config page (title, sidebar, first run, settings). The page's logo tile is the
  mark itself and its favicon is the gold tile mark. (Update: no letter icons anywhere. Channels without a logo show
  an icon for their kind, live, YouTube, films or series, instead of their initials.)
- The launcher icon is the mark as a vector drawable on gold, kept inside the adaptive icon's safe
  zone; the TV banner is the hand-off's 320x180 banner rendered to PNG (xhdpi and xxxhdpi) with the
  wordmark in Inter, until a final wordmark font is outlined.
- The package and code names stay `com.ielali.simpletv` (and the build's `simple-tv.apk`): a new
  package id would install a second app and lose the lineup and settings on the TV.
- The daily catalog update keeps its 04:00 default (ADR-024), not the hand-off's 21:00.

## ADR-027 Logos found for channels without one

Context: Many IPTV channels come without a logo, so the lineup, Home and the TV's channel card show
only a kind icon. A general web image search would be unreliable (wrong pictures, no licence, a
search API key, and scraping is ruled out), but two good sources already exist.

Decision:
- For a live channel without its own logo the TV looks, in order, at: the `<icon src>` the XMLTV
  guide gives the guide channel it is matched to (ADR-020); then iptv-org's public-domain channel
  database (https://github.com/iptv-org/api, `logos.json` and `channels.json`, CC0), by the
  channel's guide id and then by its tidied name. A name shared by several channels is settled by the
  TV's country (GB is UK there); if that doesn't settle it, nothing is chosen: no logo beats a wrong
  one. Adult and closed channels are left out, and PNG/JPEG logos are preferred because the TV
  can't draw SVG.
- The two iptv-org files are downloaded at most weekly, read as a stream, and kept as a small index
  (only channels that have a logo). Lineup and guide changes trigger a new look after 5 seconds.
- Found logos are kept apart from the channels (`files/logos/found.json`): the API returns them as
  `foundLogo`, never inside `logoUrl`, so saving the lineup never writes them in, and a logo the
  provider later sends wins. A found logo is kept while the sources can't be reached.
- The edit panel says when a logo was found automatically, with "Remove it"; a removed one stays
  off for that channel until "Find a logo again" (`PUT /api/channels/{id}/found-logo {off}`).

Consequences: Logos show up some minutes after the TV starts or the lineup changes, not at once.
Channels with uncommon or provider-specific names may stay without one. The pictures are loaded
from the guide's or iptv-org's hosts, as provider logos already are.

Update (more sources and a picker):
- Live channels nothing else knows are looked up on Wikidata: the whole name, then its brand (the
  name with words dropped from the end, never a generic word such as "Movies" alone), so "Netflix
  Documentary" gets Netflix's logo. Only an exact name whose description says TV channel, network,
  broadcaster, streaming or news counts; the logo is the item's P154 file on Wikimedia Commons
  (drawings as a 330 px PNG). At most 25 names per run; a miss is asked again after a week.
- YouTube channels get the channel's picture: from the household's subscriptions (no quota), else
  the YouTube Data API (`channels.list`, 50 per call; videos and playlists by their owner) with the
  API key or the signed-in account. Pictures are refreshed within 30 days (API policy).
- The edit panel says where a logo came from and has "Choose a logo": a search of iptv-org and
  Wikidata (and the channel's YouTube picture), or a pasted picture address. A chosen logo stays
  until changed. "Find one automatically" is offered whenever a channel has no automatic logo: it
  asks every source again for that channel at once (ignoring the week's wait for misses) and says
  whether one was found.
  (`GET /api/channels/{id}/logo-choices?q=`, `PUT /api/channels/{id}/found-logo {url}`.)

## ADR-028 Live channels keep a buffer: no live offset of our own, loading never pauses

Context: IPTV channels on the Kogan froze while playing. The dongle's own audio log
(`tools/tv-playback.py freezes`) for one evening on build 0.1.67 shows it. Of the four stays of 20 s
or more, two froze: four freezes of 3.6, 11, 12.8 and 17.1 s, 44.5 s frozen against 267 s played,
14% of the time. (Counting the sixteen shorter stays too, which are zapping, it is 11%; compare
builds with the same cut-off.) One channel played 25 s, froze 3.6 s, played 8 s, froze 11 s, played
9 s, froze 17 s.

What the streams look like (`tools/tv-playback.py probe`; 27 of that lineup's 30 channels, one
Xtream subscription): HLS, one quality, 10-second segments with one keyframe each, five segments in
the playlist (50 s), no hold-back or date tags, 1.7 to 2.9 Mbit/s. Every request goes through a
redirect and the servers are about 0.3 s away per round trip: the playlist takes 1.5 s, and a
segment usually 3 to 5 s but sometimes 12 s, slower than it plays. Wi-Fi was ruled out (0% loss,
8 ms to the router), and so was the guide call made on each channel change (it does not delay the
stream; `tools/tv-playback.py guide-call`).

Two settings of ours turned that line into freezes:
- `PlaybackEngine` asked to play 6 s behind live. ExoPlayer starts at the segment that contains that
  point, which is the newest one, so the player never held more than one segment. Whenever the next
  segment was published late, found late (the playlist is reread every 10 s) or downloaded slowly,
  the picture froze until it arrived. The 6 s is only a starting point: these playlists carry no
  hold-back and the engine set no speed range, so Media3 plays at exactly 1.0 and never moves towards
  or away from live by itself.
- The load control had a 3 s minimum and a 30 s maximum. ExoPlayer stops loading at the maximum and
  starts again only below the minimum, so with a full buffer it would wait until 3 s were left before
  fetching a segment that needs 3 to 12 s. On a live channel the buffer could only get that full
  after freezes had pushed playback some 30 s back; for films, and for live channels as soon as the
  first setting is put right, it would bite every time the buffer filled.

A second review read the first point differently: the engine also passed an explicit start position
of 0, an explicit position is used as written, so build 0.1.67 would have started 50 s back at the
oldest segment and the 6 s would never have applied. That holds for every explicit position except
0. Media3 creates the first period before it knows the stream, and when the stream arrives a
position of 0 is taken to mean "the default position" (`MaskingMediaSource`). To settle it the real
Media3 1.5.1 player was run on the JVM (Robolectric, renderers that discard their samples) against
a local playlist shaped like the provider's, five 10-second segments:

| Settings | Segments asked for first | Starts | Buffered after 6 s |
|---|---|---|---|
| 0.1.67: offset 6 s, start position 0, buffer 3 to 30 s | the newest only | 10 s behind the end | 5 s |
| This change: no offset, default position, buffer 30 s | third newest, then the two after | 30 s behind | 25 s |
| Offset 6 s kept, default position | the newest only | 10 s behind | 5 s |
| No offset, start position 0 | third newest, then the two after | 30 s behind | 25 s |
| Offset 6 s, start position 5 s | the oldest, then the three after | 45 s behind | 30 s, loading stopped |

So the offset was in effect, removing it is what gives the buffer, and the last row shows the load
control stopping at its maximum.

Decision:
- No live offset of our own. A live stream plays as far behind as it asks for (HLS hold-back, DASH
  presentation delay), or three segments behind when it does not say: HLS's rule (RFC 8216) and
  ExoPlayer's default. On this provider that is 30 s behind, with 20 to 30 s buffered.
- A live channel is started with `C.TIME_UNSET`, the stream's default position, instead of an
  explicit 0. Media3 already treated that 0 as the default (see the table), so this changes nothing
  on screen; it says what is meant and does not lean on a special case.
- Load control minimum equals maximum (30 s): loading carries on whenever there is room. The maximum
  stays at 30 s, so films use no more memory than before (the Kogan's heap limit is 256 MB).
- The picture still starts with 1 s buffered and resumes after a stall with 2 s.
- Off the screen, a live channel lets go of its stream. When the TV goes to standby or another app
  comes in front (`MainActivity.onStop`), `PlaybackEngine.onScreenGone` stops the player; back on
  screen, `onScreenBack` tunes the channel again from live, with the tuning card. A film or episode
  is paused and resumed as before and keeps its place. See the 0.1.89 run below for why.
- `PlaybackStats` (pure) times each channel's first picture and every freeze; the engine logs them
  under `SimpleTvPlayback` with the channel number and no address, so the next change to these
  numbers can be measured on the TV instead of guessed. "First picture" needs both the player ready
  (the tuning card, which covers the video, goes away) and a video frame drawn. With the screen on
  Media3 draws the frame first ("first frame" in the log) and is ready once the start buffer has
  filled. With the screen off, the app behind another one, or a channel with sound only, it gets
  ready without drawing anything; the log then says "ready, no frame drawn" and the stay's summary
  "no picture". Paused time does not count (a film paused by the viewer, or the moment between the
  screen going off and the channel letting go), a stay ends when the screen goes off, and a freeze
  shorter than a quarter of a second is ignored, as in the audio log's figures.

Consequences: Live channels run about 30 s behind the broadcast instead of about 10. The first
picture comes no later: either way it needs the redirect, the playlist and the start of one segment,
about 3.5 s on this provider. The player cannot hide a line that stays slower than the stream for
longer than its buffer; it then freezes once and carries on further behind, because playback speed
stays at 1.0 and nothing catches up. From 30 s behind in a 50 s playlist there are about 20 s of such
slack; past that the position falls out of the playlist and the engine's behind-live-window retry
puts it back at the default position.

Checked so far with a real-time model of ExoPlayer's rules (`tools/tv-playback.py model`: start
position, playlist rereads, one segment at a time, the load control), run from a laptop on the same
network, one minute per channel and setting. Its old settings start where the real player does (the
table above).

| Model run | Settings | Runs that froze | Freezes | Time frozen | Average buffer |
|---|---|---|---|---|---|
| Connections reused, six channels | old | 6 of 6 | 10 | 16% | 8 s |
| | new | 1 of 6 | 1 (2 s, right after the start) | 0.6% | 23 s |
| New connection per request, four channels | old | 4 of 4 | 6 | 12% | 8 s |
| | new | 1 of 4 | 2 (about 2 s each) | 2% | 14 s |

Whether the TV's HTTP stack reuses connections has not been measured, so the second run is the
cautious figure: the change still removes most freezes, with a thinner buffer. The first picture took
3.6 to 8.8 s under every setting. It is a model, not the player; the runs on the Kogan follow.

First run on the Kogan (build 0.1.88, 25 November, TV in standby, channels changed from a laptop):
four channels were ready after 7.5, 4.0, 3.3 and 3.0 s, and the one left on played 35 s without a
stall. No frame was drawn, as the decoder's own records confirm, because the screen was off. It
shows the streams load on the TV with the new settings; it is not yet the viewing check of item 0a.

Second run on the Kogan (build 0.1.89, 25 November, TV on from 08:46 to 09:05), from the playback
log: 15 channel changes over ten channels and 17 minutes of playing, one stay of 11 minutes.

| | Evening before, 0.1.67 | This run, 0.1.89 |
|---|---|---|
| Stays of 20 s or more in the TV's audio log | 4 | 5 |
| Played in them | 267 s | 900 s |
| Freezes | 4, of 3.6 to 17 s | 0 |
| Frozen share of the time | 14% | 0% |

The first frame was drawn after 2.0 to 3.3 s (middle value 2.8 s) and the picture was there after
2.5 to 3.9 s (middle value 3.0 s), over the 13 changes that gave a picture. The other two were the
same channel, which the provider refused both times (`ERROR_CODE_IO_BAD_HTTP_STATUS`, "No signal").
No channel froze right after it started. It is one morning and one long stay against one evening,
so the evening check of item 0a still stands.

The playback log shows one freeze that the audio log does not count, because a failure and its retry
are two stays there: 7.6 s on the channel that had been on through standby, 33 s after the screen
came on. That channel was tuned at 02:56 with the screen off and then only paused. The audio log
has its sound track living 5 h 50 min and playing 30 s: at 08:46 the player went on with the video
it had held since 02:56, drew its first frame 3.5 s later, ran out, failed with
`ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` and froze until the retry put it back on live. The
real-player rig used for the table above shows the mechanism. Paused, the player rereads the
playlist all the while (twice in 12 s), and resumed after the stream has moved on it plays on from
its old position. Stopped, it asks for nothing, and tuned again it asks for the third newest
segment of the new playlist. Hence the last decision above. It costs a live channel about 3 s of
tuning card whenever Telly comes back to the screen, also after a short trip to another app, and
the TV no longer holds the provider's stream while nobody watches. Pausing through standby is older
than this branch; what the branch added is the size of the buffer that is replayed, 30 s where there
were 10 at most. The new behaviour has not yet run on the Kogan (item 0a).

Still open: when the first segment arrives slowly, the picture starts with its 1 s and can freeze
for a couple of seconds straight after (twice in seven model runs, never in the 13 starts on the
TV). A larger start threshold would trade that for a longer wait on every channel change; the 1 s
stays unless the evening check shows such freezes.

Not adopted: OkHttp or Cronet instead of the platform HTTP stack (no measured gain yet), MPEG-TS
instead of HLS (faster first picture, but a stream with nothing behind it). See backlog item 15.

## ADR-029 A preview player in the page's channel list: the TV relays, and the TV always wins

Context: The caregiver wants to see a channel from the page, without walking to the TV or taking it
over with Play on TV. Three things stand in the way. A stream's address holds the subscription's
username and password, and the page must never see one (ADR-015). Only Safari plays HLS by itself;
other browsers need a player library. And the household's main subscription reports one stream at a
time (`max_connections = 1`; the two M3U ones report nothing), so a second stream while the TV is
watching may stop the TV's picture.

Decision:
- The TV relays. `POST /api/preview` gives the page a random 128-bit token, never an address.
  `/preview/<token>/<name>` asks the TV for what is behind a made-up name: a playlist comes back
  with every address in it swapped for more made-up names (`HlsRewrite`; a tag that still holds an
  address is dropped), anything else is passed on byte for byte as it arrives. Redirects are
  followed on the TV, the channel's own User-Agent is used, and a failure never carries the
  provider's answer. The token is in the path because a video element cannot send the page's
  sign-in; only a signed-in page gets one, and it dies with the preview.
- One preview at a time (`PreviewSessions`). It ends when the page closes it, when its time is up
  (3 minutes at first; a setting since the update below), when the browser has asked for nothing
  for 30 s, when another preview starts, or when the TV needs it.
- The TV always wins (`PreviewRules`). No preview of the channel the TV's own player holds, nor of
  another channel of the same subscription when that allows fewer than two streams. With no limit
  known, the same server is taken as the same account; two subscriptions are two accounts; a channel
  added by its link goes by its server. What the TV "holds" is the channel on screen and the one in
  its channel list's preview window, while Telly is on the screen: in standby a live channel has let
  go (ADR-028). A running preview ends the moment the TV tunes into such a conflict
  (`ConfigServer` watches `TvController`), and the window says why.
- The player is hls.js 1.7.4 (Apache-2.0, licence beside it in the assets), bundled and served by
  the TV, loaded on the first preview. The full build: the light one leaves out HEVC and AC-3 in
  MPEG-TS, which providers use. A browser without Media Source Extensions (older iPhones) uses its
  own player. Its settings are the ones the TV's player ended up with (ADR-028): three segments
  behind live and no offset of our own, half a minute loaded and no more, 10 s kept behind. The
  relay uses the same 8 s timeouts. Sound plays when the browser allows it, else the preview is
  silent and a sound button says so.
- Nobody watching means no stream: closing the window, leaving the screen it was started on, hiding
  the page and the time limit all let go of it.
- YouTube channels use YouTube's own embedded player in the caregiver's browser (rule 6), with no
  relay. Film and series channels are not previewed: their files are often MKV, which browsers do not
  play, and Play on TV is there.
- On the page: an eye button on each live and YouTube channel in the lineup, list and cards. The
  window sits beside the list on a wide screen, like the TV's own channel list, in the bottom corner
  on a narrower one and above the tab bar on a phone; the list keeps room so no channel stays behind
  it.

Checked on 25 November 2026, on the development Mac, not on the TV:
- 33 new unit tests, among them the routes end to end on a real server engine with a provider on a
  local socket: the redirect, the rewritten playlist, a segment byte for byte, the refusals, the
  end reasons, and that nothing the page receives holds the address.
- The page's own preview code in headless Chrome 158, against the relay and a fake live provider
  rolling a real segment of the provider's (H.264 High 1080p25, AAC). It asked for seg2202, seg2203
  and seg2204 of a playlist 2200 to 2204, the same three the TV's player asks for (ADR-028). The
  picture was there within a second of asking. It held 20 to 30 s loaded, reread the playlist
  every 10 s and fetched one new segment each time. When the TV "tuned", the window said so
  within a second (the page asks every 5 s) and the provider was asked for nothing more. A preview
  was refused while the TV was on, and played again after. With sound on autoplay refused, it
  played silently.
- Screenshots of the real page at 1440, 1280, 1180, 900 and 390 px wide.

Consequences and what is not known:
- Not run on the TV yet (backlog 0b): the relay on Android with the real provider, an iPhone, and
  Safari's own player.
- How the provider counts streams is unknown. A preview that ended a moment before the TV tunes may
  still count for a few seconds; the TV's player would show the tuning card and try again.
- A previewed stream crosses the TV's Wi-Fi twice, in from the provider and out to the phone. On a
  weak network that could disturb what the TV is playing from another subscription.
- The app grows by about 0.6 MB (the library, before compression).

Not adopted: the browser fetching from the provider itself (it would need the address, and most
providers send no CORS headers), the light build of the library, and letting the caregiver override
the TV. A preview with no time limit was on this list at first; see the update.

Update (25 November 2026, after the first try on the Kogan, build 0.1.93): three changes the
caregiver asked for.
- **The time is a setting, and it counts from the first picture.** The 3 minutes started at the
  ask, so a channel that was slow to start had used part of them before it showed anything. Now
  `previewMinutes` in `AppSettings`, chosen under TV settings ("Preview on this page"): 5 minutes,
  15 minutes (the default), 1 hour, or no limit (0). The TV starts the clock when the first video
  has been passed to the browser (`PreviewSessions.pictureStarted`); until then the window counts
  the seconds of loading. The page takes the time left from the TV every 5 s, so the two agree.
- **No limit plays until the window is closed.** Everything else that ends a preview still does:
  the TV needing the subscription, the page out of sight, and a browser that has asked for nothing
  for 30 s. Two things had only been safe because every preview was short, and are fixed:
  - Made-up names: at most 600 are kept, and the oldest used to go first. After about 100 minutes
    of 10 s segments that would have been the playlist the player keeps reloading. Now the least
    recently used goes first, so a name still in use stays.
  - A browser that stops reading (a frozen tab, a dropped network) left the relay waiting on it
    with the provider's connection still open, and nothing looked at the time or at the 30 s
    unless a request came in. `ConfigServer` now sweeps every 5 s and after every change on the
    TV (`PreviewRoutes.sweep`, off the main thread): it ends a preview whose time or browser has
    gone and cuts the provider's connection of every ended preview, read or not.
- **A subscription's own list can be previewed before a channel is added.** The eye is on every
  live channel under Add channel, From my TV subscription. `POST /api/preview` takes
  `{providerId, itemId}`; the channel is built as adding it would build it
  (`ProviderImport.tryOut`: the same address and headers). The same rules apply, and a list entry
  that is the stream the TV is showing (the same address, or the same subscription and source id)
  counts as the TV's channel.
- A channel that is not HLS cannot play in a browser player. The relay now sees that on the first
  answer (video where a playlist should be), lets go at once and the window says so and points to
  Play on TV, instead of loading without end. On the Kogan's three subscriptions on that day,
  24,302 of 25,055 live entries (97%) have an address ending in `.m3u8`; the rest are DASH, RTMP
  and plain files.
- The playback log (`adb logcat -s SimpleTvPlayback`, ADR-028) gets `preview:` lines: the start
  with its limit, the first twelve timings of each preview (a playlist's time; a segment's size,
  time to first byte, time to pass on and rate) and an end forced by the TV. No address, host or
  name. They are there to find out how long a real channel takes through the relay, which is not
  measured yet.
- On a wide screen the window sits under the categories in a subscription's list, so the channels
  keep their width and every eye button stays in reach; while searching, and on narrower screens,
  it is where the lineup has it.

Checked for the update, on the development Mac, not on the TV: 15 more unit tests (the clock from
the first video, no limit, a name in use for 5,000 segments, a list entry, the TV's own channel in
a list, a channel that is not HLS, and a browser that reads nothing while the provider is cut off
by the sweep). The page's own code in headless Chrome 158 against the relay: a channel whose
segments each took 6 s to start showed "Loading" for 6 s and then counted its minute from 1:00
(the TV ended it 60 s after the first video, and the window said so 2 s later); with no limit it
played until the TV "tuned" and then said so; a
plain MPEG-TS channel was refused in words within a second and its connection was closed; leaving
the screen closed the window. Screenshots of the subscription's list at 1440, 1180, 900 and 390 px
and of the setting.

Still not known: how long the household's channels take to show a picture through the relay on
the Kogan, and everything else under backlog 0b.

Second update (25 November 2026): full screen, and marks on the channels that were previewed.
- **Full screen.** A button on the corner of the picture (and a double click on it) puts the whole
  preview window full screen: the picture fills the screen and the bar with the name, the time
  left, sound and close lies over its foot, fading after 3 s without the pointer moving. The whole
  window rather than the video alone, so the reasons a preview ends and Play again show there too.
  An iPhone lets only a video go full screen, so there the video opens in the phone's own player.
  YouTube's player keeps its own button.
- **Marks.** The caregiver goes down a subscription's list to see which channels are worth
  adding, and needs each one tried to say how it went. The TV judges, not the page, because only
  the TV sees the provider: `PreviewChecks` notes **worked** when the first video has been passed
  to the browser, **failed** when the provider refused or did not answer before any video came (a
  web page where the stream should be counts as that), and **unplayable** when the address is not
  http or what it gives is not HLS, which says nothing about whether the TV can play it. A later
  preview replaces the mark; a failure after a picture does not take "worked" back; a preview
  closed early or refused for the TV leaves none.
- Marks are kept by subscription and source id where a channel has them, so a channel tried in a
  subscription's list shows its mark in the lineup once added, and by channel id otherwise. They
  are in `files/preview_checks.json` (results and times only, no address or name; at most 2,000,
  the oldest going first) and survive a restart; Erase Telly deletes the file with the others.
  `GET /api/preview/checks` gives them to the page: by channel id for the lineup, by
  "subscription/item" for the lists. Each list shows the mark under the channel's name.
- One case the mark cannot tell: the stream arrives but the browser has no decoder for it (HEVC or
  MPEG-2 in most browsers). The TV notes "worked", which is true of the stream, and the window
  says the browser cannot show the picture and points to Play on TV.
- Not adopted: marks in the browser's own storage (they would not follow the caregiver from the
  laptop to the phone), and the page reporting the result (the TV already knows, and a page
  cannot tell a dead channel from a browser that cannot decode it).

Checked for the second update, on the development Mac: 10 more unit tests (the marks' store, and
through the routes: worked, failed then worked, a web page as failure, the two unplayable cases,
no mark for an early close, the lineup copy of a list entry). In headless Chrome the marks
appeared under the right channel in both lists within a second of each outcome. Headless Chrome
refuses the Fullscreen API, so the full-screen layout and the button were checked with a stand-in
that does what the browser does (sizes the element to the screen and names it as the full-screen
element): the picture filled 1440 by 900, the bar faded after 3 s and came back when the pointer
moved, and the button led in and out. Real full screen in a browser, and on a phone, is not
checked.

## ADR-030 Posters stand alone, and film details open in the page instead of over it

Context: The page wrote each title over its poster. Posters carry their own lettering, usually at the
foot, so the two fought and neither could be read. Details opened in a dialog that hid the list.

Decision:
- Nothing is written on a poster: name (two lines at most), rating badge, year, length and score sit
  below it. A year at the end of the provider's name is moved to that line, so it is said once. A poster that is missing or fails
  to load leaves a plain tile with the film icon. Posters load as they come near the screen.
- Details open in a panel across the grid, under the row of the chosen poster. The poster travels
  from the grid into the panel, the row opens, and the text follows over the backdrop. Another poster
  moves the same panel. Previous/Next buttons and the arrow keys go through the list; Esc closes
  (from the trailer, it goes back to the details). Keys typed in a search box or a field are left to it. "About this
  series" uses the same panel at the top of the series channel's screen, outside the form, which is
  drawn again on every change: a trailer keeps playing.
- The grid uses dense packing, so the panel follows its poster in the page (reading and tab order)
  and the browser finds the row, also after a resize. More posters are added after the ones shown
  instead of drawing the list again, so a trailer keeps playing.
- There is only ever one panel: a title chosen while it closes takes over the closing one.
- All of it is CSS and the Web Animations API, with no library. Reduced motion gets no movement.

Not adopted: a gradient behind the title (it hides the poster's own name and says it twice), a side
drawer (half the width on a tablet, a dialog again on a phone) and a page of its own (the
caregiver loses their place in the list).

## ADR-031 A trailer YouTube refuses on the page is offered on YouTube or on the TV

Context: "Watch the trailer here" sometimes showed YouTube's "This video is unavailable". Of 13 cached
trailers, 4 were refused in a page opened by an IP address, and all 13 played in the same page opened
as localhost. The refused ones also played from a page named `telly.local` (name mapped in the browser
for the test). Their owners do not allow embedding on a bare address, which is how caregivers open
the config page.
The player host (youtube.com or youtube-nocookie.com) and the `origin` parameter made no difference.

Decision: the page listens to the embedded player's messages (`enablejsapi=1`, no script from Google)
and on an error replaces the player with a sentence and two buttons, Watch on YouTube and Trailer on
the TV. The referrer policy is written on the frame, since the player gives error 153 without one.
Safari will not start a player inside another page with sound, whatever was pressed in the page. When
the player is still idle 2 s after it is ready, it is started without sound and the page shows Turn the sound on.

Not adopted: claiming another site as the page's origin, and a page on a public site that embeds the
trailer (the household's choices would leave the house). Opening the page by a name is ADR-032.

## ADR-032 The TV answers to telly.local

Context: YouTube refuses some trailers on a page opened by a bare IP address, and plays them on one
opened by a name (ADR-031). The address also changes when the router hands out a new one.

Decision: a small mDNS responder (`MdnsMessage`, `MdnsResponder`) answers questions about one name,
`telly.local`, with the TV's IPv4 address. It asks first and takes `telly-2.local` and so on when
another device has the name, up to `telly-5.local`; then it does without for ten minutes. Two TVs that
took one at the same moment settle it by address, and a device that keeps claiming it (more than five
times in a minute) gets it. When the address changes or the name is given up, a goodbye makes devices
drop the old one. A change is noticed within about 2 s and announced after 1.5 s of asking. The TV screen and QR code
keep the IP address, since not every device finds such names. The page mentions the name where it
helps, when a trailer is refused, and asks the TV for it then.

Consequences: the app holds a Wi-Fi multicast lock (the box is on mains power). It shares port 5353
with Android's own mDNS. A browser treats the name as another site, so the PIN is entered once there.

Not adopted: Android's `NsdManager` (it advertises services, under a host name it makes up) and JmDNS
(a library of service discovery for one address record).

Port 80, to drop `:8080` from the address, is not possible: Android refuses ports below 1024 to an
app without root (on the Kogan `ip_unprivileged_port_start` is 1024 and a bind as Telly's user got
"Permission denied"), and browsers do not look up a port from a name for http.

## ADR-033 Telly becomes a household IPTV player, with Simple mode per TV

Context: the engine behind the elderly viewer's TV already covers most of an IPTV player:
Xtream and M3U subscriptions, cached catalogs, the TV guide, films and series with resume, logos,
ratings and previews. Only the TV-side interface is missing. General IPTV players (TiviMate,
IPTV Smarters) are feature-rich and hard for a household to set up; none has a mode for someone who
needs a plain television. The question was whether to grow Telly into a full player without losing
what the current viewer relies on.

Decision:
- One app, two modes, chosen per TV. **Simple mode** is today's product and keeps its rules unchanged
  (CLAUDE.md, S1–S8). **Full mode** is a household player: a side rail with Live TV (start screen),
  Guide, Films, Series, Search and Settings, and full-screen playback with channel up/down, digits and
  an info banner. No per-person profiles at start; a profile picker would put a menu in front of the
  Simple-mode viewer.
- **My channels** is the lineup under a new name: the numbered list Simple mode plays, Full mode's
  first Live TV category, and what number keys tune in both modes. One list, edited from the TV or
  the web page.
- **Switching**: Simple to Full from the web page, or by holding MENU for 5 s and typing the PIN with
  the number keys (off when no PIN is set). Over a YouTube channel the guard swallows MENU today; it
  will notice a 5 s hold and pass it to Telly, while a short press there still does nothing. Full to
  Simple from Settings or the page, without the PIN: Simple mode only takes things away. Entering
  Simple mode turns the web server back on, since the page is how Simple mode is set up.
- **The TV stops showing the PIN.** ADR-015 printed it in the MENU overlay so whoever is in the room
  could sign in; a PIN anyone can read cannot guard leaving Simple mode or adult categories. Instead
  the page's "Forgot PIN?" makes the TV show a one-time sign-in code for 2 minutes. One secret,
  and the person in the room can still get in. Not adopted: a second, never-shown household code
  beside the page PIN (two secrets to explain and forget).
- **First run** on a new install: language, a subscription (typed on the TV or sent from a phone by
  QR code), My channels (can be skipped), "Who is this TV for?" which sets the mode, then a PIN in
  either mode. Existing installs skip it and open in Simple mode.
- **The web page stays on the home network and becomes optional.** Day-to-day setup works with the
  remote in Full mode; the page remains the tool for bulk editing, film-channel rules, guide matching,
  keys, export/import and the mode switch. From M7 the PIN is required in both modes, because the
  page holds provider passwords in either: it is set at first run, "Remove the PIN" (ADR-015)
  becomes "Change the PIN", and a TV updated without one keeps working while the page asks for one
  on its next visit (the viewer's screen does not change, S8). Full mode can turn the server off.
- **Audience**: sideloaded first, Google Play later, free and open source, with no content and no
  provider suggestions. The name Telly stays, pending a trademark check before Play.
- **Two build flavours**: `sideload` with the home guard, `play` without it. Google Play's
  accessibility policy is very unlikely to accept an accessibility service used as a kiosk, and the
  guard also needs a permission granted over adb. In the `play` build Simple mode relies on Telly
  being the home app, which is weaker; the household that needs the guard sideloads.
- **Release key**: a real keystore outside the repo, held in CI secrets, signing release builds. The
  committed debug key is public, so anyone could sign an "update" with it. Debug builds keep the
  shared debug key but take a `.debug` package suffix, so a laptop or CI debug build installs beside
  the TV's release app instead of over it, and no copy of the release key is needed on a laptop.
  `docs/DEPLOYMENT.md` changes with it.
- **Export and import.** Because the signature changes, the installed Kogan cannot update in place.
  An export file moves it once and serves as a backup. It holds everything that is not rebuilt
  from a provider: the lineup, subscriptions with their passwords, film channels, settings,
  playback progress (films, series, YouTube playlists), `secrets.json` (PIN, TMDB key, YouTube API
  key, YouTube OAuth client and refresh token) and `guide/overrides.json` (manual guide matches).
  Catalogs, guide listings and the logo index are caches and stay out. The file is encrypted with a
  passphrase typed at export, not the PIN: 4 to 6 digits is too weak for a file that can be copied.
  M1 records the file format in its own ADR.
- **Storage**: the plan is to move the catalog and the TV guide to SQLite (Room), since Full mode
  searches and pages 10,000+ channels. Whether reading the JSON catalog whole is too slow is not
  measured yet; backlog item 0 measures it on the Kogan and M2 waits for that number. The guide
  keeps every channel for the next 24 hours, replacing ADR-020's window of the lineup's channels
  for 36 hours; if a large guide is too much for the Kogan, it falls back to My channels plus the
  category being browsed. Small files (lineup, subscriptions, settings) stay JSON.
- **Parental**: adult categories are recognised by name and hidden by default in Full mode; the PIN
  shows them for the session, and the web page can hide or show any category. Film rating rules stay
  as they are. Simple mode plays only My channels, so it needs nothing new.
- **Scope**: Android TV and Google TV. Fire TV is a likely follow-up (same APK, guard unchecked
  there). YouTube stays a channel source through the official player in both modes; no YouTube
  browsing or search in Full mode. Catch-up is left out of the first release.
- **Order**: the open Kogan checks and playback fixes first (both modes share the engine), then
  M1–M8 in `docs/BACKLOG.md`. Every milestone is checked on the Kogan; a second device (a Chromecast
  with Google TV or a Google TV Streamer) and the Android TV emulator join before Play.

Consequences: CLAUDE.md's product rules are split into All modes, Simple mode and Full mode. Old
rule 8 ("configuration happens off the TV") becomes A6, "configuration is possible off the TV".
On-screen keyboards are allowed in Full mode only, always with a QR code to type on a phone
instead. Every word stays translated and right-to-left in both modes; the huge text sizes are
Simple mode's only.

Not adopted:
- Two separate apps: two installs, two copies of the subscriptions, two update paths.
- A hosted configuration site with accounts: running costs, the household's IPTV passwords on a
  server, and network calls the project rules forbid. A caregiver in another city stays on the
  "Later" list (a hosted JSON config or Tailscale).
- Competing feature for feature with the established players.

## ADR-034 Full mode's screens use androidx.tv Material

Context: Simple mode's screens are video, a banner and one list, built with plain Compose. Full
mode has rails, rows of posters, a guide grid and settings, all driven by D-pad focus, which plain
Compose handles poorly on TV (focus restoration, scrolling to the focused item, focus indication).

Decision: Full mode's screens use `androidx.tv:tv-material` (cards, navigation drawer, immersive
list, focus indication). Simple mode's screens stay on plain Compose and Material3 so a library
change cannot touch them. The dependency is added in its own commit with M3.

Consequences: one more library to keep current. Leanback fragments and XML layouts stay out.
