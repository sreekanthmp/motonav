# MotoNav — Product Plan & System Architecture

A handlebar-mounted motorcycle navigation display (Beeline Moto-style) that is driven by a phone app over Bluetooth LE.

> **Caveat:** I could not open the Beeline Moto page from the build sandbox (network block), so the
> competitor comparisons below come from general knowledge of this product category, not from the page.
> Paste its spec list and I'll align the requirements to it.

---

## 1. The one decision to make first: how "Google Maps" connects

There is **no public API that streams the Google Maps app's turn-by-turn guidance to a third-party Bluetooth device.** The realistic options:

| # | Approach | Works on | Verdict |
|---|----------|----------|---------|
| A | **Share-to-app**: rider finds a place in Google Maps → Share → MotoNav. App resolves the link to coordinates, routes with *your* engine, sends guidance to the device. | iOS + Android | **Recommended.** Legitimate, robust, no per-route Google cost. |
| B | **Google Places API** (autocomplete / details) inside your own app for search and POI data. | iOS + Android | **Recommended** alongside A. Check display/attribution terms. |
| C | **Google Navigation SDK** (Android/iOS): your app runs Google's navigation and you read its turn-by-turn feed to drive the device. | iOS + Android | **Recommended for the prototype** (see `PROTOTYPE_PLAN.md`). Google's docs show self-serve access (Cloud project + billing), a turn-by-turn feed for external displays, and good-quality two-wheeler directions in India. Priced per destination — check the India price list, and read the Maps Platform license restrictions before shipping a product. |
| D | **Google Routes/Directions API** as your router. | — | Risky. Google's terms have historically restricted using route data on non-Google displays and caching it. Get a legal read first. Also has no motorcycle/"twisty road" profile. |
| E | **Scrape the Google Maps notification** (Android `NotificationListenerService`). | Android only | Prototype hack only. Fragile, grey on ToS, impossible on iOS. Don't build a product on it. |

**Recommendation:** **prototype with C** (Navigation SDK) — it is the simplest path, because Google does routing, rerouting and map-matching. For a product, decide between C and **own routing on OpenStreetMap data (motorcycle-aware) + Google for search and import (A + B)** once you know the per-destination cost and the license terms. Keep A + B as Plan B.

Trade-off to accept: Google's traffic and POI data are the best available; OSM routing quality varies by region. If traffic matters, evaluate Mapbox / HERE / TomTom as a paid add-on rather than Google.

---

## 2. System architecture

```
┌──────────────────────── RIDER'S PHONE (iOS / Android) ─────────────────────────┐
│ UI: search, plan, saved routes, settings, pairing                               │
│ Share target: Google Maps link / geo: URI / GPX file                            │
│ Nav engine: GPS → map-match → next maneuver → NavState message                  │
│ BLE manager (background-safe)  │ Offline store (SQLite, tiles, routes)          │
│ OTA relay (firmware download → device)                                          │
└──────────┬───────────────────────────────────┬──────────────────────────────────┘
           │ BLE GATT (bonded, encrypted)      │ HTTPS
           ▼                                   ▼
┌── HANDLEBAR DEVICE ───────┐      ┌─────────────────────── CLOUD ───────────────────────┐
│ MCU + BLE (nRF52840/      │      │ API gateway → API service     │ Auth                  │
│  nRF5340 or ESP32-S3)     │      │ Routing (Valhalla, moto       │ Search/geocode        │
│ Sunlight-readable display │      │  costing) on OSM extracts     │ Firmware/OTA service  │
│ IMU + magnetometer + ALS  │      │ Tiles (PMTiles on S3 + CDN)   │ Analytics, crash logs │
│ (GNSS optional)           │      │ Postgres+PostGIS │ S3 (GPX,   │ Push (FCM/APNs)       │
│ Battery + USB-C, buttons  │      │  rides, firmware, tracks)     │ Billing (Stripe/IAP)  │
│ MCUboot DFU               │      └──────────────────────┬────────────────────────────────┘
└───────────────────────────┘                             │ (search / import only)
                                                          ▼
                                              Google Places API / Maps share links
```

Design principle: **the phone is the brain, the device is a rugged, low-power display.** All heavy work (maps, routing, search, rerouting) runs on the phone/cloud; the device only renders compact state messages. This keeps the BOM cheap and the battery life long.

### 2.1 Hardware

- **MCU:** Nordic nRF52840/nRF5340 (best BLE stack, low power, Zephyr support) or ESP32-S3 (cheaper, more RAM, worse power).
- **Display:** Sunlight readability and battery dominate. Options, in order of fit:
  1. Memory-in-pixel / transflective LCD (e.g. Sharp-style) — readable in sun, very low power, fast enough for speed/arrows.
  2. High-brightness IPS TFT — nicer colour, much worse battery.
  3. E-paper — superb battery, but refresh too slow for a live speed/turn display.
- **Sensors:** IMU (tilt, crash/fall detection later), magnetometer (compass), ambient-light sensor (auto-brightness). **Design the PCB with a GNSS module footprint (e.g. u-blox MAX-M10)** even if v1 firmware uses phone GPS — a board respin is far costlier than a populated-later part.
- **Power:** Li-ion/LiPo + PMIC, USB-C charging, optional 12 V hard-wire kit (needs a buck converter with automotive load-dump/TVS protection).
- **Mechanical:** IP67, vibration-hardened (motorcycle vibration kills connectors, crystals and solder joints), handlebar clamp mount with anti-theft option, glove-friendly buttons.
- **Certifications (long lead — start early):** FCC / CE-RED / UKCA, Bluetooth SIG listing, battery UN38.3, RoHS/REACH, plus IP and vibration/thermal test reports.

### 2.2 Firmware (Zephyr / nRF Connect SDK recommended)

Modules: BLE GATT peripheral · protocol decoder · UI renderer (LVGL or custom) · power manager · sensor drivers · settings (flash) · watchdog + crash log · **OTA via MCUboot + SMP over BLE** (signed images, rollback on failure).

### 2.3 BLE protocol (the contract between phone and device)

One custom GATT service. Keep messages small (≤ ~100 bytes after MTU negotiation) and versioned.

| Characteristic | Direction | Content |
|----------------|-----------|---------|
| `NavState` | phone → device (write w/o response, ~1 Hz + on change) | `seq`, `mode` (IDLE/NAV/OFF_ROUTE/REROUTING/ARRIVED), `maneuver` enum, `dist_to_maneuver_m`, `next_street` (≤24 B), `remaining_dist_m`, `eta`, `speed`, `heading`, `speed_limit`, `gps_quality` |
| `Route` (v2) | phone → device (chunked write) | Simplified polyline + maneuver list so the device can keep guiding if BLE drops |
| `DeviceStatus` | device → phone (notify) | battery, firmware version, light level, orientation, button events |
| `Config` | read/write | brightness, units, theme, screen layout |
| `DFU` | standard SMP | firmware update |

- Encode with nanopb/protobuf or a fixed binary layout. **Keep the `.proto`/spec in a shared `protocol/` folder that generates code for both firmware and app** — it is the single source of truth.
- **Security:** LE Secure Connections with bonding; passkey shown on the device display for first pairing; signed firmware images; app-level device identity to block clones.

### 2.4 Mobile app

- **Framework:** Flutter or React Native for UI (pick what your team knows), plus **thin native modules** for BLE and the navigation foreground service. Background reliability is the hard part and is easier to control natively.
- **Map rendering:** MapLibre (open-source) with vector tiles.
- **Key features by phase:**
  - MVP: pairing, search (Places), **Google Maps share-import**, route preview, start navigation → device, reroute, offline route cache, ride recording, device settings, firmware update.
  - v1.1: ride modes (fastest / scenic-twisty / avoid motorways / avoid unpaved), round-trip generator, GPX import/export, offline map regions.
  - v2: standalone on-device navigation from a preloaded route, crash alerts, community routes, premium subscription.
- **The hardest engineering in the whole product:** keeping location + BLE alive for hours with the screen locked and the phone in a pocket.
  - iOS: `location` + `bluetooth-central` background modes, Core Bluetooth state restoration.
  - Android: foreground service (location / connectedDevice types), battery-optimisation exemptions, Android 12+ BLE permissions, Android 14 FGS-type rules, OEM task-killers (Xiaomi, Samsung, OnePlus…).
  - Budget test time on many phone models; this is where most "it works on my desk" projects fail.

### 2.5 Google Maps link import (approach A in detail)

1. Register intent filters (Android: `ACTION_SEND text/plain`, `geo:` and Google Maps URL filters) and an iOS Share Extension.
2. Receive `maps.app.goo.gl/...` or `google.com/maps/...` text.
3. Backend resolves the short link (follow redirect) and extracts coordinates (`@lat,lng` / `!3d..!4d`) or a place ID.
4. If only a place ID/name is available, call Places Details for coordinates.
5. Hand off to the router → preview → start. Link formats change, so add monitoring and a graceful "couldn't read this link — search instead" fallback.

### 2.6 Backend (keep it small for MVP)

- **API:** NestJS / FastAPI / Go on containers (ECS/Cloud Run).
- **Auth:** Firebase Auth / Supabase / Cognito (don't build your own).
- **Data:** Postgres + PostGIS (users, devices, routes, rides); S3 for GPX, ride tracks, firmware binaries.
- **Routing:** self-hosted **Valhalla** (has a motorcycle costing model, produces turn narratives) on regional OSM extracts, refreshed weekly. Later: on-device routing for true offline.
- **Tiles:** PMTiles on S3 + CDN, or a hosted provider (MapTiler/Stadia) to start.
- **Search:** Google Places Autocomplete (best quality) or self-hosted Pelias/Photon as a cost-saver.
- **Ops:** firmware OTA manifest with staged rollout and rollback, Sentry, product analytics, Terraform, GitHub Actions CI/CD.

### 2.7 Runtime flow

```
Rider picks destination (search OR Google Maps share)
   → App calls /route → polyline + maneuvers
   → Rider taps Start → app starts foreground nav session
   → Every GPS fix: map-match to route → compute next maneuver + distance
   → Encode NavState → BLE write → device redraws arrow/distance/speed
   → Off-route? app reroutes, sends new state (mode = REROUTING → NAV)
   → Device button press → DeviceStatus notify → app (mute, next screen…)
```

---

## 3. Step-by-step plan

Timeline assumes a small focused team; treat as planning estimates, not commitments.

| Phase | Duration | Goal | Exit criteria |
|-------|----------|------|---------------|
| **0. Define & de-risk** | 2–4 wks | Decide what you're building and for whom. Rider interviews, teardown/benchmark of Beeline / TomTom / Garmin, **legal review of Google terms**, target price + BOM + margin, BLE protocol v0. | PRD, architecture sign-off, BOM target, protocol v0 |
| **1. Proof of concept (no custom hardware)** | 4–8 wks | Dev board (nRF52840-DK or ESP32-S3) + round LCD dev module + phone app: search → route → BLE → arrow on screen. Mount on a real bike. | Ride 30+ min with locked phone, no BLE drop-outs; Google link import works |
| **2. Alpha / EVT** | 3–5 months | Industrial design, schematic + PCB, first 10–20 prototypes (3D-printed/CNC enclosures), firmware alpha, app beta (TestFlight / Play internal), backend v1, OTA working. | Devices ride daily; OTA update proven |
| **3. Field beta / DVT** | 3–4 months | 50–100 beta riders. Waterproof, vibration, thermal and battery-life testing. **Start certifications.** Choose contract manufacturer, commit to tooling. Optional pre-order/crowdfunding campaign. | Pass environmental tests; certs filed; crash-free rate target met |
| **4. Pilot production / PVT** | 3–4 months | Pilot run (500–1,000 units), factory test jig + provisioning, logistics, support/RMA tooling, store release. | Yield and defect targets met; first customer shipments |
| **5. Scale & v2** | ongoing | On-device GNSS navigation, crash alerts, subscription, community routes, Navigation SDK evaluation. | — |

**Rough total to first shipped units: ~12–18 months.** Big-ticket costs to quote early: injection tooling, certification testing, and the first production inventory.

**Minimum team:** 1 embedded/firmware engineer · 1 hardware (EE) engineer or design house · 1 industrial/mechanical designer · 2 mobile engineers (one with strong native BLE/background experience) · 1 backend/DevOps · 1 product/UX designer · a contract manufacturer.

---

## 4. Top risks

1. **Google data/terms** — mitigated by share-import + own routing (§1).
2. **Background BLE + location reliability** — prototype in Phase 1, test on many phones.
3. **Display vs. battery vs. sunlight** — decide with real outdoor tests before PCB spin.
4. **Waterproofing + vibration** — test early with EVT units, not at DVT.
5. **Certification and supply-chain lead times** — start parts sourcing and cert planning in Phase 2.
6. **Map quality per region** — pick launch markets deliberately; benchmark OSM routing there.
7. **Rider safety/liability** — glanceable UI, nothing interactive while moving, clear disclaimers.
8. **Privacy** — location history is sensitive: GDPR/CCPA, data minimisation, local-first storage where possible.

---

## 5. Suggested repo layout

```
motonav/
  docs/        architecture, protocol spec, decisions (ADRs)
  protocol/    BLE schema (.proto) + generated code for firmware and app
  hardware/    KiCad schematics/PCB, enclosure CAD, BOM
  firmware/    Zephyr app, bootloader config, display UI
  app/         mobile app (Flutter/React Native + native BLE/nav modules)
  backend/     API, Valhalla config, Terraform
  tools/       fake-phone BLE client + GPX ride replayer (test firmware without a bike)
```

The **GPX ride replayer** is the best early investment: it lets firmware and app developers exercise the whole nav loop at a desk.
