# MotoNav product firmware: ESP32-S3 + 412 × 412 round panel (layout B)

Draws the display you chose (layout B of [`tools/minimap_sim`](../../tools/minimap_sim)) from the data the phone sends over
Bluetooth: big arrow and distance when a turn is near, and arrow, distance, street, north-up route map, distance left and
minutes left otherwise, plus rerouting / arrived / choose-a-destination / waiting-for-phone screens. There is no separate "off route" screen: the
Google Navigation SDK never reports it (it goes straight to rerouting), so the protocol's OFF_ROUTE mode shows the rerouting
screen.

## Status: read this first

| | |
|---|---|
| **Renderer** (`lib/mnui`) | Written and tested on a PC. Draws every screen of layout B. Its pictures match the simulator's (mean difference about 1 of 255 levels). |
| **Firmware** (`src/`) | **Builds** for the ESP32-S3 (562 KB flash, 133 KB static RAM). **Never run on a chip**: no ESP32-S3 board and no panel were available. |
| **Panel driver** | **Missing.** `src/panel_stub.cpp` accepts the pixels and discards them. Nothing can appear on the real panel until a driver for its controller IC is written. See "What is missing". |
| **Timing on the chip** | **Not measured.** On a PC the heaviest screen renders in about 2 ms; I expect roughly 30–50× that on an ESP32-S3 (60–100 ms), but that is an estimate. The firmware prints the real numbers over serial once it runs. |

So "draw it on the real panel" is done as far as it can be without the panel: everything up to the last step, the bytes
into the controller, exists and is checked. That last step needs the panel's datasheet.

## How it works

```
BLE writes ─► ble_service / nav_state / nav_map   (the prototype's files, compiled from ../prototype-esp32/src)
                         │ state about every second
                         ▼
              mnui::Ui::update()      builds a display list: ~25 filled paths (text outlines, arrows, route line ...)
                         │
                         ▼
              mnui::Ui::renderBand()  anti-aliased, 16 rows at a time into a 13 KB RGB565 buffer
                         │
                         ▼
              Panel::writeRect()      the driver sends the band (QSPI, DMA)      <- the missing piece
```

Why bands: a full frame is 412 × 412 × 2 = 339 KB, more than the free internal RAM of an ESP32-S3 that is also running
Bluetooth. A band needs 13 KB. A display list of 9,000 points (36 KB) is built once per frame and drawn once per band.

Why filled outlines for everything, including text: one small font table (26 KB source, about 10 KB in flash) works at any
size, so the sizes the layout needs (92, 58, 56, 52, 44 ... 26 px, and street names that shrink to fit) need no per-size
bitmaps, and text, arrows and the map all go through one rasteriser.

## Files

| File | Role |
|---|---|
| `lib/mnui/mn_raster.*` | Display list + anti-aliased band rasteriser (non-zero winding, 8 sub-scanlines, exact horizontal coverage, rounded-rectangle clip) |
| `lib/mnui/mn_font.*`, `mn_font_data.cpp` | Text from glyph outlines; kerning. The data file is **generated** by `tools/gen_font.py` |
| `lib/mnui/mn_icons.cpp` | Manoeuvre icons, left-hand-traffic roundabouts |
| `lib/mnui/mn_screens.*` | Layout B, the mini-map, status screens |
| `lib/mnui/mnui.h` | The public header |
| `src/main.cpp` | Reads the phone state, redraws when something changed (at most 4 per second, at least 1 per second) |
| `src/panel.h`, `src/panel_stub.cpp` | The panel driver interface and the stand-in |
| `test_host/` | PC harness and tests |
| `platformio.ini` | Build for a generic ESP32-S3 board (`esp32-s3-devkitc-1`) |

No Arduino or ESP32 header is included by `lib/mnui`: the same code runs on the PC.

## Build and test

```bash
cd firmware/product-esp32s3
pio run                                  # ESP32-S3 image
pio run -t upload && pio device monitor  # flash, then watch the frame timings
```

```bash
cd tools/minimap_sim
python3 -m unittest test_firmware_parity -v     # firmware renderer vs the simulator (9 tests, about 30 s)
```

```bash
cd firmware/product-esp32s3                      # the C++ tests alone, under AddressSanitizer + UBSan
g++ -std=c++17 -g -O1 -Wall -Wextra -fsanitize=address,undefined -I lib/mnui -I ../../protocol \
    test_host/test_mnui.cpp lib/mnui/mn_*.cpp -o /tmp/test_mnui && /tmp/test_mnui [seed]
```

What the tests check:

* **Parity with the simulator**: the same 11 screens from the same raw BLE bytes (decoded by the real `nav_route.h`);
  mean difference under 3/255, under 1.2 % of pixels differing strongly, same layout chosen, same map zoom, same text
  widths (kerning included), a ride's zoom sequence unchanged frame by frame.
* **Rasteriser**: circle area within 0.4 %, half-covered pixels at 50 %, overlapping shapes unite, holes stay empty,
  winding direction does not matter, rendering in bands of any size gives the same pixels as one tall band, the clip
  works, pools overflow safely, wild and non-finite coordinates do not crash.
* **Random screens** (1,500 per seed, tried with 20 seeds): absurd distances, 20 wide characters, descenders, every
  manoeuvre code including undefined ones, odd route windows, stale or missing positions. No crash, no pool overflow, and
  **nothing drawn within 12 px of the circle's edge**. This found four real defects, now fixed in both the simulator and
  the firmware: a 32-bit overflow turning 4,294,967 km into "0 km", an unbounded remaining-time string, a status line
  wider than the circle, and street names whose first glyph reached the edge.
* **Deliberate breakage**: I broke the code in 13 ways (map mirrored, wrong rounding, wrong switch distance, no
  anti-aliasing, band not cleared, right arrow not mirrored, text fit ignoring descenders, clip ignored, kerning dropped,
  abbreviations skipped, no zoom hysteresis, no distance clamp, map padding changed) and checked each is caught. 12 were caught
  at once; the loss of vertical anti-aliasing slipped through, so a half-covered horizontal edge test was added and now catches it.

## Memory and time

| | |
|---|---|
| Display list | about 49 KB (9,000 points, 192 paths) |
| Edge scratch space | 30 KB static (used while drawing a band) |
| Band buffer | 13 KB |
| Total renderer RAM | about 95 KB of the 512 KB internal SRAM; the whole firmware shows 133 KB static |
| Largest real screen | about 2,000 points (22 % of the pool); the random screens stay below 8,000 |
| Render time, PC (-O2) | build 0.05 ms + draw 2.0 ms for the map screen |
| Render time, ESP32-S3 | **not measured**; estimate 60–100 ms. If it proves too slow: raise `kBandRows`, lower `kSub` to 4, or skip redraws when nothing changed |

## What is missing

1. **The panel driver.** I need, for the 1.45" 412 × 412 panel you pick:
   * the controller IC and its datasheet (the init command sequence, the column/row address commands, the pixel format
     and byte order, the maximum QSPI clock);
   * which interface it uses (QSPI, SPI, MIPI-DSI is not available on the S3);
   * the backlight arrangement (PWM pin into the boost converter) and the reset and tearing-effect pins.

   Then `src/panel_<name>.cpp` implements `Panel::begin / writeRect / setBrightness` (ESP-IDF `esp_lcd` with QSPI and DMA, or
   the controller's registers directly), and `panelCreate()` returns it. About 150 lines once the datasheet is known.
   The 12 px safe margin the layout assumes and the 36.8 mm active diameter come from the simulator's assumptions: replace
   them with the panel drawing's values.
2. **Brightness control.** The ambient-light sensor and the backlight PWM are in the hardware design but not in this firmware.
3. **Buttons.** The four buttons of the hardware design are not read.
4. **A real board definition** (`platformio.ini` targets a generic devkit; pins, flash size and PSRAM follow the PCB).
5. **Stale data.** If no NavCore arrives for 5 s the screen shows "Waiting for phone", even though Bluetooth is connected.

## Licence note (fonts)

The glyph outlines come from **Liberation Sans Bold** (SIL Open Font License 1.1, © 2010 Google Corporation, © 2012 Red Hat, Inc.).
The OFL permits embedding in firmware that is sold, if the licence text travels with it (`lib/mnui/OFL.txt`) and a modified
font does not use the reserved name. Have this checked when you prepare the product documentation; I am not giving legal advice.
