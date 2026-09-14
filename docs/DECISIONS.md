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
