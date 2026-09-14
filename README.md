# Simple TV

An Android TV app that turns a modern set-top box into an old-style television for elderly viewers.
Numbered channels, one classic remote, no menus. Channels come from IPTV streams or YouTube live
channels and are configured by a caregiver from a phone or laptop.

## How it works for the viewer

- Type a number on the remote, the channel changes. Channel up/down works too.
- A large banner shows the channel number and name, then fades.
- BACK does nothing harmful. MENU shows one screen with the address for the config page.
- The app starts on boot and can be set as the home screen so the box never shows anything else.

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

Scaffold. Core tuning logic, playback engine, config server and web UI are in place and the pure
logic is unit tested. Not yet run on a device. Work through `docs/BACKLOG.md`.
