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
0a. **Check the buffering change on the Kogan (ADR-028).** Install the build, run
   `adb logcat -s SimpleTvPlayback` and watch IPTV channels for an evening: change channel twenty
   times and stay ten minutes on three of them. Record in ADR-028 the first-picture times, the number
   of freezes and the frozen share of viewing time, against the evening before the change (4 freezes
   of 3.6 to 17 s in 6 minutes of viewing, 11% frozen). `tools/tv-playback.py freezes` reads the
   same figures from the TV's own audio log afterwards, for any build. If channels still freeze right
   after they start, raise `START_AFTER_MS` in `PlaybackEngine` from 1 s towards 2.5 s and measure
   again (`tools/tv-playback.py model --settings now,start2.5` gives a first idea).
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
11. **Last-channel resume and standby.** On resume after HDMI-CEC standby, retune the last channel
   instead of showing a frozen frame. Today the player is only paused when the screen goes off: it
   keeps its buffer and keeps asking the provider for the playlist, so the TV holds the account's one
   stream while nobody watches, and on wake it plays up to 30 s of stale video and then has to
   recover. Stop the player in `onStop` and tune again in `onStart` (films keep their position).
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
    APK through Coil) for firmer connection reuse; opening the connection to the provider while
    digits are being typed; a per-subscription "MPEG-TS instead of HLS" switch, which starts faster
    but has no buffer at all behind it.
16. **A live MPEG-TS stream that ends is not retried.** When the server closes a plain `.ts` stream
    the player reports "ended", not an error, so `PlaybackEngine` does not retry and the last frame
    stays on screen until the viewer changes channel (found by reading the code; the Kogan's channels
    are HLS, which does not end). Retry a live channel that ends, like any other failure.

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

- Release signing: replace the committed debug key with a release keystore held in GitHub Actions
  secrets before any distribution outside the family. The debug key in `app/keystore` is not secret.

- Tizen (Samsung) / webOS (LG) web-app port sharing the channel model and config page; needs the
  hosted-config item below first. See DEPLOYMENT.md.

- Provider re-sync that also removes channels the provider dropped and refreshes names/logos
  (today import only adds new ones).
- EPG from the Xtream API (`get_short_epg`): "now playing" title in the banner.
- Encrypt providers.json with Android Keystore (today it is plaintext in app-private storage).

- XMLTV EPG: show "now playing" title in the banner.
- Favourites / hidden channels toggled from the web UI.
- Export/import of `channels.json` from the web UI.
- Multiple profiles (two viewers, two channel maps) selected by a coloured remote button.
- Remote config sync from a hosted JSON URL for caregivers in another city.
- Play Store / sideload release build with signing, banner bitmap, privacy policy.
- Accessibility: optional spoken channel name on tune (TextToSpeech).

## Done

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
