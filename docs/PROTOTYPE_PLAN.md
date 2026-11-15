# MotoNav — Prototype Plan (ESP32 + 0.96" OLED)

Phase 1 "proof of concept" from `ARCHITECTURE.md`, scoped to hardware you already have.

**Assumptions — correct me if wrong**
- ESP32 board: classic **ESP32-WROOM-32 DevKit** (S3 / C3 also work; only pin numbers change).
- Display: **0.96" SSD1306, 128×64, monochrome, 4-pin I2C** (GND, VCC, SCL, SDA). If yours has 7 pins it is SPI; if it is 1.3" it may be SH1106.
- Phone: **Android** (background behaviour is the hardest part and Android is >90% of the Indian market). iPhone support comes after the prototype proves the loop.

---

## 1. Goal and scope

**Goal:** prove the core loop end to end — *phone → Bluetooth LE → ESP32 → OLED shows turn arrow, distance and street* — and retire the three biggest risks cheaply:

1. Bluetooth stays connected and recovers on its own (phone locked, in a pocket, 30–60 min).
2. A destination shared from **Google Maps** becomes a route and live guidance.
3. Guidance feels correct on the move (turn timing, off-route, reroute).

**In scope:** ESP32 firmware, simple BLE protocol, desk simulator, Android app with Google Maps share-import, routing, ride test.
**Out of scope (later phases):** custom PCB, enclosure, battery/charging, OTA updates, pairing security, onboard GPS, offline maps, iPhone.

---

## 2. Hardware

| Item | Notes |
|------|-------|
| ESP32 DevKit + SSD1306 OLED | You have these |
| Jumper wires / breadboard, USB cable | Bench |
| **Power bank** (any) | Powers the board during ride tests |
| Small project box + handlebar phone-mount clamp (or zip ties) | For the ride test only |
| Optional: USB power meter (~₹500) | Rough current-draw measurements |
| Optional: 1–2 push buttons | Test device → phone events (the board's BOOT button works too) |
| Optional: nRF52840 dev kit | For the later ESP32-vs-nRF reconnect/power comparison |

**Wiring (classic ESP32):** OLED VCC → 3V3, GND → GND, SDA → GPIO21, SCL → GPIO22. I2C address is usually `0x3C`. On S3/C3 pick any two GPIOs and set them in config.

**Known limitation to expect:** a monochrome OLED is poor in direct sun. Test at dusk/shade and write down what you see — it feeds the display decision for the real product.

---

## 3. Prototype architecture

```
 Android app ──BLE──► ESP32 firmware ──I2C──► 128×64 OLED
  │  Google Maps Share → coordinates
  │  Router (Valhalla) → route + maneuvers
  │  GPS → match to route → next maneuver
  └─ NavCore + NavStreet messages (~1 Hz)

 Desk simulator (Web Bluetooth page + GPX replay) replaces the app for early work
```

### 3.1 Firmware (ESP32)
- **PlatformIO + Arduino framework**, **NimBLE-Arduino** (much lighter than the default Bluedroid stack), **U8g2** for the display.
- Modules: `ble` (GATT + auto re-advertise) · `proto` (decode, version check) · `nav` (state + distance interpolation + staleness) · `ui` (screens) · `config` (pins, names).
- **Screens:** Boot · Waiting for phone · NAV · Off-route/Rerouting · Arrived · Phone lost.
- **Arrow:** draw it procedurally (shaft + head rotated by turn angle) so you don't need 20 bitmaps on day one; roundabouts = circle + exit number.
- **Staleness:** no NavCore for ~5 s → show "Phone lost", never a stale arrow.
- **Interpolation:** between updates, count the distance down using speed so the number moves smoothly; re-sync on each packet.

OLED layout (128×64):

```
┌────────────────────────────┐
│ ┌──────┐  350 m            │   arrow ~48×48 (left)
│ │  ↱   │  MG Road          │   distance (large) + street (small)
│ └──────┘                   │
│ 42 km/h   ETA 18 min  4.2km│   status bar
└────────────────────────────┘
```

### 3.2 BLE protocol v0
Two characteristics, because the default BLE payload is only **20 bytes** unless the MTU is negotiated — keeping the hot path ≤ 20 bytes works on every phone and tool.

**Service:** custom 128-bit UUID (generate one; keep in `protocol/`).

| Characteristic | Direction | Content |
|----------------|-----------|---------|
| `NavCore` | phone → device, write w/o response, ~1 Hz | 18-byte packed little-endian struct (below) |
| `NavStreet` | phone → device, write | UTF-8 street name, ≤ 20 bytes, truncated by the phone; sent only when it changes |
| `Events` | device → phone, notify | button presses, battery/status (later) |

```
NavCore (18 bytes, little-endian)
  0  u8   version        = 1
  1  u8   mode           0 IDLE · 1 NAV · 2 OFF_ROUTE · 3 REROUTING · 4 ARRIVED
  2  u8   maneuver       see enum
  3  u8   roundabout_exit
  4  u16  seq
  6  u32  dist_to_maneuver_m
 10  u32  remaining_dist_m
 14  u16  eta_minutes
 16  u16  speed_kmh_x10
```

Maneuver enum: `NONE, DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN_LEFT, UTURN_RIGHT, KEEP_LEFT, KEEP_RIGHT, MERGE, RAMP_LEFT, RAMP_RIGHT, ROUNDABOUT, FERRY, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT`

Design rules (keep these even after the prototype): send **full state, not deltas** · send **enums, not images** · **version byte** in every message · **staleness timeout** on the device.

**India note:** street names may be in Devanagari/Tamil/etc., and the tiny OLED fonts won't render them. The phone should send an English/transliterated name (OSM `name:en` / `int_name`), ASCII only for the prototype.

### 3.3 Phone side — three stages

| Stage | Tool | Purpose |
|-------|------|---------|
| A | **nRF Connect** (free Android app) | Write raw hex bytes to the characteristics by hand. Proves OLED + BLE with zero phone code. |
| B | **Web Bluetooth page** (single HTML file, Chrome on Android) + **GPX replay** | Desk simulator: load a GPX, compute maneuvers, stream NavCore. Fast iteration without an app build. Foreground only; not available on iOS Safari. |
| C | **Android app** (Flutter recommended; Kotlin if you prefer native) | The real test: foreground service, BLE auto-reconnect, GPS, routing, Google Maps share-import. |

**Routing for the prototype:** Valhalla with the `motorcycle` costing model. Use a public demo server only for quick tests (rate-limited, not for production); self-host in Docker with a city or state OSM extract (Geofabrik) when you need reliability.

**Guidance logic (Stage C):** snap GPS to the route polyline (nearest segment within ~30 m) → distance along route to the next maneuver → send. Off-route if > ~50 m away for ~5 s → request a new route.

**Google Maps share-import:**
1. Android intent filter for `ACTION_SEND text/plain`.
2. Receive `maps.app.goo.gl/...` or `google.com/maps/...`.
3. Follow the redirect (HTTP) and parse `@lat,lng` or `!3d…!4d…`.
4. If no coordinates are present, show "couldn't read this link — search instead".
5. Route → preview → Start.

---

## 4. Milestones

Estimate: ~5–6 weeks part-time (10–15 h/week), 1–2 people.

| # | Time | What | Exit criteria |
|---|------|------|---------------|
| **M0** | 1 day | Wire OLED, run hello-world, draw arrow + text | Smooth redraw (10+ fps), I2C pins confirmed |
| **M1** | 2–3 days | BLE service + protocol v0; drive the screen from **nRF Connect** | Hex write → screen updates < 200 ms; device re-advertises after disconnect; "Phone lost" works |
| **M2** | 3–5 days | Web Bluetooth simulator + GPX replay; all maneuvers; interpolation | 20 km GPX replay shows correct turn timing and screens |
| **M3** | 1–2 weeks | Android app: Google Maps share → route → live guidance → BLE | Walk or drive a real route; correct calls; reroute < 10 s |
| **M4** | 1 week | **Ride test** (see below) | Targets in §5 met on ≥ 2 phones |
| **M5** | 2–3 days | Review and decide | Written results; go/no-go on custom hardware |

---

## 5. Test plan and targets

**Safety first:** test as a **passenger in a car** or on foot before riding — Bluetooth/GPS behaviour is identical. For the bike test, secure the mount, use a quiet road, and do not operate anything while moving.

**Run the ride test with the phone screen locked** (phone in pocket/tank bag). Testing with the screen on skips the hardest problem.

| Metric | Target |
|--------|--------|
| Packets delivered (seq gaps in log) over 30–60 min | ≥ 99% |
| Reconnect after forced drop (walk out of range / toggle Bluetooth) | < 10 s, no user action |
| GPS fix → screen update | < 500 ms |
| Turn call-outs on a 10 km route | all correct, sensible timing |
| Off-route → new guidance | < 10 s |
| Locked-screen run with background restrictions | survives 60 min on ≥ 2 Android makers (e.g. Samsung + Xiaomi/OnePlus) |
| Display readability | recorded at midday shade, dusk, night |
| Current draw (USB meter) | recorded, for battery estimates |

Log everything: ESP32 serial output, phone log with timestamps, seq numbers.

**Optional M5 comparison:** repeat the reconnect + power test on an nRF52840 kit running the same protocol; use the results to pick the production chip.

---

## 6. Repo layout for the prototype

```
motonav/
  protocol/                 spec + nav_protocol.h + constants (single source of truth)
  firmware/prototype-esp32/ PlatformIO project (platformio.ini, src/…)
  tools/web-sim/            Web Bluetooth page + GPX replay
  app/                      Android app (Stage C)
  docs/
```

---

## 7. Risks to watch

| Risk | Mitigation |
|------|-----------|
| Phone kills background app / drops BLE | Foreground service from day one; test on several Android makers; ride-health log |
| Default 20-byte BLE payload | Keep NavCore ≤ 20 bytes (done) |
| Public routing server limits | Self-host Valhalla (Docker) early |
| Google link formats change | Fallback to search; log failures |
| OLED unreadable in sun | Expected — record it, it informs the display choice |
| GPS drift / delay at speed | Interpolate on the device; log fix quality |
