# From prototype to product — hardware integration roadmap

The prototype works end to end: **Google Navigation SDK → Android app → Bluetooth LE → ESP32 → OLED**.
This document is the plan for the hardware side of turning it into a real product. It extends
`ARCHITECTURE.md` and `HARDWARE_AND_FLOW.md`.

Claims marked **(verify)** come from vendor blogs or search summaries and must be confirmed against the
official source before you spend money on them. Part numbers are starting candidates only.

---

## 1. What the prototype proved, and what it did not

| Proved | Not proved yet (this is the hardware work) |
|--------|--------------------------------------------|
| Navigation SDK feed works on a real phone (Samsung SM-G781B): state, maneuver, distances, ETA, street name | Readability in **direct sun**, with polarised sunglasses, with a tinted visor |
| BLE link: connect, write NavCore/NavStreet, display updates | **Battery life**, charging, heat and cold behaviour |
| Maneuver mapping Google → device; share-from-Google-Maps | **Locked-screen reliability** over hours on several phone brands |
| ESP32 + I2C OLED firmware, display driver switching | **Vibration, waterproofing** (IP67), UV, mount security |
| Protocol v1 | **Custom PCB**, enclosure, certification, manufacturing cost |
| | Google licence terms and per-ride cost at scale |

---

## 2. Decisions only you can make (they shape every part choice)

| Decision | Options | Default I will assume until you say otherwise |
|----------|---------|-----------------------------------------------|
| **Power** | Internal battery · bike 12 V · both | Internal Li-ion battery + USB-C, optional 12 V hardwire cable |
| **Display** | Mono/transflective · bright colour TFT · OLED | Decide by the sunlight test in §4.1 |
| **Own GPS in the device?** | No (phone GPS) · yes | **No** for v1 (the market leader's Moto II uses phone GPS, per search summaries) — but leave the footprint on the board |
| **Target price and first batch** | e.g. ₹ price, 100 / 500 / 1,000 units | Pre-order batch of ~100–500 |
| **First market** | India only · export later | India first |
| **Controls** | Buttons only · rotary · phone only | 2–4 glove-friendly sealed buttons |

---

## 3. Hardware workstreams

### A. Display and optics (biggest risk)
- The 0.96" **OLED will be hard to read in direct sun** — expect this from the test; it is a dev display only.
- Options to compare (vendor claims, **verify** with datasheets):
  - **Transflective / memory-in-pixel (MIP) LCD** — reflects sunlight instead of fighting it, very low power, but fewer colours. Candidates to test: JDI LPM013M126A, Sharp LS013B7DH03.
  - **High-brightness transmissive TFT** — sources suggest roughly **700–1000 nits for full sun**; costs much more power and heat. Candidate to prototype with: 1.28" round IPS (GC9A01 class), then a high-brightness bonded panel.
  - **Small AMOLED** — vivid, but sunlight power/readability needs testing.
- Also decide: size (arrow readable at arm's length), round vs rectangular, glass/lens coating (anti-glare, hard coat), anti-fog vent.

### B. MCU and Bluetooth
- Today: ESP32 (classic). Candidates for product: **ESP32-C3/C6** (cheap, matches a ~14 h battery target), **nRF52832/nRF54L** (lower power, mature BLE stack).
- Decide with data, not opinion: measure current draw and BLE reconnect reliability on both (see §4.2).
- Use a **pre-certified radio module** for the first batches (cheaper and faster than certifying your own antenna).

### C. Power and charging
- Single-cell Li-ion/LiPo (start with ~600–1000 mAh) + charger IC with **temperature (NTC) cutoff** — handlebars see sun-soak heat and cold mornings.
- USB-C charge input; optional **12 V → 5 V** hardwire cable (fused, automotive-protected buck inside the cable).
- Fuel gauge for battery %, IMU wake-on-motion for sleep.
- Targets: ≥ 14 h riding on a charge (market reference from search summaries), standby weeks.

### D. Sensors and controls
- IMU (auto-sleep/wake, orientation), ambient-light sensor (auto-brightness), optional magnetometer.
- Sealed silicone-dome buttons, glove-friendly. Avoid capacitive touch (rain and gloves).

### E. Mechanical, mount and sealing
- Handlebar/mirror-stem **mount**, vibration-isolated, optional lock. Start with 3D-printed → CNC → only then injection tooling (expensive to change).
- **IP67** target, **pressure-equalising vent**, conformal-coated PCB, UV-stable plastic, gasket or ultrasonic weld.
- Collect real handlebar diameters and mirror-stem dimensions for the bikes you target.

### F. PCB and assembly
- KiCad schematic + layout; design for assembly; test points; pogo-pin programming/test jig.
- Prototype boards from an online fab; decide later whether to assemble in India or overseas.

### G. Production firmware
- OTA updates with signed images and rollback, secure bonding, power management, ride log, factory-test mode. (Today's firmware is a prototype: no security, no OTA.)

### H. Compliance (India first) — verify every item
| Item | What the searches indicate |
|------|----------------------------|
| **WPC ETA** (radio) | DoT says Equipment Type Approval is required to import, sell and use wireless devices in de-licensed bands, and is permitted by **self-declaration** via the Saral Sanchar portal. Vendor blogs say 2–6 weeks **(verify)** and that a product must be exempt from import licensing under DGFT policy. |
| **BIS CRS** (batteries) | Lithium cells/batteries for portable use fall under **IS 16046 (Part 2)** under the Compulsory Registration Order; a Feb 2026 notice adds rated-capacity verification **(verify)**. Whether your **finished product** is also on the CRS list is **not settled** — check the Order's schedule/BIS product list or a consultant. |
| **Bluetooth SIG listing** | Needed to use the Bluetooth brand/qualify the product. |
| **Vibration** | Motorcycle mounts are commonly tested to **IEC 60068-2-64** (random vibration, annex for wheeled vehicles). One vendor report shows example levels — treat it as an example, not your requirement. |
| **Waterproofing** | IP67 per IEC 60529 (test at an accredited lab). |
| **EMC if wired to the bike** | Connecting to the vehicle electrical system may bring automotive EMC rules — **not researched yet; verify**. |
| **Google terms** | Confirm the Maps Platform terms allow showing Navigation SDK guidance on external hardware **in a product**; model the per-destination cost (India first 7,000/month free, then about $0.008 each). |

### I. Cost and manufacturing
- Build a **costed BOM** (use real distributor prices at 100 / 1k / 10k) before choosing parts.
- Biggest cost drivers: display, enclosure and tooling, battery, radio module, test and certification.
- Plan a pilot run with an EMS partner; design a factory test jig and device-provisioning step.

---

## 4. Experiments to run first (cheap, highest value)

### 4.1 Sunlight display shootout (1–2 weeks)
Buy 3–4 candidate displays and the same test pattern (arrow, distance, street). In **direct midday sun**, shade, dusk and night, **with polarised sunglasses and a tinted visor**, on a real handlebar. Record photos and a 1–5 readability score. Output: chosen display technology.

### 4.2 Power profiling (1 week)
Measure current of the ESP32 and an nRF52/nRF54 kit with BLE connected, display on and off, at 1 Hz updates (USB power meter, or Nordic Power Profiler Kit II). Output: battery-life model per option.

### 4.3 Locked-screen reliability (2 weeks, parallel)
Run navigation with the **screen locked for 60+ minutes** on at least 3 phone brands (Samsung, Xiaomi, OnePlus/Realme), battery optimisation on and off. Count Bluetooth drops and missed updates. Output: required permissions/settings and a go/no-go on the Android background design.

### 4.4 Ride and vibration test (1 week)
Mount the prototype on a bike (passenger/safe road). Check display visibility, connector and wire reliability, mount stability, heat after 1 hour in sun.

### 4.5 Teardown and benchmarking (1 week)
Buy one **Beeline Moto II** (and ideally one other device), photograph the PCB, read chip markings, measure battery capacity and weight, check the FCC ID filing for internal photos. Output: real chipset, BOM clues, target specs.

### 4.6 Google cost and terms (1 week)
Read the Maps Platform terms; log billed requests across a week of use; compute cost per ride and a pricing model.

---

## 5. Phases and gates

| Phase | Time | Output | Gate to continue |
|-------|------|--------|------------------|
| **1. Research and experiments** | 4–6 weeks | §4 results, display and MCU decision, costed BOM, target spec | Spec and price target agreed |
| **2. EVT — first custom board** | 2–3 months | Custom PCB with chosen MCU/display/battery/IMU, 3D-printed enclosure, 5–10 units | Runs a full day on a bike |
| **3. DVT — rugged prototype** | 2–3 months | CNC/pre-tooling enclosure, IP67 and vibration tests, pre-certification checks, 30–50 beta units | Passes environmental tests |
| **4. Certification and pilot production** | 2–3 months | WPC ETA, battery registration, Bluetooth listing, tooling, 100–500 unit pilot, factory test jig | Yield and defect targets met |
| **5. Launch** | ongoing | Sales, support, OTA, app on the Play Store | — |

Rough total: **9–15 months** for a small team. Treat this as an estimate, not a commitment.

---

## 6. Suggested team and skills

Embedded firmware · hardware/PCB engineer · industrial/mechanical designer · Android developer · one person
who owns compliance and manufacturing. Much of this can be contracted (design house, EMS partner, test lab).

---

## 7. Next steps in this repo

1. Add `hardware/` with a **BOM and cost spreadsheet**, an **experiment log template** (§4), and a **block diagram**.
2. Firmware: add a **display abstraction** so panels can be swapped, a **battery/power-measurement mode**, and a **stress-test mode** (long-run BLE counters).
3. App: show **Bluetooth health stats** (drops, packet gaps) for the reliability tests.
4. Start a KiCad project once the display, MCU and power decisions are made.

---

## Sources (search summaries; verify before relying)

- DoT, [Equipment Type Approval (ETA)](https://eservices.dot.gov.in/equipment-type-approval-eta)
- TÜV, [India WPC ETA](https://www.tuv.com/market-access-services/en/certification-filter/wpc-approval.html) · [Granite River Labs: WPC and ETA guide](https://www.graniteriverlabs.com/en-us/market-access-services/country/india/wpc-mark)
- BIS, [Scheme II registration (CRS)](https://www.bis.gov.in/product-certification/products-under-compulsory-certification/scheme-ii-registration-scheme) · [Nemko: BIS CRS](https://www.nemko.com/product-certification/bis-compulsory-registration-scheme)
- [IEC 60068-2-64 preview](https://webstore.ansi.org/preview-pages/IEC/preview_iec60068-2-64%7Bed3.0%7Den.pdf) · [CLIQR vibration test report (example)](https://www.cyclegear.com/assets/0006/3559/CLIQR_Vibration_Test_Report.pdf)
- [Focus LCDs: transflective vs sunlight-readable displays](https://focuslcds.com/application-notes/comparing-transflective-and-sunlight-readable-displays/)
