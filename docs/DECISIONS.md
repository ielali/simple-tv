# Decisions

Short architecture decision records. Newest at the bottom.

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
