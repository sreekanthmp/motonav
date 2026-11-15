# MotoNav — Hardware, Phone Connection & Turn-by-Turn Flow

Companion to `ARCHITECTURE.md`. Part numbers are **starting candidates** from general knowledge — check lifecycle, availability and price with distributors (Digi-Key / Mouser / LCSC) before committing, and replace the rough numbers below with measurements from your own prototypes.

---

## 1. Hardware plan

### 1.1 Block diagram

```
      Bike 12 V ──► [hardwire cable: fused 12V→5V automotive buck, potted]
                                   │ USB-C 5 V (or magnetic pogo)
                      ┌────────────▼─────────────┐
                      │ Charger/PMIC + fuel gauge│◄── LiPo 1000–1500 mAh (+ NTC)
                      └────────────┬─────────────┘
                                   │ 3.0–3.3 V
 ┌─────────────────────────────────▼────────────────────────────────────┐
 │ nRF52840 (BLE 5, Cortex-M4F)  — pre-certified module for EVT/DVT      │
 │  SPI  ── Display (MIP LCD or TFT) + frontlight driver                 │
 │  I²C  ── IMU · magnetometer · ambient-light · fuel gauge              │
 │  UART ── GNSS module (footprint on v1 board; populate for v2)         │
 │  QSPI ── 8–16 MB NOR flash (icons, fonts, OTA slot, ride log)         │
 │  GPIO ── 2–3 sealed buttons · status LED                              │
 │  I²C  ── (optional) secure element for device identity                │
 └───────────────────────────────────────────────────────────────────────┘
```

### 1.2 Recommended parts

| Block | MVP / EVT pick | Alternatives | Why / watch-outs |
|-------|----------------|--------------|------------------|
| **MCU + BLE** | **Nordic nRF52840**, in a **pre-certified module** (e.g. Raytac MDBT50Q or equivalent) | nRF54L15 (newer, lower power — use if a module and SDK support are mature when you start); ESP32-S3 (adds Wi-Fi/PSRAM, worse power); nRF5340 (overkill) | Best BLE stack + Zephyr/nRF Connect SDK. A pre-certified module reuses the vendor's radio approval, cutting cert cost/time; go chip-down only at volume. |
| **Display — PoC** | 1.28" round IPS TFT, GC9A01, 240×240 (on a dev board) | — | Cheap and quick for UI iteration. Too dim in direct sun and power-hungry — **not** a product choice by default. |
| **Display — product candidates** (choose by outdoor test, §1.5) | **(a)** Memory-in-pixel (MIP) LCD, e.g. JDI LPM013M126A 1.28" 8-colour or Sharp LS013B7DH03 128×128 mono | **(b)** High-brightness (≥800 nit) optically bonded TFT with AR coating; **(c)** small AMOLED | MIP = reflective/transflective, readable in sun, microwatts static, but few colours, needs a frontlight at night, few suppliers (single-source risk). TFT/AMOLED = vivid but battery-hungry. E-paper is too slow for a live speed/turn display. |
| **Frontlight** | White LED + PWM driver (MIP only) | — | Dim-to-dark for night; keep it off by default for battery. |
| **Flash** | 8–16 MB QSPI NOR (e.g. W25Q/MX25R class) | — | Stores icon/font assets and the **secondary OTA image slot** (internal 1 MB is too tight for dual-bank MCUboot). |
| **Battery** | 1-cell LiPo, 1000–1500 mAh, with protection circuit + NTC | LiFePO4 (better heat tolerance, lower energy density) | A handlebar in summer sun can exceed 60 °C; in winter it's below 0 °C. Inhibit charging outside ~0–45 °C. This is a real safety item. |
| **Charger / PMIC** | Nordic **nPM1300** (charger + bucks + fuel gauge) or TI BQ25185 | Discrete charger + MAX17048 fuel gauge | nPM1300 pairs naturally with nRF and saves parts. |
| **Power in** | **USB-C 5 V** on the device; ship an optional **hardwire cable** containing the fused 12 V→5 V buck | Magnetic pogo connector (better sealing; mind compass interference) | Keeping 12 V automotive hazards (load dump, reverse polarity, noise) **in the cable** simplifies the device and its certification. |
| **GNSS** (footprint v1, populate v2) | u-blox MAX-M10S (or SAM-M10Q with antenna) | Dual-band L1/L5 (e.g. u-blox F10 / Quectel LC29H) for city canyons | Phone GPS is fine for v1. Having your own GNSS gives speed/track/standalone nav and removes phone-GPS drift. Plan antenna placement early — plastic case, rider body and tank all affect it. |
| **IMU** | Bosch BMI270 or ST LSM6DSO | + high-g accelerometer (e.g. ADXL375) for crash detection (v2) | Wake-on-motion, auto-orientation, ride detection. Standard IMUs saturate around ±16 g — crash detection needs a high-g part. |
| **Magnetometer** | Bosch BMM350 or ST LIS2MDL | — | Handlebar compass is disturbed by engine/steel/mount magnets: calibrate, and prefer GNSS course-over-ground when moving. |
| **Ambient light** | VEML7700 or OPT3001 | — | Auto-brightness / auto day-night theme. |
| **Buttons** | 2–3 sealed silicone-dome tact switches, glove-friendly | Handlebar BLE remote (v2) | Avoid capacitive touch: unreliable with gloves and rain. |
| **Connector** | USB-C with silicone plug + ESD protection + 5.1 kΩ CC resistors | Magnetic pogo | Port sealing is a classic IP67 failure point. |
| **Sealing** | Gasket or ultrasonic weld, **ePTFE pressure-equalising vent**, hard-coated/AR lens, conformal-coated PCB | — | Without a vent, temperature swings pump moisture in → condensation behind the lens. |
| **Security** (optional) | ATECC608B secure element | MCU's built-in crypto + key storage | Device identity, anti-clone, signed OTA. |
| **Enclosure + mount** | PC/ABS or glass-filled nylon, UV-stable; handlebar/mirror-stem clamp with vibration isolation and optional lock | — | Vibration (5–200 Hz) kills solder joints, crystals and connectors — design for it from EVT. |

**Not needed:** vibration motor (you can't feel it on a bike), speaker/audio (voice prompts come from the phone to the rider's helmet headset over normal Bluetooth audio, independent of this device).

### 1.3 Rough power budget (estimates — measure on EVT units)

Assume ~1000 mAh usable.

| Scenario | Approx. average current | Approx. runtime |
|----------|------------------------|-----------------|
| BLE connected, MIP LCD, sensors, no frontlight, no GNSS | ~1–2 mA | ~3 weeks standby-style use |
| + night frontlight | ~5–15 mA | ~3–8 days of riding |
| + onboard GNSS continuous | ~10–25 mA extra | ~1.5–3 days of riding |
| Bright TFT/AMOLED backlight in daylight | ~30–60 mA | ~15–30 h |

Takeaway: **display choice dominates battery life**, GNSS is second.

### 1.4 Very rough cost framing

Expect a landed BOM in the tens of dollars at low volume, dominated by **display + enclosure/tooling amortisation + GNSS**. Replace this with real quotes in Phase 0; set the target as roughly one-quarter to one-third of the retail price.

### 1.5 Decide by experiment, not by datasheet

Run an **outdoor display A/B test** on real handlebars before the PCB spin:

- direct midday sun, shade, dusk and night;
- **with polarised sunglasses** and a **tinted visor** (polarised LCDs can go dark at some angles);
- mounted at realistic distance/angle; glove-operated buttons;
- −10 °C and +50 °C chamber/sun-soak, plus a rain/hose test.

---

## 2. How the device connects to the phone

**Roles:** device = BLE **peripheral** (GATT server). Phone app = BLE **central** (GATT client).

### 2.1 First-time pairing

```
Rider opens app → "Add device"
App scans for the MotoNav service UUID (and strongest RSSI)
Device (in pairing mode via button) advertises
App connects → device shows a 6-digit passkey on its screen
Rider types it into the phone's OS pairing dialog   ← LE Secure Connections
   (alternative: scan a QR code on the box/screen = out-of-band pairing, smoother + stronger)
Bond created (keys stored on both sides)
App writes HELLO { protocol version, app version, time, units, language }
Device replies { firmware version, display size, capabilities }
App stores device ID against the user's account
```

### 2.2 Reconnecting (every ride)

- Device advertises slowly (~1 s) when bonded and disconnected; uses resolvable private addresses (privacy) and a filter accept list (only bonded phone may connect).
- Android: `connectGatt(autoConnect=true)` inside a foreground service. iOS: pending `connect` + Core Bluetooth **state restoration** so the OS relaunches the app.
- Negotiate **ATT MTU (~185–247)** and data length extension; pick a connection interval of roughly 100–500 ms with slave latency — nav updates are 1 Hz so low rates save power.

### 2.3 GATT map

| Service / characteristic | Direction | Purpose |
|--------------------------|-----------|---------|
| Device Information (0x180A), Battery (0x180F) | read/notify | Standard — model, firmware, battery % |
| **MotoNav service** (custom 128-bit UUID) | | |
| ├ `Control` | phone → device (write with response) | HELLO, mode, brightness/units/theme, route start/stop |
| ├ `NavState` | phone → device (**write without response**) | Current guidance state, ~1 Hz and on change |
| ├ `Route` *(v2)* | phone → device (chunked) | Simplified polyline + maneuver list for offline continuity |
| └ `Events` | device → phone (notify) | Button presses, orientation, low battery, errors |
| SMP / DFU service | phone ↔ device | Signed firmware update (MCUboot) |

### 2.4 Message design rules

1. **Send full state, not deltas.** A dropped packet self-heals on the next update. No retries or ordering logic needed.
2. **Send enums, not images.** Maneuver icons live in device flash; the phone sends a number. This is tiny and reliable.
3. **Version everything** (`proto_ver` in HELLO) so old firmware and new apps can coexist.
4. **Heartbeat / staleness:** if no `NavState` for ~5 s the device shows "Phone disconnected" rather than a stale arrow — wrong guidance is worse than none.
5. **Device-side interpolation:** with 1 Hz updates at 100 km/h the rider moves ~28 m between packets, so the device should count the distance down itself (`dist = last_dist − speed × Δt`, clamped ≥ 0) and re-sync on each packet. This makes the display smooth and the turn timing feel right.

```
NavState (≤ ~100 bytes)
  seq            u16
  mode           enum { IDLE, NAV, OFF_ROUTE, REROUTING, ARRIVED }
  maneuver       enum (see §4.2)
  roundabout_exit u8, side u8
  dist_to_maneuver_m  u32
  next_street    utf8[≤24]
  remaining_dist_m u32, eta_epoch_s u32
  speed_kmh_x10  u16, heading_deg u16, speed_limit u8
  gps_quality    u8
```

---

## 3. End-to-end flow

```
SETUP (once)   pair + bond → HELLO → store device on account

PLAN           search (Places) or Google Maps "Share" → coordinates
               → router → route + maneuver list → preview on phone

START          app starts a foreground nav session (location + BLE kept alive)

RIDE LOOP      GPS fix (1 Hz)
(every second) → map-match to route → current segment + next maneuver
               → build NavState → BLE write → device redraws
               device interpolates distance between packets

OFF-ROUTE      matcher sees deviation → mode = OFF_ROUTE/REROUTING
               → new route (cloud or on-device) → mode = NAV, new maneuvers

DEVICE INPUT   button → Events notify → app (mute, next screen, dim)

ARRIVE / END   mode = ARRIVED → save ride (GPX) → sync to cloud

FAILURES       BLE drop    → device shows "reconnecting", app auto-reconnects
               app killed  → OS relaunches via state restoration / foreground service
               no data     → use cached route/tiles; reroute needs offline engine (§5)
```

Voice prompts: the phone's text-to-speech plays through the rider's helmet headset (normal Bluetooth audio). The device is visual-only.

---

## 4. Turn-by-turn from Google Maps

There is no public feed from the Google Maps *app*. Design the app so the **source of guidance is swappable**, and the device never knows which one is used.

### 4.1 One internal interface, several sources

```
          NavSource (interface) ──► canonical NavState ──► BLE ──► device
             ▲        ▲        ▲
   OwnRouter │  GoogleNav │  GpxReplay
  (Valhalla; │   SDK feed │  (test only)
   MVP)      │ (later, licensed)
```

| Source | What it is | When |
|--------|-----------|------|
| **OwnRouter** | Your routing engine (Valhalla, motorcycle costing) + your own map-matching produce the maneuvers. Destination can come from Google Maps **Share** or Google Places search. | **MVP.** This is what "Google Maps → device" means in practice. |
| **GoogleNav (Navigation SDK)** | Google's navigation runs inside *your* app; its turn-by-turn updates (current step, distance to step, remaining time/distance) are read and converted to NavState. Android and iOS both expose such an update feed — **confirm exact API names and eligibility in current docs**. | Later, only if you need Google's live traffic and accept licensing, cost and UI/attribution rules. Confirm with Google sales that a motorcycle-device use case is permitted. |
| **GpxReplay** | Replays a recorded ride. | Dev/testing — lets firmware be developed without a bike. |
| ~~Notification scraping~~ | Android `NotificationListenerService` reading Google Maps' ongoing notification text/icon. | Prototype only. Android-only, breaks when Google changes the notification, grey on ToS, requires a scary special permission, impossible on iOS. Don't ship it. |

### 4.2 Canonical maneuver enum (device-side icon set)

```
NONE, DEPART, STRAIGHT,
SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
UTURN_LEFT, UTURN_RIGHT, KEEP_LEFT, KEEP_RIGHT,
MERGE, RAMP_LEFT, RAMP_RIGHT,
ROUNDABOUT (+ exit number, + side), FERRY,
ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT
```

Each source has a small adapter that maps *its* maneuver types (Valhalla's numeric types, Google's maneuver names) onto this enum. Roundabouts and complex junctions are where most mapping bugs live — build a test suite from real routes.

### 4.3 Google Maps link import (the share path)

1. Register an Android intent filter (`ACTION_SEND text/plain`, `geo:`, Google Maps URLs) and an iOS Share Extension.
2. Receive `maps.app.goo.gl/...` or `google.com/maps/...`.
3. Backend follows the redirect and extracts coordinates (`@lat,lng`, `!3d…!4d…`) or a place ID; if only a place ID/name, call Places Details.
4. Route → preview → Start.
5. Link formats change: monitor failures and fall back to "Couldn't read this link — search instead".

---

## 5. The hardest parts (ranked)

| # | Problem | Why it's hard | Mitigation |
|---|---------|---------------|------------|
| 1 | **Background reliability on the phone** | iOS/Android kill background apps; BLE drops with phone in a pocket; Android OEMs (Xiaomi, Samsung, OnePlus…) are aggressive. Must run 3+ hours locked. | Foreground service / iOS background modes + state restoration from day one; test on 10+ phone models; build a "ride health" log. Prove in Phase 1. |
| 2 | **Offline routing & rerouting** | Riders go where there's no signal; a cloud-only router fails exactly when needed. | Cache the full route + maneuvers on phone (and later device); evaluate on-device routing (Valhalla / GraphHopper / BRouter) as a key differentiator. |
| 3 | **Display readability vs. battery** | Sun glare, polarised glasses/visors, night, heat, battery trade-offs. | Outdoor A/B test (§1.5) before the PCB spin; ambient-light auto-brightness. |
| 4 | **Mechanical: waterproof, vibration, heat/cold, UV** | Mostly iteration and test time; tooling is expensive and hard to change. | Test EVT units early; ePTFE vent; conformal coating; vibration-isolated mount; leave tooling decisions to DVT. |
| 5 | **Navigation correctness** | Map-matching at speed on twisty roads, GPS drift in tunnels/canyons, late/early turn alerts, roundabout exits. A wrong instruction destroys trust and is a safety issue. | Map-matching tuned for motorcycles; device interpolation; large replay test corpus (GPX); field beta. |
| 6 | **Google licensing / map data** | Terms restrict how Google data is shown/cached; Navigation SDK is licensed separately. | Own routing + share-import now; legal review; keep NavSource swappable. |
| 7 | **Battery safety & thermal** | Hot handlebars and cold mornings vs. Li-ion limits. | NTC + charge inhibit, conservative charge limits, thermal testing, UN38.3. |
| 8 | **Bricked devices from bad OTA** | Fatal for a hardware company with devices in the field. | MCUboot dual slots, signed images, automatic rollback, staged rollout, factory-recovery path. |
| 9 | **Certification & supply chain** | Long lead times; single-source displays. | Pre-certified RF module; second-source plans; order long-lead parts in Phase 2. |
