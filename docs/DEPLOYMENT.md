# Deployment

## Choosing hardware

The remote matters more than the box. Requirements:

- Number keys 0–9 that send `KEYCODE_0`…`KEYCODE_9` (or the NUMPAD variants).
- Channel +/– keys (`KEYCODE_CHANNEL_UP/DOWN`). The D-pad up/down also works.
- Big buttons, few buttons. Tape over or remove the rest.

Known-good combinations to verify (update after testing):

| Box | Remote | Notes |
|---|---|---|
| Android TV set (Sony/TCL/Philips/Hisense) | Bundled IR remote | Has digits; app runs on the TV itself |
| Any Android TV box | Bluetooth "senior" remote with keypad | Pairs as a HID keyboard |
| Chromecast with Google TV / Shield | Bundled remote | No digits; needs a keypad remote |

## TVs that are not Android TV (Samsung Tizen, LG webOS)

The APK only runs on Android TV / Google TV / Fire TV. Samsung sets such as the BU8000 run Tizen and
LG sets run webOS. Two routes:

1. **Android TV box on HDMI.** Any box above works. The Samsung Solar Cell remote has no number keys
   (its "123" button opens a Tizen-only on-screen keypad that never reaches the box), so pair a
   Bluetooth remote with a real number pad directly to the box and drive TV power through HDMI-CEC.
2. **Tizen / webOS web app port.** Both platforms run HTML/JS apps: AVPlay (Tizen) or the HTML5
   video element with HLS.js (webOS) for playback, and the TV input-device API for number keys.
   Constraints to plan for: no local HTTP server (the config page would move to a hosted page that
   writes a JSON file the TV polls), no launcher replacement (only "autorun last app"), sideloading
   through Tizen Studio / webOS CLI in developer mode. The channel model, tuner rules, provider
   import and the config page are reusable as-is in JavaScript.

## Getting the APK

Every push to `main` publishes the newest build as the GitHub pre-release named **latest**, with one
file, `simple-tv.apk`. Releases do not expire. The repository is private, so download it while signed
in to GitHub. To rebuild without a code change: Actions tab, Android CI, Run workflow.

All builds share one signing key (`app/keystore`), so a newer APK installs over an older one and
keeps the channel list. The config page header shows the installed build number.

## Installing on a Google TV dongle (Kogan KAGDSX5MA, Blaupunkt BPTV30, Chromecast)

1. On the dongle: Settings, System, About, press **Android TV OS build** seven times.
2. Settings, System, Developer options: turn on **USB debugging**. Note the IP address under
   Settings, Network and Internet.
3. From a laptop on the same Wi-Fi, with Android platform-tools installed:

   ```bash
   adb connect <dongle-ip>:5555      # accept "Allow USB debugging" on the TV, tick Always allow
   adb install -r simple-tv.apk
   adb shell am start -n com.ielali.simpletv/.MainActivity
   ```

   Without a laptop: install **Send Files to TV** on the dongle and a phone, download the APK on the
   phone, send it, then open it on the TV with the dongle's file manager after allowing
   Settings, Apps, Security and restrictions, Unknown sources for that app.

4. The TV shows "No channels yet" with an address and a QR code. Scan it with a phone on the same
   Wi-Fi, add one channel, press Save to TV.

Do not set up kiosk / device-owner mode yet. `dpm set-device-owner` refuses to run while any Google
account is on the device, so it needs either removing the account or a factory reset, and the app
side of it (backlog item 10) is not built yet.

## Installing

```bash
# On the TV: Settings > Device Preferences > About > tap "Build" 7x, then enable USB/Network debugging
adb connect <tv-ip>:5555
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ielali.simpletv/.MainActivity
```

Simulate the remote while developing:

```bash
adb shell input keyevent KEYCODE_1
adb shell input keyevent KEYCODE_2
adb shell input keyevent KEYCODE_DPAD_CENTER
adb shell input keyevent KEYCODE_CHANNEL_UP
adb shell input keyevent KEYCODE_MENU
```

## Testing on a Chromecast with Google TV

The cheapest real test rig. (The older cast-only Chromecast cannot run apps.)

1. Settings > System > About > press "Android TV OS build" 7x to unlock Developer options.
2. Settings > System > Developer options > USB debugging on (this also enables network ADB).
3. Settings > Network shows the IP. Then `adb connect <ip>:5555`, accept the prompt on the TV, and
   install as above. Alternative without ADB: the "Downloader" app opening a direct APK link.
4. The bundled remote has no digits. Pair any Bluetooth keyboard: number keys, Enter, Page Up/Down
   map to digits, OK, channel up/down. Or drive it from the laptop with `adb shell input keyevent`.

Proves: tuning, banner, config page, QR, IPTV playback, YouTube embed in Google's WebView, hand-off to
the pre-installed YouTube app. Does not prove: the final remote's key mapping, boot-to-app (Chromecast
resists custom launchers).

Without hardware, the Android TV emulator in Android Studio runs the APK with keyboard digits; use it
for UI and config flow, not for judging video playback.

## Configuring channels

Open the address shown on the TV (for example `http://192.168.1.50:8080`) from any browser on the same
network. Save writes `channels.json` on the TV and the running app updates immediately.

## Making it the only thing the viewer sees

1. **Launcher**: the app declares the HOME category. On boxes that allow a custom launcher, press HOME
   and choose Simple TV "Always". Google TV devices may not offer this.
2. **Boot**: `BootReceiver` starts the app after boot. Android 10+ may block background activity
   starts; being the HOME app avoids that.
3. **Kiosk (recommended for a device set up in person)**: factory-reset the box, skip adding a Google
   account, then:

   ```bash
   adb shell dpm set-device-owner com.ielali.simpletv/.admin.DeviceAdminReceiver
   ```

   This needs a `DeviceAdminReceiver` and lock-task setup (backlog item 8). With lock task enabled,
   HOME and BACK cannot leave the app and the system UI stays hidden.

## Updating

Sideload a new APK over ADB, or (later) host the APK and add a self-update check to the settings overlay.
