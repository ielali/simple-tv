# Caregiver web app: design handoff (23 Nov 2026)

Source: the Claude Design canvas "Simple TV caregiver app" (42 artboards). Every artboard renders
`Main.dc.html` with different props (`screen`, `size`, `theme`, `tv`, `addMode`, `catTab`, `filmMode`,
`guard`, `step`, `pin`), so that one file holds the complete markup, CSS, copy, sample data and icon
paths. It is written in the design tool's template syntax (`sc-if`, `sc-for`, `{{…}}`): port it,
do not serve it. `canvas.json` lists the artboards; `nizam-ui-tokens.json` is the design system.

The built page is `app/src/main/assets/config/index.html`. The decisions below come from the
developer handoff and override anything older in this repository.

## Decided

- Five-item navigation: Home, Lineup, Add channel, Subscriptions, TV settings. The film and series
  editor sits under Lineup. First run and sign-in hide the shell.
- Sidebar from about 1100 px, icon rail from 820 px, bottom tab bar below.
- Light and dark follow the device; TV settings has an Appearance switch (Same as my device, Light,
  Dark) stored in the browser's local storage.
- Colours (light / dark): bg #f4f4fc / #121216, card #fcfcfc / #1c1c22, tint #f4f4fc / #26262e,
  line #dcdce4 / #383844, ink #1c1c1c / #f3f3f6, muted #6b6b78 / #a9a9b8, primary #2464ec / #7aa4ff,
  on-primary #ffffff / #0b1220, highlight #fcd444, sky #8cbcfc, violet #ac8cfc, mint #dcfcec / #14382a,
  ok ink #0f7a43 / #6ee0a4, warn #fff3c4 / #3a3110 with ink #6b4a00 / #ffd96a, error #fde8e6 / #3f1d1b
  with ink #a8201a / #ffb4ac. Navy #1B2A41 and gold #FFC857 only for the "on the TV now" card and
  the playing channel's number.
- Type: system stack with Inter first, no external fonts. Body 14 px; page title 28 px (24 px phone);
  channel numbers 28 px bold (24 px phone). 4 px spacing base; cards pad 20 px (16 px phone), 24 px
  between cards; radii 16 cards, 10 inputs, 999 pills and buttons; touch targets at least 44 px.
- Daily catalog refresh at 9:00 pm by default, changeable in TV settings (`catalogRefreshTime`).
  Keep the last good copy when a refresh fails; flag lineup channels the provider dropped.
- PIN: 4 to 6 digits, shown on the TV's settings screen, 5 tries then a 5-minute lock, sessions in
  memory (end when the TV restarts). Plain HTTP for now (HTTPS parked in the backlog).
- The web interface stays English; the layout uses logical properties and `dir="auto"` on channel and
  category names so Arabic can be added later.
- YouTube @handles need the optional YouTube API key (ADR-014); without it the page says they
  aren't supported yet.

## Security rules from the handoff

- Provider passwords and any address that embeds one are never shown after saving.
- Error messages never contain a URL. Never put credentials in a query string the page sends.
- `GET /api/providers` returns no passwords; catalog endpoints return no stream addresses; the lineup
  endpoint returns no stream addresses either (YouTube links are not secret and stay visible).
- With a PIN set, every `/api/*` route except `GET /api/auth` and `POST /api/auth/login` needs the
  session token.
