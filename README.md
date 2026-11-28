# MotoNav

A small handlebar display for motorcycles: the phone does the navigation, the device shows the next turn.
Prototype: **Android app (Google Navigation SDK) → Bluetooth LE → ESP32 → 0.96" OLED**.

| Folder | What |
|--------|------|
| [`docs/`](docs) | Product plan, architecture, hardware choices, prototype plan, one-hour quickstart |
| [`protocol/`](protocol) | The BLE protocol shared by firmware and app |
| [`firmware/prototype-esp32/`](firmware/prototype-esp32) | ESP32 DevKit V1 firmware (PlatformIO) |
| [`android/`](android) | Android prototype app (Kotlin) |
| [`tools/minimap_sim/`](tools/minimap_sim) | PC simulator of the 412×412 round display: draws the screen from the same BLE bytes the phone sends |
| [`firmware/product-esp32s3/`](firmware/product-esp32s3) | Product firmware: ESP32-S3 + 412×412 round panel, layout B renderer (panel driver still missing) |

Start here: [`docs/ONE_HOUR_QUICKSTART.md`](docs/ONE_HOUR_QUICKSTART.md).

| Piece | Status |
|-------|--------|
| ESP32 firmware | **Works on real hardware** (ESP32 DevKit V1 + 0.96" I2C OLED): BLE link, arrows/distance/street, display driver switch (long-press BOOT). |
| Protocol | Cross-checked firmware ↔ Android test bytes and proven end to end. |
| Android app | **Runs on a real phone** (Samsung SM-G781B): Google Navigation SDK turn-by-turn feed verified, destination via **Share from Google Maps**, 31 unit tests pass. |
| Map feed | The app now sends the **route line and your position** to the device (new, additive `NavRoute`/`NavPos`); the firmware receives and stores them (not drawn yet: needs the colour panel). Built and tested: 117 Android unit tests pass in the real build, the firmware builds for the ESP32, 240 C++ checks pass. **Not yet tried on a phone with the device.** See `protocol/README.md`. |
| Route follower | Built and tested (57 unit tests, now also run in the Android build), **not yet used as a data source**: the map feed uses Google's route today. Parser not yet checked against live Ola Maps / Mappls responses. See `android/README.md`. |
| Display simulator | **PC simulation of the 412×412 round panel** (`tools/minimap_sim`): two layouts, mini-map, legibility numbers, sunlight preview, ride animation. 41 tests, including a run of the simulator's bytes through the real firmware decoder. Panel size and sunlight figures are **assumptions** until the panel datasheet is read. |
| Panel firmware | **Layout B renderer for the 412×412 panel** (`firmware/product-esp32s3`): matches the simulator on the PC, builds for the ESP32-S3. **Never run on a chip, and the panel driver does not exist yet** (needs the panel's controller datasheet). See its README. |
| Still to prove | Locked-screen reliability, sunlight readability, battery life, vibration/waterproofing — see `docs/HARDWARE_ROADMAP.md`. |

Docs: [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) · [`HARDWARE_AND_FLOW.md`](docs/HARDWARE_AND_FLOW.md) · [`PROTOTYPE_PLAN.md`](docs/PROTOTYPE_PLAN.md) · [`HARDWARE_ROADMAP.md`](docs/HARDWARE_ROADMAP.md) (**next: prototype → product**) · [`HARDWARE_PCB_GUIDE.md`](docs/HARDWARE_PCB_GUIDE.md) (parts, small PCB, costs) · [`GO_TO_MARKET_PLAN.md`](docs/GO_TO_MARKET_PLAN.md) (prototype → selling) · [`PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md) (spec draft, rider interviews, landing page) · [`DEVICE_DESIGN_V1.md`](docs/DEVICE_DESIGN_V1.md) (**production-intent hardware design**, with `hardware/` BOM and I/O budget)
