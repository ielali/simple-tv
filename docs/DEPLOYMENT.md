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
