# KiCad lesson 1: a USB-C to 3.3 V board

Goal: go through the **whole** PCB workflow once on a board so simple that nothing can go very wrong: install -> schematic ->
footprints -> board -> checks -> fab files -> order. About 10 hours over 3-5 sessions. This is the first step of
`docs/LEARN_PCB_DESIGN.md`. It is the power input your real board will also need.

**What you build:** a 30 x 20 mm board. A USB-C connector takes 5 V from any charger or computer, a small regulator makes
3.3 V, and an LED lights to show it works. Test pads let you measure both voltages.

```
USB-C J1 ──VBUS (5 V)──┬──[C1 1uF]──► U1 3.3 V regulator ──┬──[C2 1uF]── 3V3 ──┬── TP2 (3V3)
   CC1 ─[R1 5.1k]─ GND │              (IN, GND, EN, OUT)    │                   └─[R3]─LED D1─ GND
   CC2 ─[R2 5.1k]─ GND └── TP1 (5V)                         └── (EN tied to IN)
   shell ─ GND                                                                    TP3 (GND)
```

## Why each part is there (be able to say this)
- **R1, R2 (5.1 kohm on CC1 and CC2):** tell a USB-C charger "I am a device that wants power". Without them a USB-C-to-C charger gives no 5 V.
- **U1 (3.3 V regulator):** turns 5 V into the 3.3 V a microcontroller needs. Your real board will use the same idea.
- **C1, C2 (1 uF):** the regulator's datasheet asks for input and output capacitors so it stays stable. **Read it and confirm the value yourself.**
- **R3 + D1 (resistor + LED):** a power indicator. Current = (3.3 V - LED voltage) / R. With a red LED at about 2.0 V and 470 ohm: (3.3 - 2.0) / 470 = about 2.8 mA. Check the number against the LED's datasheet.
- **TP1-TP3:** places to touch a multimeter probe. A board with no test points is hard to debug.

## Parts to look up (check every pin in the datasheet)
| Ref | Part | Note |
|---|---|---|
| J1 | USB-C receptacle, 16-pin, USB 2.0, power only | In KiCad search "USB_C_Receptacle". Names differ by version. |
| R1, R2 | 5.1 kohm, 0805 | 0805 is easy to solder by hand. |
| U1 | A 3.3 V LDO, about 600 mA, in SOT-23-5, such as the AP2112K-3.3 | Read its datasheet for pin order and capacitor values. |
| C1, C2 | 1 uF ceramic, 0805 | Value from the datasheet. |
| R3 | 470 ohm, 0805 | |
| D1 | LED, 0805, any colour | |
| TP1-TP3 | Test points | |

## Session 1: install and set up (1 hour)
1. Install KiCad from [kicad.org/download](https://www.kicad.org/download/) (the manual is for [9.0](https://docs.kicad.org/9.0/en/getting_started_in_kicad/); a 10.0 edition exists).
2. Create a project `lesson1_usbc_3v3`. You get a project file, a schematic and a board file.
3. Read the first half of KiCad's "Getting Started" manual while you do this.

## Session 2: the schematic (2-3 hours)
1. Add the symbols (press `A`). Place them with the signal flowing left to right, power at the top, ground at the bottom.
2. Wire them (`W`). Use labels (`L`) for 5V and 3V3 and the **power symbols** for GND.
3. Give every part a reference and value: R1, R2, R3, C1, C2, U1, D1, J1, TP1-TP3. Use **annotate** to number them.
4. Run **ERC** (electrical rules check). Two messages you will see:
   - *"Pin not connected"*: connect it, or mark it intentionally unconnected.
   - *"Power input pin not driven"*: put a **PWR_FLAG** symbol on the net that the connector feeds.
5. Read **every pin** of U1 and J1 in the datasheet and confirm each is connected correctly. This habit prevents most dead boards.

## Session 3: footprints and the board (3 hours)
1. **Assign a footprint to every symbol**. 0805 for the passives, SOT-23-5 for U1, a USB-C footprint matching your connector.
2. **Check footprints against the real datasheet drawing**: pin 1 position, pad size and pitch. This is the most important check.
3. Update the board from the schematic. Draw a 30 x 20 mm outline on the `Edge.Cuts` layer.
4. Place the connector on one edge, the regulator close to it, the capacitors next to U1's pins, test points where you can reach them.
5. Route with a **2-layer** board. Power traces about 0.5 mm wide, signals about 0.25 mm. Fill the **bottom layer with a ground zone** (`B`).
6. Add a name and version in silkscreen. Run **DRC** (design rules check) until it has no errors.
7. Open the **3D viewer** and look at it.
8. **Print the board at 100% on paper.** Put the real parts on it. If a part does not fit the pads, fix it before you order anything.

## Session 4: files and order (1-2 hours)
1. Use the **Fabrication Toolkit** plugin (third-party, installed from KiCad's Plugin and Content Manager) or KiCad's own export, to make Gerbers, drill files, a bill of materials and a placement file. JLCPCB has a [guide](https://jlcpcb.com/help/article/how-to-generate-the-bom-and-centroid-file-from-kicad).
2. Open the Gerbers in a viewer (most fabs have one online) and look at every layer.
3. Order a handful of boards. For a first board, solder it by hand or pay for assembly.

## Session 5: bring-up and a review (1-2 hours)
1. Look at the board. Then check **continuity** between 5 V and ground with a multimeter: it must not beep (a short).
2. Plug in a charger. Measure TP1 (about 5 V) and TP2 (about 3.3 V). The LED should light.
3. **Write down everything that surprised you.** Then send the project to me or your professor for review.

## Checklist before you order anything
- [ ] ERC and DRC have no unexplained errors.
- [ ] Every pin of every part checked against its datasheet.
- [ ] Every footprint checked against its datasheet drawing, and the board printed at 100% and tested with real parts.
- [ ] Pin 1 of U1 and the LED polarity are marked on the silkscreen.
- [ ] Test points on 5 V, 3.3 V and ground.
- [ ] Ground zone present, no isolated copper islands.
- [ ] Gerbers viewed layer by layer.

## Self-check questions
1. What is the difference between a symbol and a footprint?
2. What happens on a USB-C-to-C charger if you leave out R1 and R2?
3. Why put C1 and C2 next to U1 and not elsewhere on the board?
4. What does a ground zone do, and why fill the bottom layer with one?
5. What does ERC check that DRC does not, and the other way round?

## Share it for review
Commit the project folder to the repo, for example `hardware/kicad/lesson1_usbc_3v3/`. The `.kicad_sch` and `.kicad_pcb` files are
text, so the schematic can be checked pin by pin. Screenshots and PDF exports also work.

Common reasons first boards fail: a wrong footprint, a reversed LED or connector, a missing ground connection, a pin you
assumed but did not read, and skipping the paper printout.
