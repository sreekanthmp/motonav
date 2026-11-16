# One-hour desk demo

Goal: the OLED follows a **simulated** Google route, driven by the Navigation SDK through your Android phone.
Not in scope: real rides, locked-screen reliability, auto-reconnect polish (those are M4/M5 in `PROTOTYPE_PLAN.md`).

This only fits in an hour if the **prep is done first** — Android Studio's first install and the Google Cloud setup alone can take about an hour.

## What is already done (by the repo)

| Part | State |
|------|-------|
| `firmware/prototype-esp32` | **Flashed and working** on an ESP32 DevKit V1 + 0.96" OLED (display driver switchable with a long BOOT press). |
| `protocol/` | Written; firmware struct cross-checked against the Android test bytes. |
| `android/` | **Runs on a real phone** (Samsung SM-G781B): Navigation SDK feed verified; 31 unit tests pass. Destination comes from **Share in Google Maps**. |

## Prep (before the clock starts)

1. **PC:** USB driver for the ESP32 board (CP210x or CH340), `pip install platformio`, Android Studio (Meerkat or newer) with the Android 16 (API 36) SDK installed.
2. **Hardware:** wire the OLED — VCC→3V3, GND→GND, SDA→GPIO21, SCL→GPIO22.
3. **Phone:** Android 7+, Google Play services, USB debugging on, **nRF Connect** installed (free).
4. **Google Cloud** (see `android/README.md`): project + billing, enable *Navigation SDK for Android*, restricted API key, budget alert + low quota. Put the key in `android/local.properties`.
5. Pick a **start and destination near your city** as `lat,lng` (e.g. from Google Maps: right-click → copy coordinates).

## The hour

| Time | Do | Done when |
|------|----|-----------|
| 0:00–0:10 | `cd firmware/prototype-esp32 && pio run -t upload`. Press **BOOT** to cycle demo screens. Then in nRF Connect write the hex values from the firmware README. | Demo arrows show; a hex write changes the screen |
| 0:10–0:25 | Open `android/` in Android Studio, sync, run on the phone. Grant permissions, **Connect to MotoNav**, tap the test buttons. | Tapping "Left 300m" updates the OLED |
| 0:25–0:50 | **Init Navigation SDK** → accept terms → enter coordinates → **Simulated route**. Watch the on-screen log and the OLED. | The OLED follows the simulated route |
| 0:50–1:00 | Fix mapping issues, record a short video, press the phone's power button and see whether updates continue. | Demo recorded, issues noted |

If the Google part stalls, the second step still gives you a working phone → ESP32 → OLED demo with no Google account.

## Likely snags

| Symptom | Fix |
|---------|-----|
| Gradle sync / compile errors in `android/` | Expected on first open — fix the few reported lines (the code was never compiled). Make sure Android Studio is Meerkat or newer. |
| `NOT_AUTHORIZED` in the app log | API key missing/typo in `local.properties`, Navigation SDK not enabled, or billing off |
| `LOCATION_UNKNOWN` on real navigation | Go outdoors / near a window; simulated route does not need a fix |
| Route request fails with two-wheeler on | Turn off the *Two-wheeler routing* switch |
| Blank OLED | Try I2C address `0x3D`, or the `esp32dev_sh1106` build |
| Phone never finds MotoNav | Board not advertising — check `pio device monitor`; make sure Bluetooth and Location are on |
| Distances look off by one step | Check the log vs Google's own navigation UI; `currentStep` semantics are assumed (see `android/README.md`) |
