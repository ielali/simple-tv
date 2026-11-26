# Feasibility

## Verdict

Feasible. The IPTV half is a well-trodden path on Android TV. Two things decide the project:
whether the remote has number keys, and how YouTube is sourced. Everything else is ordinary Android
TV work.

## Platform and input

- Android TV provides what is needed: Kotlin, Media3 ExoPlayer for playback, Jetpack Compose for
  the D-pad UI. Number keys arrive as ordinary key events, so a "type 12, wait a second, tune" flow
  with an on-screen banner is straightforward.
- Hardware is the catch. Google TV sticks, Chromecast and Nvidia Shield ship remotes with no digits.
  Android TVs from Sony, TCL, Philips and Hisense have numeric remotes. Cheap Bluetooth "senior"
  remotes with big number pads pair with any box and send standard digit keycodes. Pick the remote
  first, then the device.
- Channel up/down, volume and power stay with the system. Only digits, up/down and OK are ours.

## Sources

- **IPTV** is the easy case: M3U playlist plus optional XMLTV guide, HLS or MPEG-TS streams, all
  playable in ExoPlayer with hardware decoding. Apps like TiviMate prove the stack; they are just far
  too complex for this audience.
- **YouTube** is the risk. The official YouTube Android Player API was discontinued. Legitimate routes:
  the embedded IFrame player in a WebView, or handing off to the YouTube TV app by intent. The WebView
  route works for many live channels but the terms require the embedded player, some channels disable
  embedding, and WebView playback on TV hardware is heavier than native. The intent route is solid but
  drops the viewer into YouTube's own UI.
- Extracting raw stream URLs (yt-dlp / NewPipe style) gives native playback but violates YouTube's
  terms, breaks every few weeks and rules out Play Store distribution. Not an option for this project.
- Decision: IPTV first as the core, YouTube as a WebView "channel type", intent handoff as a fallback
  to evaluate after device testing.

## Configuration

- Nobody should type on a TV with a D-pad. The app runs a small HTTP server on the LAN; a caregiver
  opens the box's address from a phone or laptop and gets a web page to add sources, name channels,
  assign numbers and reorder.
- Alternative for remote support from another city: the box polls a hosted JSON config. Deferred
  until there is a need; it adds hosting and auth.

## Lockdown

- Start on boot and become the launcher. Android TV supports replacing the home screen on many boxes;
  Google TV devices resist it. Device-owner mode set once over ADB with lock-task gives true kiosk
  behaviour on any box and is the reliable route for a device set up in person.

## Effort (one developer, or an AI agent with a human reviewer)

| Slice | Estimate |
|---|---|
| IPTV player, number tuning, banner, boot start | 3–4 weeks |
| Local web config UI | 1–2 weeks |
| YouTube WebView channel type with app fallback | 1–2 weeks plus hardware testing |
| Kiosk mode and settings PIN | under 1 week |

## Risks, in order

1. Remote without digits.
2. YouTube embed reliability and terms.
3. IPTV stream URLs that rotate or require auth headers.

All three are testable in a one-day spike on real hardware before committing further.

## Full mode (ADR-033, November 2026)

Verdict: feasible on the existing engine. Subscriptions, catalogs, the guide, films and series with
resume, and the player are built and in daily use; Full mode is mostly TV screens over them. The
risks are elsewhere:

1. **Catalog and guide size on cheap boxes.** A big subscription has 10,000+ channels and a guide of
   tens of megabytes. Moving both to SQLite (M2) is the answer; measure on the Kogan before building
   screens on top.
2. **Google Play policy.** Play allows IPTV players that ship no content, but the home guard is an
   accessibility service used as a kiosk and is very unlikely to pass review. The `play` build leaves
   it out; Simple mode there is weaker (it relies on Telly being the home app).
3. **The signing change.** Moving from the committed debug key to a release key cannot update the
   installed TV in place; export and import (M1) must work before the switch.
4. **Competition.** Established players have years of features. Telly competes on Simple mode and
   phone setup, not on feature count.
5. **Remote navigation quality.** Focus handling on Android TV is where players feel cheap;
   `androidx.tv` Material (ADR-034) and testing on a second device reduce the risk.
