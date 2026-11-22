# MotoNav hardware design specification (final decisions, for review)

Status: **draft for review by your professors.** It fixes the decisions made so far, states what each block must do,
gives a draft pin plan and the design rules, and sets out the review gates. Companion files: `hardware/bom_v2.csv`
(parts), `hardware/io_budget_v2.csv`, `docs/POWER_DESIGN_USBC.md`, `hardware/cost_model_final_product.xlsx` (cost).

Confidence marks: **[V]** read in an official source this project used · **[S]** from a search summary of such a
source, confirm in the datasheet · **[?]** engineering judgement or estimate, to be measured.

---

## 1. Decisions (final)

| Item | Decision |
|---|---|
| Display | Round **1.45" IPS TFT LCD, 412x412, QSPI**, 800+ nit, optically bonded with anti-reflective cover glass. Panel not yet chosen: see §9. |
| Power | **From the bike through a USB-C lead** into the rider's own 12 V USB charger. **No battery.** |
| Microcontroller | **ESP32-S3-MINI-1 module** (not a bare chip): pre-certified radio, antenna, crystal and flash on a 15.4 x 20.5 x 2.4 mm module **[S]**. |
| Sensing | Ambient light sensor only. No IMU, magnetometer, GPS or battery gauge. |
| Case | Waterproof (target IP67), two-part plastic housing with a bonded glass front and a handlebar clamp. |
| Link | Bluetooth LE to the phone. The phone does navigation. |
| Who designs | The project owner designs the board and case with this assistant's help; professors review at the gates in §8. |

Why the S3 module: the panel needs a QSPI interface and the app needs Bluetooth LE. The S3 has a native USB port and
a large GPIO count. A bare chip would need your own RF design and radio approval, which a module avoids.

## 2. Requirements

**Functional:** show next-turn arrow, distance, street, distance left, ETA and a route-line mini-map (north-up). Wake
on power, reconnect to the phone by itself, dim with ambient light, show "waiting for phone" when no data.

**Electrical:**
- Input: 5 V from USB, at least 500 mA available. No battery, no charger, no power path.
- Draw: typically 150 mA, peaks about 330 mA at 5 V **[?]** (see §4).
- The device resets whenever the bike charger drops out (engine start). Accepted; boot to a useful screen in a few seconds.
- Must survive a bad or noisy charger (surge, over-voltage) without damage.

**Environmental:** IP67 (1 m, 30 min). Vibration on a motorcycle (IEC 60068-2-64 is the usual test **[S]**). Sun and heat:
the **panel is rated -20 to +70 C [S]**, which a sun-baked handlebar can exceed: this is a design driver, not a footnote.

**Mechanical:** outside diameter at most 55 mm, thickness at most about 20 mm (probably 12-15 mm with no battery **[?]**),
weight at most about 60 g, mounts on a 22 mm handlebar or mirror stem (verify the target bikes).

## 3. Architecture

```
RIDER'S USB CHARGER ──USB-C lead──► J1 waterproof USB-C ─► ESD + surge TVS + over-voltage protection + ferrite
                                                              │ +5V (VBUS)
        ┌─────────────────────────────────────────────────────┴───────────────┬──────────────────────────┐
        ▼                                                                     ▼                          ▼
 5V → 3.3V buck (≥1 A) ──► ESP32-S3-MINI-1, ambient light sensor      backlight boost driver      bulk capacitor
                              │  QSPI + control                         (PWM dimming) ─► panel LED string
                              └──► 1.45" TFT panel (FPC)                 
```

The USB data pair also goes to the ESP32-S3's native USB (GPIO19/20) for flashing and logs through the same connector.

## 4. Power budget (to be replaced by measurements)

| Load | Assumption | Power at the load | Input from 5 V |
|---|---|---|---|
| ESP32-S3 + BLE connected, display updating | 100 mA at 3.3 V typical, 350 mA peak **[?]** | 0.33 W typical | via buck at 90% |
| Panel logic + interface | 10 mA at 3.3 V **[?]** | 0.03 W | via buck |
| Ambient light sensor | 0.2 mA | negligible | |
| Backlight, 800 nit | LED 27 mA x 9.6 V string = 0.26 W **[?]** (stock panel lists 15 mA at 450 nit) | 0.26 W | boost at 85% -> 0.305 W |
| **Total typical** | | | (0.33+0.03)/0.9 + 0.305 = **0.70 W = about 140 mA at 5 V** |
| **Total peak** | ESP at 350 mA | | (1.16+0.1)/0.9 + 0.305 = about 1.7 W = about 340 mA |

Peak stays below the 500 mA that any USB source must give. The two numbers that move this most are the backlight
current and LED string voltage: **ask the panel vendor for both.**

## 5. Block by block

| Block | Design | Verify before the schematic is final |
|---|---|---|
| **USB-C input** | Receptacle (waterproof). **5.1 kohm from CC1 and from CC2 to ground** (a power sink). VBUS to the protection stage. D+/D- to ESP32-S3 GPIO19/20 with 22-33 ohm series, 90 ohm differential **[V]** (Espressif design guide). Shell to ground. | Connector part and its mechanical drawing; locking option. |
| **Protection** | USB ESD array on D+/D-; surge TVS on VBUS (5 V standoff class); over-voltage protection IC/switch; ferrite bead; bulk capacitor 100-470 uF. | Part ratings against a charger fault (12-14 V) and the TVS clamp. |
| **5 V to 3.3 V** | Buck, at least 1 A, low noise. ESP32 needs 3.3 V at 500 mA or more, with at least 10 uF at the power entrance and 0.1 uF at supply pins **[V]**. | Pick the part and compute inductor/capacitor values from its datasheet. |
| **Backlight** | Boost LED driver from 5 V, PWM-dimmable, output rated above the panel's LED string voltage plus margin, current to 40-60 mA to leave headroom. | Panel LED voltage and current. |
| **ESP32-S3-MINI-1** | EN: 10 kohm pull-up with 1 uF to ground **[V]**. GPIO0 BOOT: pull-up, no large capacitor, to a button or test pad **[V]**. UART0 (GPIO43/44) and EN/BOOT on test pads. Antenna at the board edge in a copper-free zone, at least 15 mm clear inside the housing **[V]** (module-specific distances: datasheet). | Module pin table, antenna keep-out and land pattern. |
| **Panel interface** | QSPI to the panel by FPC, reset, optional tearing-effect, backlight PWM. Logic level (1.8 or 3.3 V) set by the panel. | Panel FPC pinout, logic voltage, SPD2010 timing. |
| **Light sensor** | I2C with pull-ups, behind a light window in the housing. | Part choice (the VEML7700 is flagged "not recommended for new designs" on LCSC **[S]**). |
| **Buttons** | 3 or 4 sealed switches to GPIO with pull-ups and ESD protection. Fewer buttons means fewer seals. | Button count. |
| **Spare** | Unpopulated IMU footprint on the I2C bus, test points on every rail. | |

## 6. Draft pin plan (verify against the module datasheet)

| Function | GPIO | Note |
|---|---|---|
| QSPI to the panel: CS, D0, CLK, D1, D2, D3 | 10, 11, 12, 13, 14, 9 | The SPI2 fast pins **[S]**. Quad-mode pins (WP/HD = 14/9) need a check in the technical reference manual. The fast pins allow up to 80 MHz; any other pins go through the GPIO matrix at 40 MHz **[S]**. |
| Panel reset / tearing effect / backlight PWM | 15 / 16 / 17 | Free pins. |
| I2C SDA / SCL / light-sensor interrupt | 4 / 5 / 6 | Free pins. |
| Buttons | 7, 8, 18, 21 | Free pins. |
| USB D- / D+ | 19 / 20 | Native USB. |
| UART0 TX / RX | 43 / 44 | Test pads. |
| BOOT | 0 | Strapping pin. |
| Status LED (optional) | 38 | |
| **Avoid** | 3, 45, 46 | Strapping pins **[S]**: GPIO0/46 select boot mode, GPIO45 selects flash voltage, GPIO3 selects the JTAG source. Sources disagree on one boot-mode combination: **check the datasheet's strapping table.** |
| **Avoid** | 26-37 | Used for flash and PSRAM on the module **[S]**: not available, or only on some variants. |

Total about 22 GPIOs. The ESP32-S3-MINI-1 comes as -N8 (8 MB flash, no PSRAM) or -N4R2 (4 MB flash, 2 MB PSRAM). One community
source says GPIO0 behaves differently with PSRAM **[S, single source]**: confirm before choosing the variant.

## 7. Design rules

**PCB**
- 4 layers: top signals and parts, solid ground, power, bottom signals **[V]**. Round board about 44-47 mm.
- Module at the board edge with the antenna over a copper-free, plastic-only sector; no metal, display flex or cable near it.
- QSPI: short, length-matched traces over an unbroken ground. USB pair 90 ohm differential.
- Wide traces for the 5 V and the backlight current. Keep the switching converters away from the antenna and light sensor.
- ESD and surge parts right at the connector, before anything else. Test points on every rail and signal in §6.
- Design for assembly at an online fab: only parts they stock, fiducials, panel rails, silkscreen that names every part.
- Leave footprints for the display connector to be swapped if the final panel's FPC differs.

**Case**
- Two parts plus a silicone gasket in a groove compressed about 15-25% **[?]**; screw bosses outside the sealing line.
- The bonded glass-and-panel unit sits in the front part and is sealed with adhesive or a gasket.
- USB-C: a sealed receptacle with its own gasket, on the side of the housing, with a locking lead, strain relief and a clip so the cable cannot snag.
- A pressure-equalising ePTFE vent on the back. Sealed silicone button membranes.
- A small **sun hood** around the display and a light-coloured matte housing: both help sunlight readability and lower the panel temperature **[?]**.
- Mount: a 22 mm clamp with rubber isolation and a quick-release interface to the back. No electrical contacts.
- Print and test-fit a prototype before any machined part, then test the seal in water.

## 8. Review gates

| Gate | You produce (with this assistant) | Professors review | Pass when |
|---|---|---|---|
| 1. Spec | this document | requirements, power budget, risks | agreed |
| 2. Block validation | the risky blocks built on dev boards: USB-C input and protection, buck, backlight driver, panel + ESP32-S3 | measurements | rails clean, display runs, resets handled |
| 3. Schematic | KiCad schematic, ERC clean, calculation sheet | the checklist below | no open points |
| 4. Layout | PCB layout, 3D model of the board | stack-up, antenna zone, routing, fit in the case | no open points |
| 5. Case | CAD model, assembly sequence, tolerance stack | seals, fit, thermal | prints fit, seal test passes |
| 6. Bring-up | first boards | measured results | everything on the bring-up list works |

**Schematic checklist:** every pin of every IC accounted for; strapping pins not loaded; decoupling at each supply pin;
USB-C CC resistors and ESD present; over-voltage and surge parts rated above the worst case; every part's ratings
against 5 V + margin; the ESP32 supply stable before EN rises; panel level compatible; backlight driver rated for the LED
string; test points on every rail; ERC with no errors; parts match `hardware/bom_v2.csv`.

**Bring-up order:** visual check and shorts -> power with a current-limited supply, no module fitted -> measure 5 V and
3.3 V -> fit the module and flash it -> panel -> backlight and dimming -> light sensor -> buttons -> Bluetooth to the app ->
connect to a bike charger -> engine-start test.

## 9. Open items that block final design

1. **The panel.** The schematic and board outline need the chosen panel's FPC pinout, logic voltage, backlight LED voltage
   and current, and outline drawing. Until the quote arrives, design to the stock 1.45" 412x412 QSPI SPD2010 panel
   (EastRising ER-TFT1.45-3) and ask the vendor for an 800-nit variant with the **same driver and connector**.
2. **Datasheets.** The module's pin table and antenna keep-out, and the datasheets of the buck, backlight driver, USB-C
   connector and over-voltage part, must be read before values are fixed.
3. **USB-C connector.** The chosen waterproof, locking part and its drawing (it sets the case cut-out).
4. **Button count.**
5. **Tools:** KiCad (free) on the project owner's computer for the schematic and board; a CAD tool for the case.

## 10. Risks, in order
1. USB-C connector sealing and retention on a vibrating, wet handlebar.
2. Panel heat: rated to +70 C, and the housing sits in sun.
3. Panel availability and minimum order for an 800-nit round part.
4. First-time board errors: mitigated by gate 2 (dev-board validation), by designing only from datasheet reference circuits,
   and by ordering 5-10 boards so a mistake is not fatal.
5. Radio and EMC behaviour near the antenna inside a sealed case.
