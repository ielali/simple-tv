# Claude Design brief: Simple TV caregiver web app

Paste everything below the line into Claude Design.

---

## What you are designing

Design the **caregiver web app for Simple TV**. Make it look professional, calm and trustworthy, and make adding channels from YouTube or IPTV close to effortless. You are redesigning an existing working page, not inventing a product. Keep every capability listed under "What exists today", reorganise them into clear screens and flows, and design the planned features marked **(planned)**.

## The product in one paragraph

Simple TV is an Android TV app that turns a streaming stick (currently a Kogan Google TV dongle) into an old-fashioned television for an elderly viewer with no digital experience. The viewer sees one full-screen channel and changes it with the remote: up and down step through channels, number keys tune directly where the remote has them, and a large banner shows the channel number and name. There are no menus on the TV. Everything is set up by a **caregiver**, usually an adult son or daughter, from a phone or laptop on the same Wi-Fi. The TV serves this web app at an address such as `http://192.168.0.79:8080`, shown on the TV with a QR code.

## Who uses the web app

- **Primary: the caregiver.** Comfortable with a phone, not an IPTV expert. Visits occasionally to add or reorder channels, fix something the viewer reported, or pause the TV's lock to change Wi-Fi. Often on a phone standing next to the TV, sometimes on a laptop.
- **The viewer never sees the web app.** Every decision should be judged by how it shapes what the viewer gets on the TV. The core idea is a short, well-ordered **lineup** of numbered channels, typically 10 to 30.
- **Languages.** The TV screen already supports English and Arabic. The web app is English today, but design it so Arabic with right-to-left layout can be added: logical start/end alignment, no text baked into images, and room for longer strings. Channel names arrive in either script, often mixed in one list.

## Hard constraints

- **Single self-contained page served by the TV.** Vanilla HTML, CSS and JavaScript with no build step and no framework. No external fonts, CDNs or images: the phone may be online but the TV serves everything. Use system font stacks and inline SVG icons.
- **Works at 390 px (phone), 820 px (tablet) and 1440 px (laptop).** Phone first. Touch targets at least 44 px.
- **Light and dark themes**, following the device setting.
- **Large lists.** A real IPTV subscription can hold 10,000+ live channels and thousands of films. Lists must be searchable and paged or virtualised, and must never render everything at once.
- **Security.** No login yet; a PIN is planned. Provider passwords and the optional YouTube API key are never shown after saving, only as "saved". The key lives on the TV, and the TV makes all YouTube API calls, so the key never reaches the phone. Error messages never contain URLs, because IPTV URLs embed the password.
- **The TV can be unreachable** (switched off, Wi-Fi changed). Design that state clearly.

## Visual direction

Calm, warm and confident: a well-made home appliance, not an IPTV hacker tool or an admin dashboard. The TV screens use deep navy `#1B2A41` and near-black `#0B1220` backgrounds, warm gold `#FFC857` for channel numbers, and off-white `#F2F4F8` text. Echo that family so the phone and TV feel like one product, but use a lighter ground for daytime phone use. Channel numbers are the hero element, set large and bold. Give generous spacing, few borders, plain language and no jargon: say "channel list", not "M3U"; "subscription", not "Xtream". Technical detail belongs in an "Advanced" disclosure.

## What exists today (all working)

**Channel lineup**
- An ordered list of channels, each with a number (what the viewer types), name, logo, type (IPTV stream or YouTube), source address and an optional group.
- Add, rename, renumber, remove, drag to reorder, "Renumber 1…n in this order", "Sort by number". Changes are saved explicitly with "Save to TV" and apply on the TV immediately.

**IPTV subscriptions ("providers")**
- Add a provider: name, optional icon URL, type (server address plus username and password, the "Xtream" kind, or a single playlist link), username and password.
- "Import channels" downloads the provider's list and shows **categories with channel counts** and the account's expiry date. The caregiver searches categories, ticks some, and adds them. A confirmation appears above 300 channels.
- Plain-language failure messages: wrong username or password, subscription expired, account banned, server not found, provider not answering.
- Removing a provider removes its channels.

**YouTube**
- A channel of type YouTube takes a video link, a live link or a channel ID (starting `UC`). A channel ID always plays that channel's current live stream.
- A household-wide choice of how YouTube plays: inside Simple TV (embedded, anonymous, with ads) or in the YouTube app (uses the signed-in account, so Premium means no ads). In app mode, up and down still change channel over YouTube.

**TV settings**
- TV language: device language, English or Arabic.
- Home guard: keeps the TV on Simple TV, so Home returns to the TV and assistant and app shortcut buttons do nothing. "Pause 15 minutes" lets a caregiver use Google TV settings, "Resume now", turn on or off, and a status line.
- Remote buttons: press buttons on the remote, refresh, and see each one listed with its result. Block buttons that still open something.
- TV status: app version and the address of this page.

**API endpoints the page uses now**

```
GET  /api/status             app version, channel count, page address
GET  /api/channels           lineup
PUT  /api/channels           replace lineup
POST /api/import/m3u         add channels from a pasted playlist or link
GET  /api/settings           settings; PUT sends only changed fields
GET  /api/guard              home guard state; POST /api/guard/pause, /resume
GET  /api/keys               recent remote presses; POST /api/keys/clear
GET  /api/providers          subscriptions (passwords never returned)
PUT  /api/providers          save subscriptions; blank password keeps the saved one
POST /api/providers/preview  download a provider's list, return categories and counts
POST /api/providers/import   add channels from chosen categories
POST /api/providers/remove   remove a provider and its channels
```

## Planned features to design

1. **Cached catalog with categories (planned).** Each IPTV subscription's full catalog is stored on the TV with its categories, so browsing is instant and works when the provider is slow. It refreshes automatically once a day at a quiet hour and on demand ("Refresh now"). Show the last refresh time, the next scheduled refresh, success or failure in plain words, and what changed (for example "12 channels added, 3 removed"). Lineup channels the provider no longer offers are flagged, never silently deleted.

2. **One "Add channel" flow (planned).** Replace today's scattered sections with one entry point and three choices:
   - **From my TV subscription:** browse the cached catalog by category, search across all categories, see logos, and add single channels or whole categories. Show which are already in the lineup.
   - **From YouTube:** paste a link, channel ID or `@handle`, and the name is filled in for the caregiver. What works depends on an **optional YouTube API key** (decided, see below):
     - **Video or live link:** preview with thumbnail, title and channel name, **without** a key.
     - **Channel link (`/channel/UC…`) or channel ID:** works without a key. The preview shows the channel name when a key is present, and a generic YouTube tile otherwise.
     - **`@handle`:** with a key, resolves to the channel and shows its name and avatar. Without a key, an inline message: "To add @handle links, add a YouTube API key in Settings, or paste the channel's link instead", with a button to Settings.
     - **"Live now" badge:** only with a key, checked once when adding. Show "Live now", "Not live right now (will play its next live stream)" or no badge.
   - **From a link:** a single stream address, under Advanced.

   Every path ends the same way: the channel gets the next free number by default, the caregiver can change the number or name, and it lands in the lineup where they can see it.

3. **Film and series channels (planned, design the concept).** Many subscriptions include films and series, not only live TV. In keeping with the old-TV idea, the viewer still never browses a library. Instead the caregiver creates a **film channel**: a numbered channel that plays films from chosen categories or a hand-picked list, one after another, in order or shuffled. A **series channel** plays a chosen series episode by episode and remembers where it stopped. Design how the caregiver creates and edits these (pick category or titles, order, preview the queue), how they look in the lineup next to live channels, and how the catalog browser separates Live TV, Films and Series.

4. **"Play on TV now" (planned).** From any channel row or catalog item, tune the TV to it immediately so the caregiver can check it works. Show what is playing on the TV right now at the top of the app.

5. **PIN lock (planned).** An optional 4–6 digit PIN to open the web app, set from the app and shown on the TV's settings screen.

## Screens and flows to deliver

Provide each at 390 px and 1440 px, plus 820 px where the layout changes meaningfully, in light and dark.

1. **Home:** what is on the TV now; lineup summary; subscription health (active, expiry soon, refresh failed); quick actions "Add channel" and "Refresh"; a clear "TV not reachable" state.
2. **Lineup:** the numbered list as the hero. Reorder by drag on desktop and move up/down or long-press on phone. Inline rename and renumber, remove with undo, and type badges for Live, YouTube, Film and Series. Flag missing or failing channels. Unsaved-changes handling.
3. **Add channel:** the three paths above, including catalog browsing with category list and search, bulk add, and the YouTube preview.
4. **Subscriptions:** list of providers with status, add or edit form (password saved state, test connection), refresh now, refresh history, and remove with clear consequences.
5. **Film or series channel editor.**
6. **TV settings:** language, how YouTube plays, home guard (on, off, paused with countdown), remote buttons log with block and unblock, and the page address and QR code. Under YouTube, an **Advanced** area with "YouTube API key (optional)", a short "Why?" explainer, and a "How to get a free key" link to an instructions page (no inline wizard). After saving, show "Key saved" with Replace and Remove. The key is never displayed again, as with provider passwords. Key errors in plain words: "The key was not accepted", "YouTube's daily limit was reached, try again tomorrow", "Handle not found".
7. **First-run setup:** for an empty TV. A short guided path: add a subscription or YouTube channel, add a few channels, see them on the TV.
8. **States:** loading, empty, a 10,000-channel catalog, very long and right-to-left channel names, provider errors, TV unreachable, saving and saved, and destructive confirmations.

## Tone of the words

Short, warm and concrete. Write "Your subscription ends on 12 March 2027", not "exp_date". Write "The provider didn't accept this password", not "HTTP 401". Buttons say exactly what happens: "Add 12 channels", "Play on TV", "Refresh channel list". Avoid "M3U", "Xtream", "stream URL" and "User-Agent" outside Advanced.

## Out of scope

The TV screens themselves (already built), Google account setup, payment, and anything requiring a cloud backend: the TV is the only server.
