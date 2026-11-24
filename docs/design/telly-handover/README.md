# Telly caregiver web app: code handover

Android TV app for an elderly viewer. The TV serves this web page itself; a caregiver manages a numbered channel lineup from a phone or laptop on the same Wi-Fi.

Design canvas: https://claude.ai/artifact/2Bxh6MVfneqBeoCFdjvta3
Spec doc: https://claude.ai/artifact/3cHB5R8TDZ5EC1tuvoLqYm (screens, behaviour, API, security, open questions)

## Build constraints
- Vanilla HTML/CSS/JS, no build step, no external fonts or CDNs. Ship as static assets inside the APK and serve from the TV's HTTP server. HTTP only for now.
- Web UI is English only (Arabic is for the TV app). Use logical CSS properties and `dir="auto"` on channel names.
- One responsive layout, three shells: bottom tab bar (phone, ~390), icon rail (tablet, ~820), sidebar (laptop, ~1440). Light and dark; follow `prefers-color-scheme` by default with a manual switch (Settings > Appearance), persisted in localStorage.
- Body 14px, channel numbers 28px bold, 44px minimum touch targets.

## Where things are
- `design/Main.dc.html`: the single parameterised component. Props: `size` (phone|tablet|desktop), `theme` (light|dark), screen. Every screen is an `sc-if` block; `renderVals()` holds sample data; `ic` holds inline-SVG icon paths (reuse as-is). It is a design-canvas format, so port the markup and CSS to plain HTML; do not try to run it.
- `design/Home-*.dc.html`: examples of how a wrapper selects size/theme.
- `design/tokens.json`: Nizam UI tokens (light).
- `assets/`: `telly-mark.svg`, `telly-favicon.svg` (gold tile), `telly-tv-banner.svg` (320x180; export to PNG for Android TV `android:banner`, convert the wordmark text to outlines first).

## Theme variables (set on the app root)
Light: `--bg:#f4f4fc;--card:#fcfcfc;--tint:#f4f4fc;--line:#dcdce4;--ink:#1c1c1c;--muted:#6b6b78;--primary:#2464ec;--onp:#fff;--hl:#fcd444;--sky:#8cbcfc;--violet:#ac8cfc;--mint:#dcfcec;--okink:#0f7a43;--warnbg:#fff3c4;--warnink:#6b4a00;--errbg:#fde8e6;--errink:#a8201a;--ring:rgba(36,100,236,.2)`
Dark: `--bg:#121216;--card:#1c1c22;--tint:#26262e;--line:#383844;--ink:#f3f3f6;--muted:#a9a9b8;--primary:#7aa4ff;--onp:#0b1220;--hl:#fcd444;--sky:#8cbcfc;--violet:#ac8cfc;--mint:#14382a;--okink:#6ee0a4;--warnbg:#3a3110;--warnink:#ffd96a;--errbg:#3f1d1b;--errink:#ffb4ac;--ring:rgba(122,164,255,.3)`
Brand: navy `#1B2A41`, near-black `#0B1220`, gold `#FFC857`, off-white `#F2F4F8` (the "now on TV" card and the logo).

## Screens
home (now playing, status), lineup (numbered channels, reorder), add (catalog live/films/series, YouTube, link), subs (providers), film/series channels, settings (appearance, daily list update, guard, keys), first-run steps 1-3, states (empty/error/offline), login (PIN).

## API
Existing: `/api/status`, channels, `import/m3u`, `settings`, `guard`, `keys`, providers (preview, import, remove).
New, to add: `nowplaying` (must work in YouTube app mode), `play`, catalog (`categories`, `items`, `refresh`, `status`), film-channels (create, update, queue, restart), `youtube/preview`, auth (`get`, `login`, `pin`).
Daily catalog refresh time (default 21:00, editable) is stored as `catalogRefreshTime` via `PUT /api/settings`.

## Security
PIN login: 5 tries, then a 5-minute lock. Session via cookie or token on the LAN; no HTTPS for now. Never echo the PIN or keys back in responses.

## Open items
- Visual check of the design at 390/820/1440 in light and dark was not done in a browser; verify during implementation.
- Whether a YouTube API key is acceptable for `@handle` links.
- Final wordmark font: currently a rounded system stack; pick and outline it for the banner.
- Install is via adb (no store release).
