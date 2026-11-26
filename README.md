# Telly

An IPTV player for Android TV and Google TV with a **Simple mode** that turns the TV into an
old-style television for elderly viewers: numbered channels, one classic remote, no menus. Channels
come from IPTV subscriptions or YouTube and can be set up from a phone or laptop on the home network.

Simple mode is what Telly has been so far and is in daily use. **Full mode**, a household IPTV player
with Live TV, a guide, films, series and search, is being built (ADR-033, backlog M1–M8).

Telly was called Simple TV. The package (`com.ielali.simpletv`), the repository and the release file
(`simple-tv.apk`) keep the old name so updates install over the existing app (ADR-026).

## How it works for the viewer

- Type a number on the remote, the channel changes. Channel up/down works too.
- A large banner shows the channel number and name, then fades.
- BACK does nothing harmful. MENU shows one screen with the address and a QR code for the config page.
- The home guard keeps the TV on Telly: HOME returns here, assistant and app shortcut buttons do
  nothing, and the app starts after a reboot. A caregiver can pause it from the config page.

## How it works for the caregiver

1. Install the APK on the Android TV box (see `docs/DEPLOYMENT.md`).
2. On the TV, the empty screen shows an address like `http://192.168.1.50:8080`.
3. Open it on any phone or laptop on the same Wi‑Fi. Add channels, import an M3U playlist from an
   IPTV provider, paste YouTube live links, drag to reorder, press **Save to TV**.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew testDebugUnitTest assembleDebug
```

CI builds the debug APK on every push to `main` and attaches it to the workflow run.

## Documentation

- `CLAUDE.md` — rules and conventions for AI developers working on this repo
- `docs/FEASIBILITY.md` — why this is buildable and where the risks are
- `docs/ARCHITECTURE.md` — components and data flow
- `docs/DECISIONS.md` — architecture decision records
- `docs/BACKLOG.md` — ordered work list
- `docs/DEPLOYMENT.md` — installing on a box, kiosk mode, remotes

## Status

Simple mode is in daily use on a Google TV dongle. Live TV, YouTube, film and series channels, the
TV guide, the channel list, logos and the caregiver web app are in place; the pure logic is unit tested and every
push to `main` publishes a build. Full mode is next, after the open device checks; see
`docs/BACKLOG.md`.

## Acknowledgements

The home guard approach and the rule never to disable the stock Google TV home come from
[tv-vibehack](https://github.com/toomramcharan/tv-vibehack) (MIT), built for the same audience.
Telly reimplements the idea in Kotlin; see `docs/DECISIONS.md` ADR-009.
