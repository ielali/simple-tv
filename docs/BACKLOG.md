# Backlog

Ordered. Take items from the top. Each item should be one PR. Mark done with the PR/commit.

## Next

1. **First device run.** Build `assembleDebug`, sideload on an Android TV box, confirm: app opens
   fullscreen, empty state shows the config URL, config page loads from a phone, adding an HLS
   channel plays. Record device, Android version and remote model in DEPLOYMENT.md.
2. **Key mapping audit on real remotes.** Log `keyCode` for every button of the chosen remote(s)
   and adjust `TvViewModel.handles`/`onKeyDown`. Confirm volume/mute/power pass through.
3. **YouTube embed spike.** Add three real live channels (news, music, nature cams). Note which
   embed, which refuse, whether autoplay works without a gesture on the box's WebView version.
   Decide on the intent fallback and record it in DECISIONS.md.
4. **YouTube app handoff on device.** With `youtubePlayback = YOUTUBE_APP`, confirm the TV build
   of YouTube opens watch URLs and `channel/UC…/live` URLs, that BACK returns to Simple TV, and that
   Premium removes ads. If channel-live URLs are not handled, resolve the live video id first
   (Data API, item below) and hand over the watch URL instead.
5. **Browse subscriptions in the config page (YouTube Data API).** Needs a Google Cloud project with
   the YouTube Data API enabled and an Android OAuth client (package `com.ielali.simpletv` + signing
   SHA-1), supplied by the operator. Sign in with the device account through Credential Manager,
   request `youtube.readonly`, then let the caregiver pick channels from "My subscriptions" and
   "Live now" instead of pasting links. Quota: search costs 100 units of the 10,000/day default.
6. **Config page PIN.** Optional 4–6 digit PIN stored on the device, shown on the TV settings
   overlay, required by the API (header or cookie). Off by default for the first install.
7. **Channel logos in the banner.** Load `logoUrl` with Coil, fall back to the number when missing.
8. **Clock in the banner.** Elderly viewers use the TV as a clock; show HH:MM in the banner corner.
9. **Stream health check in the web UI.** "Test" button per channel that has the TV try the URL for
   5 s and report OK / failed with the ExoPlayer error code.
10. **Kiosk hardening.** Document and script `dpm set-device-owner` + lock task; verify BOOT_COMPLETED
   launch on Android 11+ boxes; handle HOME via the launcher intent-filter.
11. **Last-channel resume and standby.** On resume after HDMI-CEC standby, retune the last channel
   instead of showing a frozen frame.
12. **Audio-only fallback.** If the box cannot decode video for a stream, keep audio and show the
    logo/name large (radio behaviour) instead of "No signal".

## Later

- XMLTV EPG: show "now playing" title in the banner.
- Favourites / hidden channels toggled from the web UI.
- Export/import of `channels.json` from the web UI.
- Multiple profiles (two viewers, two channel maps) selected by a coloured remote button.
- Remote config sync from a hosted JSON URL for caregivers in another city.
- Play Store / sideload release build with signing, banner bitmap, privacy policy.
- Accessibility: optional spoken channel name on tune (TextToSpeech).

## Done

- YouTube playback mode setting (embedded player or hand-off to the signed-in YouTube app), with
  `/api/settings` and a radio choice in the config page. See ADR-007.

- QR code beside the config URL on the empty-state screen and the settings overlay (ZXing core,
  drawn on a Compose canvas).

- Project scaffold: Gradle, Compose, Media3, Ktor, tuner + navigator + parser + YouTube parsing with
  unit tests, config web UI, CI workflow, docs.
