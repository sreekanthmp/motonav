# MotoNav

A small handlebar display for motorcycles: the phone does the navigation, the device shows the next turn.
Prototype: **Android app (Google Navigation SDK) → Bluetooth LE → ESP32 → 0.96" OLED**.

| Folder | What |
|--------|------|
| [`docs/`](docs) | Product plan, architecture, hardware choices, prototype plan, one-hour quickstart |
| [`protocol/`](protocol) | The BLE protocol shared by firmware and app |
| [`firmware/prototype-esp32/`](firmware/prototype-esp32) | ESP32 DevKit V1 firmware (PlatformIO) |
| [`android/`](android) | Android prototype app (Kotlin) |

Start here: [`docs/ONE_HOUR_QUICKSTART.md`](docs/ONE_HOUR_QUICKSTART.md).

| Piece | Status |
|-------|--------|
| ESP32 firmware | Compiles (both OLED variants). Not flashed to hardware yet. |
| Protocol | Cross-checked firmware ↔ Android test bytes. |
| Android app | Kotlin compiles against the real SDK/Android jars; 26 unit tests pass. Full Gradle build and phone run **not done yet**. Destination via **Share from Google Maps**. |

Docs: [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) · [`HARDWARE_AND_FLOW.md`](docs/HARDWARE_AND_FLOW.md) · [`PROTOTYPE_PLAN.md`](docs/PROTOTYPE_PLAN.md)
