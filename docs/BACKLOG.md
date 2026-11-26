# Backlog

Ordered. Take items from the top. Each item should be one PR. Mark done with the PR/commit.

## Next

0. **Check the new caregiver page and film channels on the Kogan.** Install over adb, then from a
   phone: sign in with a PIN, refresh a subscription and check the counts, add channels from a
   category, make a film channel and a series channel, press Play on TV, and confirm a series resumes
   at the right episode after switching channel and after a TV restart. Note how long the first
   catalog download takes and how big `files/catalog/` gets; if a big provider makes it slow or
   large, page the catalog file or move it to SQLite. Play a film channel with Arabic subtitles
   and an .mkv that has them; check the size and that "No Arabic subtitles" shows when it lacks them.
   Add a TMDB key, make a PG film channel and check how long the first rating check takes, how
   many films the provider's details call covers, and that the banner shows the rating.
   On a series channel press Right, Left (early and late in an episode) and OK on the Kogan remote.
   TV guide: check the free guide presets download (their links were not reachable from the build
   sandbox), how long a guide takes to read on the Kogan, and which lineup channels match. YouTube:
   sign in with a household OAuth client and check that live subscriptions show as "Live now".
   Channel list: press Live TV (FEATURED_APP_1) from Telly and from YouTube; check the preview
   window plays the highlighted stream after a moment, OK keeps it without re-tuning, Back brings the
   old channel back, the list reads from the sofa, holding Down scrolls, and a lone 0 goes back.
   YouTube playlist channel: add a public playlist; check it plays in order, Right/Left/OK work,
   the banner names the video, it resumes after a channel change and a restart, and that
   embed-blocked videos are skipped. Check whether ads appear.
0a. **Finish checking the buffering change on the Kogan (ADR-028).** A morning run on build 0.1.89
   is in ADR-028: 15 channel changes, 17 minutes, no freeze while watching, against 14% frozen the
   evening before the change. Two things are left, both with the TV on and
   `adb logcat -s SimpleTvPlayback` running (the TV's log keeps only a few minutes, so start it
   before watching):
   - Standby. On a build with `PlaybackEngine.onScreenGone`, watch a live channel, switch the TV
     off for a minute and on again. The log should say `left` when it goes off and `first picture`
     about 3 s after it comes on, the tuning card should show in between, and nothing should freeze
     half a minute later. Do the same on a film channel: it should carry on where it was.
   - An evening, when the provider is busiest: stay ten minutes on three channels and change channel
     twenty times. Record the freezes and the frozen share in ADR-028 beside the morning's.
     `tools/tv-playback.py freezes --since "MM-DD HH:MM"` reads them from the TV's audio log for
     that evening alone; keep `--min-seconds` at 20 as for the other runs. If channels freeze right
     after they start, raise `START_AFTER_MS` in `PlaybackEngine` from 1 s towards 2.5 s and measure
     again (`tools/tv-playback.py model --settings now,start2.5` gives a first idea).
0b. **Check the page's preview player on the TV (ADR-029).** Tried once on the Kogan (build
   0.1.93): it played, and 3 minutes were not enough with the time a channel took to load; how long
   that was is not recorded. From a phone and from a laptop, with `adb logcat -s SimpleTvPlayback`
   running:
   - TV in standby or on a YouTube channel: press the eye on a live channel in the lineup. Note the
     seconds the window counts while loading and whether it has sound, and copy the log's
     `preview:` lines into ADR-029: they give the provider's time to first byte and the rate the TV
     passed each segment on at. A rate near the stream's own (about 5 Mbit/s for 1080p) means the
     TV's Wi-Fi is the limit. Try an iPhone (Safari uses its own player there) and a laptop browser.
   - The same from Add channel, From my TV subscription: the eye on a channel that is not in the
     lineup, on each subscription.
   - With No limit chosen in TV settings: leave one playing for half an hour, then close the laptop's
     lid without closing the window. The log should show nothing more from it, and the TV should
     tune a channel of that subscription as fast as ever.
   - Full screen: the button on the picture's corner, on a laptop (the bar should fade when the
     pointer rests, Esc should leave) and on a phone (an iPhone opens its own player).
   - Marks: preview a channel that plays and one known to be dead; each should say so under its
     name, still be there after Telly restarts, and show on the lineup's copy of a channel that
     was tried in a subscription's list and then added.
   - While it plays, switch the TV on to a live channel of the same subscription. The window should
     say the TV is using the subscription. Note in ADR-029 whether the TV shows the tuning card
     longer than its usual 3 s or logs `ERROR_CODE_IO_BAD_HTTP_STATUS`: that is the provider still
     counting the preview.
   - TV on a live channel of the one-stream subscription: the eye should refuse, in words, and the
     TV's log should show no error.
   - TV on a live channel, preview a YouTube channel and a channel of another subscription: watch
     the TV's log for freezes while the preview plays (the stream crosses the TV's Wi-Fi twice).
1. **First device run.** Build `assembleDebug`, sideload on an Android TV box, confirm: app opens
   fullscreen, empty state shows the config URL, config page loads from a phone, adding an HLS
   channel plays. Record device, Android version and remote model in DEPLOYMENT.md.
2. **Key mapping audit on real remotes.** Use the config page's **Remote buttons** card on the Kogan:
   press every button, block anything that still opens an app, record codes in DEPLOYMENT.md.
   Confirm volume/mute/power pass through and that HOME returns to Telly after a reboot.
3. **YouTube app channel switching on the Kogan (ADR-010).** Confirm: digits and CH+/- over YouTube
   change channel with the banner drawn over YouTube; YouTube-to-YouTube replaces the video without
   Telly's screen; YouTube-to-IPTV and back works; BACK does nothing over YouTube; OK pauses.
   If a new video does not replace the playing one, add FLAG_ACTIVITY_CLEAR_TASK in ExternalPlayer.
3b. **YouTube embed spike.** Add three real live channels (news, music, nature cams). Note which
   embed, which refuse, whether autoplay works without a gesture on the box's WebView version.
   Decide on the intent fallback and record it in DECISIONS.md.
4. **YouTube app handoff on device.** With `youtubePlayback = YOUTUBE_APP`, confirm the TV build
   of YouTube opens watch URLs and `channel/UC…/live` URLs, that BACK returns to Telly, and that
   Premium removes ads. If channel-live URLs are not handled, resolve the live video id first
   (Data API, item below) and hand over the watch URL instead.
5. **Browse subscriptions in the config page (YouTube Data API).** Needs a Google Cloud project with
   the YouTube Data API enabled and an Android OAuth client (package `com.ielali.simpletv` + signing
   SHA-1), supplied by the operator. Sign in with the device account through Credential Manager,
   request `youtube.readonly`, then let the caregiver pick channels from "My subscriptions" and
   "Live now" instead of pasting links. Quota: search costs 100 units of the 10,000/day default.
7. **Channel logos in the banner.** Coil is in (the list's preview window uses it); add the logo to the banner.
9. **Stream health check in the web UI.** "Test" button per channel that has the TV try the URL for
   5 s and report OK / failed with the ExoPlayer error code.
10. **Hard kiosk (optional).** Device-owner receiver + lock task for fresh installs without a Google
   account. Never hide or suspend the stock Google TV home (ADR-009).
11. **A channel change while the TV is in standby.** Seen on the Kogan: a channel change in standby
   (Play on TV from the page, for one) starts the stream and plays it with the screen off, because
   every tune tells the player to play, and it plays on unseen until the channel changes or the TV
   has been on and off again. Decide what a tune in standby should do: wake the TV, or remember the
   channel and tune when the screen is on (`PlaybackEngine.onScreenBack` is where that would start).
12. **Audio-only fallback.** If the box cannot decode video for a stream, keep audio and show the
    logo/name large (radio behaviour) instead of "No signal".
13. **Tune at once when the number is complete.** After a digit the tuner waits 1.5 s for another
    one, even when no longer number exists: with channels 1 to 31, "5" and "12" cannot be the start
    of anything else. Let `ChannelTuner` commit immediately when no channel number starts with the
    typed digits plus one more. Saves 1.5 s on most typed channel changes; record the rule in an ADR.
14. **Guide download while a channel plays.** The provider's whole XMLTV guide is read every 12 hours
    and after lineup changes, on the same line as the stream. On the Kogan, check with
    `adb logcat -s SimpleTvPlayback` whether a freeze lines up with it; if it does, do it while the
    TV is in standby or after a channel has played steadily for a few minutes.
15. **Faster first picture (measure first).** On the Kogan's provider a channel needs about 3.5 s
    before the first picture whatever the player does: a redirect (0.9 s), the playlist (0.8 s) and
    the start of one 10-second segment over a line with 0.3 s round trips (about 2 s). Options, each
    to be measured with the playback log before adopting: OkHttp as the HTTP stack (already in the
    APK through Coil) for firmer connection reuse, which in ADR-028's model is the difference
    between 14 s and 23 s of buffer (it is not known whether the platform stack reuses connections
    on the Kogan; compare first-frame times and freezes with each); opening the connection to the
    provider while digits are being typed; a per-subscription "MPEG-TS instead of HLS" switch, which
    starts faster but has no buffer at all behind it.
16. **A live MPEG-TS stream that ends is not retried.** When the server closes a plain `.ts` stream
    the player reports "ended", not an error, so `PlaybackEngine` does not retry and the last frame
    stays on screen until the viewer changes channel (found by reading the code; the Kogan's channels
    are HLS, which does not end). Retry a live channel that ends, like any other failure.
17. **A real-player test for live start position and buffering.** ADR-028's table came from running
    Media3 itself on the JVM: Robolectric, two renderers that discard their samples, a local
    `ServerSocket` serving a five-segment live playlist. As a unit test it would pin "starts three
    segments behind, keeps loading" against the next change to `PlaybackEngine` or a Media3 bump.
    Needs Robolectric as a test dependency (its own commit) and a small synthetic MPEG-TS segment in
    test resources (not a recording of a real channel); adds about half a minute to the unit tests.

- **Check the page by name (ADR-032).** Open `http://telly.local:8080` from an iPhone, an Android phone
  and a Windows laptop; with the TV in standby for an hour; after the router restarts; and watch one
  refused trailer there.

### Full mode (ADR-033)

Telly becomes a household IPTV player with Simple mode per TV. These come after the Kogan checks
and playback fixes above, in this order; each milestone may be several PRs and is checked on the
Kogan before the next starts.

18. **M1 Release key, build flavours, export and import.** A release keystore held in GitHub Actions
    secrets, never in the repo; CI signs with it. `sideload` and `play` flavours: `play` leaves out
    `HomeGuardService`, `WRITE_SECURE_SETTINGS` and the guard's page card, and Simple mode there
    relies on Telly being the home app. Export and import on the web page: lineup, subscriptions
    with their passwords, film channels, settings and progress in one file. It holds passwords, so
    it is encrypted with a passphrase typed at export (a 4–6 digit PIN is too weak for a file that
    can be copied); record the format in an ADR.
    Then move the Kogan to the new key: export, uninstall, install, import. CI builds both flavours.
19. **M2 Catalog and guide in SQLite.** Room for the catalog (live channels, films, series,
    categories) and the guide, so Full mode can search and page 10,000+ entries. Keep the guide for
    every channel for 24 hours; measure size and read time on the Kogan with the household's
    provider, and fall back to My channels plus the browsed category if it is too much. Lineup,
    subscriptions and settings stay JSON. Existing caches are rebuilt from the provider, not migrated.
20. **M3 The two modes and Full mode's shell.** A mode setting (existing installs: Simple). Simple
    to Full from the web page, or hold MENU for 5 s and type the PIN with the number keys (off with
    no PIN). Full to Simple from Settings. Add `androidx.tv:tv-material` in its own commit (ADR-034).
    The side rail, Live TV (subscriptions when there are several, categories, channels, a preview
    window, now and next) and full-screen playback with up/down, digits and the info banner. Decide
    what BACK does at the top level of Full mode and record it.
21. **M4 My channels on the TV, Search, parental hiding.** Add, remove and reorder My channels with
    the remote. Search across live channels, films and series of every subscription. Adult categories
    recognised by name and hidden; the PIN shows them for the session; the page can mark any
    category hidden or shown.
22. **M5 Films and Series.** Poster rows by category, a details screen (TMDB when a key is set), play
    and resume, episodes by season. Reuse the film channels' subtitle and soundtrack choices.
23. **M6 Guide grid.** Times across, channels down, opening on My channels with a category switcher;
    OK on a programme on now tunes the channel.
24. **M7 First run, typing on the TV, Settings.** First run: language, a subscription, My channels
    (can be skipped), "Who is this TV for?" which sets the mode and asks for a PIN. The PIN is required
    in both modes from then on. Typing fields use the system keyboard and show a QR code that opens the
    page at that form; the TV fills in when the phone sends it. Settings: language, mode, PIN, the
    web server on or off, the page's address and QR code, subscriptions, about.
25. **M8 Ready for Google Play.** Trademark check on the name, store listing and screenshots, privacy
    policy (nothing leaves the house), content rating, the banner bitmap, target SDK as Play requires,
    a review of the `play` build for anything Play's policies reject, and a check on a second device
    (Chromecast with Google TV or Google TV Streamer) and the Android TV emulator.

## Later

- TV guide: a fuller "what's on later" list per channel on the page, and the YouTube live video's
  title as the "now" line for YouTube channels.

- Arabic descriptions from TMDB (`language=ar`) when the TV language is Arabic.
- Show the poster and rating in the TV banner, not just the rating text.

- Separate subtitle files: some providers list .srt/.vtt files for a film (Xtream `get_vod_info`).
  Attach them with `MediaItem.SubtitleConfiguration` when the file itself has none.
- Closed captions on live channels (CEA-608/708 in HLS), using the same setting per channel.

- HTTPS for the config page with a trusted certificate (parked; goal: provider passwords and the
  future PIN never cross the LAN in plain text). Plan: a subdomain the household owns, e.g.
  `tv.<domain>`, pointing to the TV's LAN address; the TV obtains a Let's Encrypt certificate itself
  via the DNS-01 challenge through the DNS provider's API (Cloudflare token scoped to one zone),
  renews it, and updates the record when the LAN address changes. Serve on :8443, redirect :8080.
  Caveats: router DNS-rebinding protection may need an exception for the name; the API token's
  first entry crosses the LAN once before HTTPS exists; check whether the Ktor CIO server engine
  supports TLS or the server must move to Netty. Alternatives considered: self-signed certificate
  (browser warnings), Tailscale (also gives remote access, no domain needed).

- Native-speaker review of `values-ar/strings.xml`; check right-to-left layouts on the device.
- Config page in Arabic (follow the TV language setting or the phone's browser language).

- Local videos channel type ("media vault", idea from tv-vibehack): family videos pushed to the device
  and played full screen with the remote; ExoPlayer already plays files.
- App allow-list on the config page, for households that do use Netflix or Prime through the guard.

- Tizen (Samsung) / webOS (LG) web-app port sharing the channel model and config page; needs the
  hosted-config item below first. See DEPLOYMENT.md.

- Provider re-sync that also removes channels the provider dropped and refreshes names/logos
  (today import only adds new ones).
- EPG from the Xtream API (`get_short_epg`): "now playing" title in the banner.
- Encrypt providers.json with Android Keystore (today it is plaintext in app-private storage).

- XMLTV EPG: show "now playing" title in the banner.
- Multiple profiles (two viewers, two channel maps) selected by a coloured remote button. Full mode
  only: Simple mode never shows a picker (ADR-033).
- Catch-up in Full mode: Xtream `tv_archive` replays, first from the guide grid, then pausing live TV.
- Fire TV: the same APK; check the home guard and the remote's keys there before saying it works.
- Remote config sync from a hosted JSON URL for caregivers in another city.
- Accessibility: optional spoken channel name on tune (TextToSpeech).

## Done

- The config page also opens as `http://telly.local:8080`, answered by the TV over mDNS (ADR-032).

- A trailer YouTube refuses to play on the page offers Watch on YouTube and Trailer on the TV instead
  of YouTube's "This video is unavailable" (ADR-031).

- Film and series names sit below their posters, and details open in an animated panel under the
  poster's row instead of a dialog (ADR-030). Checked in headless Chrome against a stand-in for the
  TV's API, at desktop, tablet and phone sizes; not tried on a real phone.

- The page's preview goes full screen from a button on the picture, and each channel previewed
  keeps a mark under its name in the lineup and in the subscription's list: stream worked, stream
  failed, or not a kind a browser can preview (ADR-029, second update). The TV keeps the marks.
  Full screen itself is checked with a stand-in only; device check: item 0b.

- The page's preview plays for a time chosen in TV settings (5 minutes, 15 minutes, 1 hour or no
  limit), counted from the first picture instead of the ask, and the eye is also on every live
  channel in a subscription's own list, to try a channel before adding it (ADR-029, update). A
  channel that is not HLS says so at once. The TV now lets go of a preview whose browser has
  vanished without being asked. Checked in a desktop browser against a fake provider; device
  check: item 0b.

- A preview player in the page's channel list (ADR-029): the eye on a live or YouTube channel plays
  it in a window on the page. Live channels come through the TV under a random token, so the page
  never sees an address; the TV always wins when it needs the subscription; the player is set as the
  TV's own (ADR-028). Checked in a desktop browser against a fake provider. Device check: item 0b.

- The corner clock has no box behind it and is smaller; the page chooses Off, Small or Large
  (ADR-024). It was too large on the Kogan and covered part of the picture. Not yet seen on a TV.

- A live channel lets go of its stream when the TV goes to standby or another app is in front, and
  tunes again from live when Telly is back; films keep their place (ADR-028). It came back from
  standby playing half a minute of hours-old video and then froze. Device check: item 0a.

- Live channels keep a buffer (ADR-028): no live offset of our own (the stream's hold-back, or three
  segments), loading never pauses below the maximum, and a playback log
  (`adb logcat -s SimpleTvPlayback`) with first-picture times and freezes. Device check: item 0a.

- Channel list on the Live TV button with a preview window and now and next for each channel, a lone 0 for the previous
  channel, a clock beside the banner, and remote buttons that can be given these jobs (ADR-022).

- TV guide: now and next in the banner and on the page, from the provider's guide with free XMLTV
  guides as a fallback, channel matching with manual fixes (ADR-020).
- YouTube suggestions from the household's subscriptions, live ones first, signed in on the TV with
  the device flow (ADR-021).

- Long lists grow as you scroll (catalog items and categories, lineup, film and series search,
  "What will play"); "Find a category" lookup in Add channel, the film editor and first run; IMDb
  links in the details sheet (from TMDB).

- Right, Left and OK move within film and series channels (next, start or previous, pause), with the
  same controls and "Play this now" on the config page (ADR-019).

- Film and series details (posters, trailers, plot, cast, score) and official ratings by country
  from the provider and an optional TMDB key; per-channel highest rating, lowest score and unrated
  rule; rating in the TV banner; never play adult titles (ADR-018).

- Subtitles and soundtrack language per film or series channel, large subtitles on the TV (ADR-017).

- Caregiver web app rebuilt from the Claude Design handoff (docs/design/handoff, ADR-015, ADR-016):
  five-section page with first run and PIN sign-in, cached catalogs with a daily refresh at 9 pm by
  default, one Add channel flow (subscription catalog, YouTube, link), film and series channels with
  resume, Play on TV now and now playing, lineup flags, refresh history, light and dark.

- Config page PIN: 4 to 6 digits, 5 tries then a 5-minute lock, shown on the TV settings screen.

- YouTube "Who's watching?" handled automatically after a hand-off (ADR-013 and addendum): teach the
  screen once on the config page; check `/api/debug/youtube-screen` (lastMatch) if it does not fire.

- TV screen in English and Arabic with right-to-left layout, chosen on the config page (ADR-012).

- Tuning card: channel number, name and spinner while an IPTV stream loads; small spinner on stalls;
  "No signal. Trying again…" with endless backoff retries; YouTube-app opening uses the same card.

- Provider import rebuilt (ADR-011): Xtream API first with account checks, playlist fallback,
  User-Agent retry, plain-language errors without credentials, pick categories before adding.

- Remote keeps working inside the YouTube app (ADR-010): app-scoped TvController, guard routing of
  channel keys over YouTube, channel banner overlay, direct YouTube-to-YouTube hand-over, getting
  started card and IPTV providers first on the config page.

- Soft kiosk home guard (ADR-009): accessibility service for HOME/TV and shortcut keys, stock home
  and sponsored-app covering with a YouTube hand-off exemption, pause/on/off and a remote key log with
  per-key blocking on the config page, boot self-repair, `tools/tv-admin.sh` with protected packages,
  starter debloat list, fake-adb script test in CI. Partial settings updates fixed.

- IPTV providers: name, icon, Xtream Codes server + username + password (or M3U link with Basic
  auth), one-click channel import, per-provider removal. Passwords never leave the TV.

- YouTube playback mode setting (embedded player or hand-off to the signed-in YouTube app), with
  `/api/settings` and a radio choice in the config page. See ADR-007.

- QR code beside the config URL on the empty-state screen and the settings overlay (ZXing core,
  drawn on a Compose canvas).

- Project scaffold: Gradle, Compose, Media3, Ktor, tuner + navigator + parser + YouTube parsing with
  unit tests, config web UI, CI workflow, docs.
