# MotoNav — Simple Prototype Plan
**ESP32 DevKit V1 + 0.96" I2C OLED + Android app using the Google Navigation SDK**

Supersedes the earlier "own routing" prototype plan. The own-routing path (Valhalla + map-matching + Google link parsing) is kept as **Plan B** in `ARCHITECTURE.md`.

---

## 1. What we are building

```
 ┌──────────── Android app (Kotlin) ────────────┐        ┌──── ESP32 DevKit V1 ────┐
 │ Google Navigation SDK                        │  BLE   │ BLE server (NimBLE)     │
 │   routing · reroute · map-matching · traffic │ ─────► │ proto decode            │
 │ Turn-by-turn feed (NavInfo, ~1/sec)          │        │ UI → SSD1306 OLED (I2C) │
 │ NavInfo → NavCore/NavStreet mapper           │        └─────────────────────────┘
 │ BLE client + minimal UI                      │
 └──────────────────────────────────────────────┘
```

**Why this is simple:** Google's SDK does the hard navigation work (routing, rerouting, map-matching, traffic). Our app only *listens* to its turn-by-turn feed and forwards a tiny message over Bluetooth. The ESP32 only draws.

**Dropped from the earlier plan:** routing server, map-matching, off-route logic, Google link parsing, desk web simulator (the SDK's built-in route simulator replaces it).

---

## 2. What Google's docs confirm (and what they don't)

Read from `developers.google.com/maps/documentation/navigation/android-sdk/` on 2026-11-15. Summaries of the docs — re-check the pages when you start building.

| Topic | Finding |
|-------|---------|
| **Access** | Self-serve: Google Cloud project with billing + API key. No sales approval or allowlisting is mentioned. Users must accept the Google Navigation Terms of Service in the app. |
| **Turn-by-turn feed** | `navigator.registerServiceForNavUpdates(...)` → the SDK sends `NavInfo`/`StepInfo` to your `Service` through a `Messenger`, **about once a second**. Documented for "devices not designed for map-based navigation guidance". |
| **Data** | `NavInfo.navState` (ENROUTE / REROUTING / STOPPED), per-step `Maneuver` enum, road name, distance to next step, remaining time and distance, lane guidance. |
| **India / motorcycles** | Coverage table lists **Two-Wheeled Vehicle Directions as good quality in India**. `RoutingOptions.TravelMode.TWO_WHEELER` exists. |
| **Testing** | `navigator.getSimulator().simulateLocationsAlongExistingRoute(...)` moves along the route without leaving your desk. |
| **Requirements** | SDK 7.0.0+: target API 36, min API 24; device with Google Play services, ≥ 2 GB RAM. You **cannot use the Navigation SDK and Maps SDK in the same app**. |
| **Pricing** | Pay-as-you-go, charged **per destination (waypoint)** on route requests. Starting guidance and reroutes are not charged extra. **Dollar amounts and any free tier/trial were not read** — check the [India price list](https://developers.google.com/maps/billing-and-pricing/pricing-india#navigation-sdk-pricing) before relying on it. |

**Not confirmed — verify early:**
- Whether **sending Google navigation data to external hardware** is allowed for a *product*. The docs pages read don't say; the Maps Platform Terms "License Restrictions" section was not read. Fine to prototype; read it before shipping.
- **Screen-off / locked-phone behaviour** of the feed (docs don't state it). This is test #1 in M5.
- Exact getter names for distance, time and road name — use the API reference.
- Whether `Waypoint.Builder` accepts lat/lng (the pages read only show `setPlaceIdString`). If not, use Places autocomplete to get place IDs.

---

## 3. Parts and accounts checklist

**Hardware (you have):** ESP32 DevKit V1 + 4-pin OLED. Add a USB cable and a power bank for ride tests, a small box and a handlebar mount later.
**Wiring:** OLED VCC → 3V3, GND → GND, SDA → GPIO21, SCL → GPIO22 (I2C address usually `0x3C`).
**PC:** USB driver for the board (CP210x or CH340), PlatformIO (VS Code), Android Studio.
**Phone:** a physical Android phone with Google Play services (BLE doesn't work on an emulator).

**Google Cloud (one-time):**
1. Create a project, **enable billing**.
2. Enable **Navigation SDK for Android** (`navigationsdk.googleapis.com`).
3. Create an API key restricted to that API (the docs warn you are financially responsible for abuse of unrestricted keys); also restrict to your app's package name + SHA-1.
4. Set a **budget alert and a quota cap**.
5. Add Google's required attribution/licensing text and the Terms-of-Service prompt to the app.

---

## 4. Data mapping: Google → device

`NavInfo` / `StepInfo` → **NavCore** (18 B) + **NavStreet** (≤ 20 B).

| Device field | From Google |
|--------------|-------------|
| `mode` | `navState`: ENROUTE → NAV, REROUTING → REROUTING, STOPPED → IDLE; `ArrivalListener.onArrival` → ARRIVED |
| `maneuver` | `StepInfo.getManeuver()` mapped as below |
| `dist_to_maneuver_m` | distance to next step (from `NavInfo`) |
| `remaining_dist_m`, `eta_minutes` | trip remaining distance/time |
| `speed_kmh_x10` | **not in `NavInfo`** — take from the road-snapped `Location` (`RoadSnappedLocationProvider`) |
| `NavStreet` | step road name, transliterated to ASCII/English for the OLED, truncated to 20 bytes |

| Google `Maneuver` (partial list from docs) | Device |
|--------------------------------------------|--------|
| DEPART / STRAIGHT | DEPART / STRAIGHT |
| TURN_LEFT / TURN_RIGHT | LEFT / RIGHT |
| TURN_SLIGHT_* / TURN_SHARP_* | SLIGHT_* / SHARP_* |
| TURN_U_TURN_CLOCKWISE (and counter-clockwise) | UTURN_RIGHT / UTURN_LEFT |
| ROUNDABOUT_* | ROUNDABOUT (+ exit number if the variant gives it) |
| MERGE_*, FORK_*, ON_RAMP_*, OFF_RAMP_* | MERGE, KEEP_LEFT/RIGHT, RAMP_LEFT/RIGHT |
| DESTINATION, DESTINATION_LEFT/RIGHT | ARRIVE, ARRIVE_LEFT/RIGHT |
| FERRY_BOAT | FERRY |
| UNKNOWN | NONE |

Confirm the complete enum in the API reference; write unit tests for the mapper.

---

## 5. BLE protocol v0 (unchanged)

Service with a custom 128-bit UUID. Default BLE payload is 20 bytes, so the hot path stays ≤ 20 bytes.

| Characteristic | Direction | Content |
|----------------|-----------|---------|
| `NavCore` | phone → device, write w/o response, ~1 Hz | 18-byte struct (below) |
| `NavStreet` | phone → device, write | UTF-8 name ≤ 20 B, only when it changes |
| `Events` | device → phone, notify | buttons / status (later) |

```
NavCore (18 bytes, little-endian)
  0 u8 version=1 | 1 u8 mode (0 IDLE,1 NAV,2 OFF_ROUTE,3 REROUTING,4 ARRIVED)
  2 u8 maneuver  | 3 u8 roundabout_exit | 4 u16 seq
  6 u32 dist_to_maneuver_m | 10 u32 remaining_dist_m
 14 u16 eta_minutes | 16 u16 speed_kmh_x10
```

Maneuver enum: `NONE, DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN_LEFT, UTURN_RIGHT, KEEP_LEFT, KEEP_RIGHT, MERGE, RAMP_LEFT, RAMP_RIGHT, ROUNDABOUT, FERRY, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT`

Rules: **send full state, not deltas** · **enums, not images** · **version byte** · device shows **"Phone lost"** if no NavCore for ~5 s · device **interpolates distance** between packets using speed.

OLED layout (128×64): arrow ~48×48 on the left (drawn procedurally, rotated by turn angle), distance in a large font + street name on the right, speed/ETA/remaining distance on the bottom line.

---

## 6. Milestones (~3–4 weeks part-time)

| # | Time | What | Exit criteria |
|---|------|------|---------------|
| **M0** | 1 day | Wire OLED, PlatformIO hello-world, draw arrow + text | Smooth redraw, pins confirmed |
| **M1** | 2–3 days | ESP32 BLE server + protocol v0; drive the screen with the **nRF Connect** app (hex writes) | Write → screen < 200 ms; re-advertises after disconnect; "Phone lost" works |
| **M2** | 2–3 days | Android project: Cloud setup, Navigation SDK sample, hard-coded destination, **simulator**; log `NavInfo` to Logcat | Maneuver, distance, road name and nav state appear about once a second |
| **M3** | 3–4 days | Add BLE client; mapper `NavInfo → NavCore/NavStreet`; stream to the ESP32 along a simulated route | OLED follows the simulated route at your desk, including a forced reroute |
| **M4** | 3–5 days | Destination picker (Places autocomplete or fixed list), foreground service, BLE auto-reconnect, connection status UI | Survives disconnect/reconnect without touching the phone |
| **M5** | 1 week | Real-world test: car passenger first, then bike; **phone screen locked** | Targets in §7 met; written go/no-go |

---

## 7. Test targets (M5)

Safety: test as a **passenger in a car** or on foot first; on the bike use a quiet road, a secure mount, and don't operate anything while moving.

| Metric | Target |
|--------|--------|
| Does the TBT feed keep flowing with the **screen locked** for 60 min? | Yes, on ≥ 2 Android makers (e.g. Samsung + Xiaomi/OnePlus) |
| Packets delivered over 30–60 min (seq gaps) | ≥ 99% |
| Reconnect after forced drop | < 10 s, no user action |
| Nav SDK update → OLED | < 500 ms |
| Turn call-outs on a real 10 km route | All correct, sensible timing |
| Reroute shows REROUTING then new guidance | < 10 s |
| OLED readability | Recorded at midday shade, dusk, night (expect poor in direct sun) |
| Cost of the test (Google billing) | Recorded — gives a real per-ride figure |

---

## 8. Risks and Plan B

| Risk | Mitigation |
|------|-----------|
| Google terms may not allow external-device display in a product | Read Maps Platform Terms "License Restrictions" before building further; **Plan B = own routing** (see `ARCHITECTURE.md`) |
| Per-destination cost at scale | Check India price list; model cost per ride vs. subscription price |
| Feed stops with screen off / app backgrounded | Foreground service from M4; test in M5; Plan B if unfixable |
| Can't combine with Maps SDK | Use the Navigation SDK's own navigation view only |
| Android-only for now | Navigation SDK also has an iOS version (not verified here); revisit after the prototype |

## 9. Out of scope for the prototype
Custom PCB, enclosure, battery/charging, OTA updates, pairing security, onboard GPS, offline maps, iPhone, Google Maps link import.
