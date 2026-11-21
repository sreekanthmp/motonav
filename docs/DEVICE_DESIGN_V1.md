# MotoNav device design v1: a Beeline-Moto-II-class product

> **Update, November 2026.** Decisions made since this was written; the parts list is now `hardware/bom_v2.csv`:
> - **Display:** round **IPS TFT 1.45" 412x412 (QSPI), 800+ nit, optically bonded**, instead of AMOLED. Bike clusters and Beeline use backlit TFT. AMOLED stays a test candidate. This adds a **backlight driver** to the board (§3.1 and §5 below assumed no backlight; the display's power draw must be re-measured).
> - **Screen content:** arrow, distance and street; distance left and ETA; a north-up route-line mini-map. No "then" manoeuvre, no compass, no heading-up rotation.
> - **No magnetometer** in v1.
> - **ESP32-S3-MINI-1:** the PSRAM variant is optional until the UI is built (same footprint as the 8 MB flash variant).
> - **Power from both battery and bike:** 12 V to 5 V stays in a separate potted cable; the device adds an input over-voltage protector; firmware cuts charge current when hot. The panel is only rated -20..+70 C, which a sun-baked handlebar can exceed.

This is the **hardware design specification** for the production-intent device: what to build, how the
parts fit together, and what an electronics engineer and a factory need to know. It is **not** a finished
schematic: a manufacturable PCB must be designed in KiCad (or similar) and reviewed by an experienced engineer.
Testing plans come later, as you decided.

Facts below are marked: **[V]** verified in a source I read (cited), **[S]** from a search summary,
**[?]** my engineering judgement or general knowledge, **verify against the datasheet before ordering**.
Companion files: `hardware/bom_v1.csv`, `hardware/io_budget_v1.csv`.

---

## 1. Benchmark and targets

Beeline Moto II, from [Beeline's support page and retailers](https://support.beeline.co/en/articles/9389532-technical-specifications) **[S]**:

| Item | Moto II | MotoNav v1 target |
|------|---------|-------------------|
| Size | 53 mm diameter × 20.5 mm deep | **≤ 55 mm × ≤ 21 mm** |
| Weight | 42 g (plastic) / 60 g (metal) | **≤ 60 g** |
| Display | 1.45" (36.8 mm) IPS TFT, 412×412 | 1.3–1.45" round, **see §3.1** |
| Battery | 600 mAh LiPo, up to 14 h in use, months of standby | **≥ 600 mAh, ≥ 14 h, ≥ 3 months standby** |
| Charging | USB-C + pogo pins for a powered mount | USB-C (service) + **pogo pins in the mount** |
| Water | IP67 | **IP67** |
| Link | Bluetooth LE; phone does the GPS | Bluetooth LE 5; phone GPS |
| Controls | 4 physical buttons | 4 sealed buttons |
| Sensors | accelerometer, gyroscope, magnetometer | IMU + magnetometer + ambient light |

---

## 2. System architecture

```
 BIKE 12 V ─► [hardwire cable: fuse + reverse protection + surge TVS + 12V→5V buck, potted] ─► 5 V ─┐
                                                                                                    ▼
                                                                            MOUNT (clamp) ── pogo pins ──┐
 USB-C (sealed, service/charging) ─► ESD ──────────────────────────────────────────────────────────────┐ │
                                                                                                        ▼ ▼
                                                              ┌─── 5 V in ───►  CHARGER + POWER PATH (I²C)  ◄──► 1S LiPo 600–700 mAh
                                                              │                         │ SYS                      (protection + NTC)
                                                              │                         ▼
                                                              │               BUCK-BOOST 3.3 V  ──► 1.8 V LDO (display I/O)
                                                              │                         │
   ┌──────────────────────────────────────────────────────────┴─────────────────────────┴────────────────────────────┐
   ▼                 ▼                 ▼               ▼                ▼               ▼                ▼
 ESP32-S3         ROUND DISPLAY      IMU          MAGNETOMETER    AMBIENT LIGHT    FUEL GAUGE       4 BUTTONS
 MODULE (BLE)     (QSPI or SPI)     (I²C)           (I²C)           (I²C)           (I²C)         (GPIO, sealed)
```

**Why the mount carries the bike power:** the device only ever sees clean 5 V through sealed pogo contacts; the
dangerous 12 V circuit lives in the cable. It also removes the need to open a USB port on a wet handlebar. This
is the same idea Beeline uses (USB-C plus pogo pins) **[S]**.

---

## 3. Key design decisions

### 3.1 Display (the decision that drives cost, power and readability)
| | **A. 1.43" round AMOLED 466×466 (QSPI, CO5300)** | **B. 1.45" round IPS TFT ~412×412 (Beeline-like)** |
|--|--|--|
| Brightness | One listing: **700 cd/m² typical** [S]; a 1.32" sibling lists 500 [S] | Standard GC9A01-class are 150–400; "ultra-bright" ones need custom panels [S] |
| Power with a **mostly black** UI | **Low** (only lit pixels draw) [?] | Backlight is always on [?] |
| Sourcing | Sold in small quantities as modules (e.g. [Panox TR143R2404A](https://www.panoxdisplay.com/amoled/143oledtouch.html), CO5300 driver [S]) | Custom high-brightness panels usually need large minimum orders [?] |
| Risk | Cost, burn-in (mitigate: dark UI, slow pixel shift, dim), sunlight must be confirmed | Sunlight readability, power |
| Firmware | **Already proven by an existing ESP32-S3 + AMOLED 1.43" dev board** (Waveshare, ₹3,390 in India) | Simple SPI driver |

**Recommendation: design v1 around A (AMOLED), keep B as the fallback.** Order the 1.43" Waveshare AMOLED board first:
it lets us port the firmware and *see the exact final display* before any custom PCB. Use the **non-touch** panel
(riders wear gloves; touch adds cost and noise). The AMOLED driver and rails differ from an IPS TFT, so the
PCB is designed for one choice, not both.

### 3.2 MCU: **ESP32-S3 module (ESP32-S3-MINI-1 class)** [?]
- Needs enough RAM and QSPI/LCD capability for a 466×466 panel; USB built in (GPIO19/20) so no USB-UART chip **[V]**.
- Use a module with PSRAM (full-frame buffers help; partial buffers also work) **[?]**.
- A **pre-certified module** avoids certifying your own radio; *conditions that keep the module's certificate valid
  (antenna keep-out etc.) must be read in the module datasheet; Espressif's design guide does not state them* **[V]**.

### 3.3 Power
- **Charger + power path:** TI [BQ25186](https://www.ti.com/document-viewer/lit/html/SLUSF69) (1 A, I²C, power path, ship mode) **[S]** or BQ25188 (WCSP). Ship mode gives the multi-month standby.
- **Main rail:** ESP32 needs **3.3 V with ≥ 500 mA** and ≥ 10 µF at the power entrance **[V]**. A **buck-boost** (TPS63802-class **[?]**) keeps 3.3 V all the way down the battery curve (an LDO wastes the last part of the battery).
- **Fuel gauge:** MAX17048-class **[?]**.
- **12 V parts live in the cable**, not the device.

### 3.4 Antenna and the plastic housing: the hardest integration rule
- Espressif: place the onboard PCB antenna **outside the base board edge** (or cut the board around it), and keep **at least 15 mm of clearance in all directions inside the housing** **[V]**.
- In a Ø53 mm round device with a battery (foil pouch) and a display, this is the main constraint: put the module at one edge of the PCB with the antenna pointing at a **plastic-only sector**, and the battery on the opposite side. A **metal** bezel/back (like Beeline's metal version) must stay away from that sector.
- Use a **plastic housing** for v1 (a metal version can follow after RF tests).

### 3.5 Round board and stack-up
```
 side view (not to scale)
   lens / cover glass ─────────────  ~1.0 mm
   display module (AMOLED+FPC) ────  ~2–3 mm
   MAIN PCB (4-layer) + ESP32-S3 ──  ~1.0 mm + 2.4 mm tallest part
   battery (e.g. 603035, ~6 mm) ───  beside/under PCB, away from antenna sector
   back housing + gasket + pogo ───  + O-ring, vent, mount interface
   total target ≤ 20.5 mm
```
- **4-layer** PCB: top signal/components, full ground, power, bottom signals **[V: recommended by Espressif]**.
- Round outline about **Ø44–47 mm** with flats for buttons; display connects by an **FPC connector**.

---

## 4. Rules from Espressif's design guide **[V]** (chip-level; the module datasheet governs module pins)

Source: [ESP32-S3 hardware design guidelines](https://docs.espressif.com/projects/esp-hardware-design-guidelines/en/latest/esp32s3/index.html)

| Topic | Rule |
|-------|------|
| Power | 3.3 V, ≥ 500 mA; ≥ 10 µF at the power entrance; 0.1 µF near digital supply pins; main 3.3 V traces ≥ 25 mil |
| Reset (EN) | RC of about 10 kΩ + 1 µF; EN high only after 3.3 V is stable; add a power monitor (~3.0 V threshold) if the supply is slow or unstable |
| Strapping pins | **GPIO0, GPIO3, GPIO45, GPIO46**: do not connect other circuits that change their boot levels; GPIO0 needs a pull-up and no large capacitor |
| USB | Native USB on **GPIO19 (D−) / GPIO20 (D+)**; reserve 22–33 Ω series resistors; **90 Ω** differential routing |
| UART0 | GPIO43 (TX) / GPIO44 (RX): keep for logs and download; 499 Ω series on TX |
| Download mode | GPIO0 low and GPIO46 low at reset release; plan a test pad/button for it |
| Flash/PSRAM pins | Not available for other functions; avoid them |
| Layers | 4-layer recommended; 2-layer allowed with a full ground plane |
| Antenna | Outside the board edge; ≥ 15 mm clear in the housing; keep crystal, USB, UART lines away |
| Pre-certification, module GPIO list, dimensions, temperature | **Not stated in the guide; read the module datasheet** |

---

## 5. Power budget (design targets, **to be measured**)

Battery 600 mAh. Assume ~80% usable ≈ 480 mAh. For **14 h** the average draw must be **≤ ~34 mA**.

| Block | Assumed average **[?]** |
|-------|-------------------------|
| ESP32-S3 + BLE connected, light sleep between updates | 8–15 mA |
| AMOLED, dark UI, moderate-high brightness | 12–25 mA |
| IMU, magnetometer, light sensor | ≤ 1 mA |
| Charger, gauge, regulators (quiescent) | ≤ 0.3 mA |
| **Total** | **~21–41 mA, so 12–23 h**, about the 14 h target |

Standby target: **< 100 µA** in ship mode / deep sleep for months of shelf life. These numbers must be replaced
by measurements on the Waveshare AMOLED board and then on the first custom board.

---

## 6. I/O budget

See `hardware/io_budget_v1.csv`. About **27 signals**: display (QSPI + reset + TE), I²C, five interrupt/status pins,
4 buttons, USB, UART0, boot/EN. The module's available GPIO count must be checked against its datasheet. If it
does not fit, use an I²C I/O expander for the buttons and slow signals.

---

## 7. Production-design checklist (for the PCB engineer / ODM)

**Electrical**
- ESD protection on USB, **pogo contacts and buttons** (they are exposed); surge/reverse protection on the 5 V mount input.
- Battery safety: protection board + NTC; charge only within a safe temperature window (hot handlebars, cold mornings).
- Magnetometer far from the battery, magnets in the mount and high current traces; plan calibration.
- Light sensor behind a light pipe/window.
- Programming/test pads for UART, USB and download mode; test points on every rail.

**Mechanical / sealing**
- IP67: gasket or ultrasonic weld, **pressure-equalising vent**, sealed buttons, sealed USB-C (or plug), potted pogo.
- UV-stable plastic; scratch-resistant lens (anti-glare coating); anti-fog.
- Mount: handlebar clamp and mirror-stem; pogo alignment; magnet/clip retention that holds on bumps.

**Manufacturing**
- DFM review with the assembler; panelisation, fiducials, reflow profile for the module and display connector.
- Factory test jig: powers up, flashes, checks rails, display, buttons, sensors, Bluetooth, writes serial number.
- Production security: secure boot and flash encryption (irreversible, see `HARDWARE_ROADMAP.md §K`), per-device identity, label with serial and MAC.

**Compliance** (see `GO_TO_MARKET_PLAN.md`): radio approval, battery registration, labelling.

---

## 8. Firmware and app consequences
- Port the display layer to the round AMOLED (colour, 466×466); redesign the UI: **black background, big arrow, high contrast, slow pixel shift**.
- Move to the ESP32-S3 target; later ESP-IDF for production security and OTA.
- Add OTA, battery gauge reading, buttons, IMU wake/sleep, ambient-light dimming, ship-mode handling.
- Pogo-power detection: show charging/powered state.

---

## 9. How to get from this document to a real board
1. **Buy** the Waveshare ESP32-S3 1.43" AMOLED board and the display-less test kit. Port and tune the firmware on the *exact* final display (I can do the firmware port).
2. **Hire or contract** an electronics engineer or design house. Give them this document, the two CSV files and the Moto II benchmark. Ask for: schematic, layout, 3D model, BOM with alternates, DFM report.
3. **Hire a mechanical designer** for the housing, gasket, vent, buttons and mount. Share the antenna-sector constraint early.
4. Get the schematic **reviewed** by a second engineer before ordering boards.
5. Order 5–10 boards, assemble, bring up block by block (shorts, rails, MCU, display, sensors, charging).

I can help with the firmware, the app, specs, review questions and checklists, but a manufacturable PCB needs an
engineer using real CAD tools and the actual part datasheets.
