# MotoNav hardware guide for beginners: parts, small PCB, and costs

Decisions: **power = battery + bike (both)**, **market = India only**, **display = decide after a sunlight test**.

> Prices below are **rough estimates from general knowledge, not quotes**. Check real prices on LCSC,
> Mouser/Digi-Key India, Robu and the display vendors before deciding. Part numbers are **candidates**
> to read the datasheet of, not final choices. Marked **(verify)** = confirm first.

---

## 1. Words you will meet

| Word | Plain meaning |
|------|---------------|
| **PCB** | The green board; copper tracks connect the parts |
| **Schematic** | The wiring *diagram* (which pin connects to which) |
| **Layout** | The physical board drawing made from the schematic |
| **Rail** | A power line, e.g. the 3.3 V rail powers the chip |
| **Regulator (LDO / buck)** | Turns one voltage into another. *LDO* is simple but wastes the difference as heat; *buck* is efficient |
| **Power path** | Circuit that runs the device from USB/bike **and** charges the battery at the same time |
| **I²C / SPI** | Two short "data cables" between the chip and display/sensors (I²C: 2 wires; SPI: 4+ wires, faster) |
| **Decoupling capacitor** | Tiny capacitor next to a chip's power pin; keeps the voltage steady. Every datasheet tells you where |
| **Footprint** | The copper pad shape for one part |
| **Gerber files** | The files you send to the PCB factory |
| **BOM** | Bill of materials: the list of parts and prices |

---

## 2. The whole device as blocks

```
 BIKE 12 V ──[fuse]──[reverse-polarity protection]──[surge protection]──[12V→5V buck]──┐
                                                                                        ├─► 5 V
 USB-C plug ──[ESD protection]─────────────────────────────────────────────────────────┘     │
                                                                                             ▼
                                              ┌──────────────────────────────────  CHARGER + POWER-PATH IC
                                              │                                         │          │
                                         LI-ION BATTERY ◄──── (charges) ────────────────┘          │ system rail
                                       (with protection + temp sensor)                              ▼
                                                                                       3.3 V REGULATOR
                                                                                                    │
        ┌──────────────┬──────────────┬─────────────────┬──────────────┬───────────────┐
        ▼              ▼              ▼                 ▼              ▼               ▼
   MCU + Bluetooth   DISPLAY      IMU (motion)    LIGHT SENSOR     BUTTONS       BATTERY GAUGE
   (ESP32 module)   (SPI/I²C)      (I²C)            (I²C)        (2–4 sealed)       (I²C)
```

**How "power = both" works:** when the bike or USB is connected, the device runs from it and charges the
battery. When it is not, the device runs from the battery. The *power-path IC* manages that for you.

---

## 3. Parts you need, block by block

### 3.1 Brain: MCU + Bluetooth
- Use an **ESP32 module**, not a bare chip. A module is a tiny pre-built board with the chip, flash memory,
  antenna and **radio certification already done**. You solder it like a component.
- Candidate: **ESP32-C3-MINI-1** (cheap, Bluetooth LE, built-in USB so you need **no USB-to-serial chip**).
  **ESP32-S3-MINI-1** if you later want a colour display with animations.
- Alternative: an **nRF52/nRF54 module** if the power test shows ESP32 drinks too much.
- Your current firmware needs only a small port (pin numbers, boot button, USB).

### 3.2 Display (undecided — design so it can be swapped)
- Run the **sunlight test** (see HARDWARE_ROADMAP §4.1) before choosing.
- On the first custom board, put a **display connector** (header sockets or an FPC connector) instead of
  soldering one display forever. That lets you try several.
- Candidates to test: transflective/MIP LCD (JDI LPM013M126A, Sharp LS013B7DH03 — **verify**), a
  1.28" round IPS (GC9A01 class), a high-brightness panel.

### 3.3 Power block
| Part | Job | Candidate (verify) |
|------|-----|--------------------|
| **Li-ion/LiPo cell**, 1S, ~1000–2000 mAh | Stores energy. Must have a **protection board** and a **temperature sensor (NTC)** | Any quality 1S pack with PCM |
| **Charger + power-path IC** | Charges safely, runs the device from USB while charging, stops charging when too hot/cold | TI BQ24074 (a common choice); simpler MCP73831 lacks a power path |
| **3.3 V regulator** | Makes the 3.3 V the chip needs from the 3.0–4.2 V battery | Easy: LDO like AP2112K-3.3 (wastes some battery). Better: buck-boost like TPS63001/TPS63802 |
| **12 V → 5 V buck** + **fuse** + **reverse-polarity** + **TVS surge protection** | Takes bike power safely (bikes have noisy voltage and spikes) | Automotive-rated buck IC; TVS diode; P-MOSFET polarity protection |
| **Battery gauge** | Reports battery % | MAX17048 |
| **USB-C connector** + ESD chip + two 5.1 kΩ resistors | Charging/programming | USBLC6-2SC6 |

**Strong advice:** put the **12 V → 5 V converter, fuse and surge protection inside the bike cable** (a small
potted "hardwire kit"), so the device itself only ever sees clean 5 V from a USB-C-style input. It keeps the
dangerous part away from your main board and makes certification easier.

### 3.4 Sensors and buttons
| Part | Job | Candidate (verify) |
|------|-----|--------------------|
| **IMU** (motion sensor) | Wake/sleep when the bike moves; orientation | BMI270 or LSM6DSO |
| **Light sensor** | Auto-brightness | VEML7700 or OPT3001 |
| **Buttons** (2–4) | Glove-friendly controls | Sealed silicone-dome tact switches |
| **Optional GPS** | Later: speed/track without the phone | u-blox MAX-M10S footprint, left unpopulated |

### 3.5 Small parts you will see on every board
Capacitors (decoupling), resistors (pull-ups, USB), LEDs, a connector for the battery (JST), test pads for
programming/measuring. Copy the **typical application circuit** from each chip's datasheet.

---

## 4. How the parts become one small PCB (what the process looks like)

1. **Block diagram** (done above).
2. **Pick each chip and read its datasheet's "typical application circuit"** and the vendor's *hardware design
   guidelines* (Espressif publishes one for each ESP32 module). You mostly copy proven circuits.
3. **Draw the schematic** in **KiCad** (free). Wire power, I²C, SPI, buttons, USB.
4. **Assign a footprint to every part**, run the **electrical rules check**.
5. **Lay out the board:**
   - decoupling capacitors **right next to** each chip's power pin;
   - keep the ESP32 module's **antenna at the board edge with copper-free space around it** (the datasheet shows exactly where);
   - solid **ground** under/around everything else;
   - wide tracks for battery and charging currents; keep noisy switching parts (buck) away from the antenna and sensors.
6. **Design-rule check**, then export **Gerber files, BOM and pick-and-place file**.
7. **Order 5–10 boards** with machine assembly from an online fab (e.g. JLCPCB/PCBWay with LCSC parts).
8. **Bring-up**, in order: check for shorts → power the board **without** the MCU running → check each rail with
   a multimeter → flash firmware → test each block one at a time.

**Size:** a board around **35–45 mm across** is realistic for a round/square handlebar display.

**Beginner-safe path:**
- **Stage 0 (now):** still on breadboards. Add a battery + charger module, IMU and light sensor to the working
  prototype; measure current with a USB power meter.
- **Stage 1:** first custom board = ESP32 module + display connector + USB-C + charger + regulator + IMU + buttons.
  **Skip the 12 V circuit** on this board (use the separate hardwire cable).
- **Stage 2:** have an **experienced engineer review your schematic and layout** before ordering. For a first
  board this is cheap insurance, especially for the battery charging and the antenna area.
- **Stage 3:** rugged version after environmental tests.

**Learning resources:** KiCad's official "Getting Started"; Espressif's ESP32-C3 *Hardware Design Guidelines*;
the datasheet of every part you use. Watch full "design a board from scratch in KiCad" tutorials before starting.

---

## 5. Safety for a beginner (Li-ion batteries are not toys)
- Never use a bare cell. Use a pack with a **protection circuit**.
- Never short the pins, puncture it, or charge it unattended while testing.
- Charge only through a proper charger IC with **temperature cutoff** (hot handlebars, cold mornings).
- Fuse the bike connection **close to the battery terminal**.
- Hot parts or a swollen battery: unplug and stop.

---

## 6. What to buy first (before any custom PCB)

| Item | Why | Rough price in India (estimate) |
|------|-----|---------------------------------|
| **Multimeter** | Check voltages, find shorts | ₹1,000–2,500 |
| **Soldering iron** (temperature controlled) + solder + flux | Solder headers and wires | ₹1,500–4,000 |
| **USB-C power meter** | Measure the current the prototype draws | ₹500–1,000 |
| **Logic analyser** (cheap) | See what the I²C/SPI wires are doing | ₹500–1,200 |
| **ESP32-C3 dev board** | Test the port to the likely product chip | ₹400–900 |
| **Display test set**: 1.28" round IPS, a Sharp-type memory LCD breakout, maybe an AMOLED | Sunlight shoot-out | ₹300–3,000 each |
| **IMU + light sensor breakouts** | Test sensors | ₹150–600 each |
| **1S LiPo with protection + charger module** (test only) | Learn charging on the bench | ₹300–800 |
| **12 V → 5 V buck module + fuse holder** (test only) | Bike power experiments | ₹100–400 |
| Jumper wires, heat-shrink, breadboard | Basics | ₹200–600 |

Total to start experimenting: roughly **₹8,000–20,000** depending on how many displays you buy (estimate).

---

## 7. Money: what costs what

### 7.1 Per device (rough BOM, ~100–500 units, in USD; multiply by today's rupee rate)

| Part | Estimate |
|------|----------|
| ESP32-C3 module | $2–3 |
| Display | **$3–30** (OLED/basic round TFT cheap; transflective/bright panels expensive) |
| Battery cell + protection | $3–6 |
| Charger, regulator, gauge, IMU, light sensor, USB-C, passives, buttons | $8–15 |
| PCB + machine assembly | $5–12 |
| Enclosure, gasket, vent, screws, mount (prototype quality) | $10–30 |
| Hardwire cable with buck/fuse | $4–8 |
| **Rough total** | **~$35–100 per unit** at pilot volumes |

The **display and the enclosure** swing the total the most.

### 7.2 One-time costs (get quotes — ranges unknown to me)
- **Enclosure tooling** (injection moulds): the biggest one-time item; delay it until the design is proven.
- **Certification and testing**: WPC ETA (self-declaration), battery registration/testing under BIS, vibration and
  IP67 lab tests (see HARDWARE_ROADMAP §3.H). Ask labs for quotes.
- **Google Play developer account**: one-time fee (about $25).
- Prototype boards, sample parts, test equipment (see §6).

### 7.3 Software and service (Google)
- **Navigation SDK**: billed per **destination** you route to. **India: first 7,000 per month free, then about
  $0.008 each ($8 per 1,000)** (from Google's pricing pages, see PROTOTYPE_PLAN.md). Starting guidance and
  reroutes are not charged extra.
  - Example: 1,000 riders × 20 rides a month = 20,000 destinations → about (20,000 − 7,000) × $0.008 ≈ **$104/month**.
  - This is a **running cost per user**, so your price or subscription must cover it.
- **Places search** (if you add in-app search later): billed separately, price not yet checked.
- **No server** is needed yet, so no hosting cost.
- **Terms:** confirm that Google's terms allow showing its navigation on your hardware in a product.

---

## 8. Waterproof case: what you must plan
- Target **IP67** (dust-tight, survives 1 m of water for 30 min).
- Sealing: a **gasket or ultrasonic weld**, sealed button membranes, a **pressure-equalising vent** (otherwise
  fog forms behind the lens), a sealed USB-C port or a **magnetic/pogo charge connector**.
- Materials: UV-stable plastic; lens with hard/anti-glare coating; vibration-isolated mount.
- Path: 3D print → CNC/urethane casting for 10–50 units → injection moulding only after tests pass.
- A **mechanical designer** is worth hiring early: the case drives the board shape.

---

## 9. Your next steps (in order)
1. Answer the open choices: target price, first batch size (100/500/1,000).
2. Buy the §6 starter kit and run the **sunlight display test** and **power measurements**.
3. Draw the block diagram into a KiCad **schematic** for Stage 1 (I can prepare the part list and pin map).
4. Get the schematic **reviewed** before ordering boards.
5. Meanwhile, price the case: ask 2–3 local makers for a CNC/urethane quote from a simple drawing.
