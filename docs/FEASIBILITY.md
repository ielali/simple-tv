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
