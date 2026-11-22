# Learn PCB design by building MotoNav's board

For a software engineer with no hardware background, who will design the real board with help and have it reviewed by
professors. The idea: **learn on a small practice board first**, then design the real one. You make your mistakes cheaply.

Time: about 8-10 hours a week, for roughly 8-10 weeks to a reviewed real board. (An estimate: it depends on you and on fab delays.)

---

## The plan: two boards

| | Board A: practice board | Board B: the real MotoNav board |
|---|---|---|
| What | USB-C input + protection + 3.3 V supply + ESP32-S3-MINI-1 + BOOT/EN + LED + test pads | Board A plus the 1.45" panel connector, backlight driver, light sensor, buttons |
| Why | The same power input and microcontroller core the real board needs, so it is not wasted | The product |
| Proves | You can go from schematic to a board that powers up, flashes and runs Bluetooth | The display, backlight and case fit |
| Size | Small, simple | Round, about 45 mm |

Board A runs a small Bluetooth test firmware (a port of your existing firmware to the S3, so the phone app can connect to it).

## Steps

| Step | What you do | You can explain when done | Check with |
|---|---|---|---|
| **0. Concepts** (1 week) | Read the glossary in `docs/HARDWARE_PCB_GUIDE.md`, then the resources below. | Schematic vs layout; net; footprint; why a chip has decoupling capacitors; what a ground plane does; why USB lines are a pair. | Me: ask me to quiz you |
| **1. First tool run** (1 week) | Install KiCad. Follow one beginner tutorial all the way: schematic -> board -> design-rule check -> Gerber files. Make a tiny LED board. Order it if you like. | The files a fab needs and how to export them. | Your own board file |
| **2. Board A schematic** (1 week) | Draw it from the datasheet circuits. I give you each block's circuit and values. | Why every part is there and what each pin does. | **Professor review 1** (schematic checklist in `HARDWARE_DESIGN_SPEC.md`) |
| **3. Board A layout** (1-2 weeks) | Place parts, route, add a ground plane, keep the antenna zone clear. Print it 1:1 on paper and lay the real parts on it to check footprints. | Why each part sits where it does; trace width; what the antenna zone is. | **Professor review 2** |
| **4. Order and assemble** (2-3 weeks, mostly waiting) | Send the files to a fab; have the module assembled or reflow it yourself. | | |
| **5. Bring-up** (1 week) | Visual check -> power with a current-limited supply -> measure rails -> flash -> Bluetooth to the app. Keep a log of every mistake. | What each mistake was and how you would avoid it. | The log |
| **6. Board B** (3-4 weeks) | Add the panel connector, backlight driver, light sensor and buttons, using what Board A taught you. Then the case. | | **Professor reviews 3-5** |

## What matters most for this project
1. **Reading a datasheet** and copying its reference circuit.
2. **Power:** the supply, decoupling capacitors close to every supply pin, and the ground return path.
3. **Footprints:** pin 1, pad size and pitch. A wrong footprint ruins a board.
4. **USB:** a differential pair, CC resistors, ESD protection.
5. **The ESP32 antenna keep-out** (copper-free zone) and the strapping pins.
6. **Trace width against current** and how to read the fab's capability table.
7. **Test points and bring-up order**, so a dead board can be diagnosed.
8. **Designing for the fab** (the rules it publishes) and exporting the right files.

You can skip for now: high-speed memory, impedance-controlled routing beyond the USB pair, simulation.

## Common first-board mistakes
- A footprint that does not match the part (check pin 1 and the datasheet drawing; print 1:1).
- 5 V connected to a 3.3 V pin.
- No pull-up on I2C, or a load on a strapping pin so the chip will not boot.
- Missing decoupling capacitors, or placed far from the pin.
- Copper or metal under or near the antenna.
- No test points, so a dead board cannot be debugged.
- Ordering without a design-rule check, or without printing the board 1:1.
- USB-C with no CC resistors (the charger gives no power).

## Resources
- KiCad's own manual: [Getting Started in KiCad](https://docs.kicad.org/9.0/en/getting_started_in_kicad/) (a 10.0 edition also exists; check [kicad.org/download](https://www.kicad.org/download/) for the current release).
- Written beginner guides: [SparkFun: Beginner's Guide to KiCad](https://learn.sparkfun.com/tutorials/beginners-guide-to-kicad/introduction), [Digi-Key Maker.io: Beginner's Guide to KiCad](https://www.digikey.com/es/maker/projects/beginners-guide-to-kicad/eb0ae92f5dae49e489f02c10f3edfc60), [Build Electronic Circuits: KiCad tutorial](https://www.build-electronic-circuits.com/kicad-tutorial/).
- ESP32 specific: [Build Your First PCB in KiCad 9](https://jak-services.github.io/en/tutorial_kicad_9_build_your_first_pcb.html) (power and analog front end for an ESP32 DevKitC); element14's [Your First Real PCB in KiCad](https://community.element14.com/challenges-projects/element14-presents/project-videos/w/documents/72057/your-first-real-pcb-in-kicad-an-arduino-compatible-board-designed-from-scratch) and [How To Make A Custom PCB From Design To Assembly](https://community.element14.com/challenges-projects/element14-presents/project-videos/w/documents/5213/how-to-make-a-custom-pcb-from-design-to-assembly----episode-485) (ESP32-S2 module).
- Paid, if you want a video course: Predictable Designs' [ESP32-S3 PCB tutorial](https://predictabledesigns.com/esp32p) (about 4 hours).
- The authority for your chip: Espressif's [ESP32-S3 hardware design guidelines](https://docs.espressif.com/projects/esp-hardware-design-guidelines/en/latest/esp32s3/index.html).
- Getting boards made: JLCPCB's [BOM and centroid from KiCad](https://jlcpcb.com/help/article/how-to-generate-the-bom-and-centroid-file-from-kicad) guide, and the third-party [Fabrication Toolkit](https://github.com/bennymeg/Fabrication-Toolkit) plugin (exports Gerbers, BOM and placement file in one click; check it supports your KiCad version).

I have not watched or tried these. They came from search results, so skim each before you commit time.

## How this assistant helps
- Explains any concept on request and quizzes you after each step.
- Gives you each block's circuit with values worked out from the datasheet (once I can read it: see `HARDWARE_DESIGN_SPEC.md` §9).
- **Reviews your files.** Commit your KiCad project to the repo (for example `hardware/kicad/board_a/`). The schematic and board files are text, so I can read them and check pins, strapping, decoupling, values and footprints against the datasheet. You can also send screenshots or PDF exports.
- Keeps a mistakes log with you.

Limits: I cannot run KiCad here, cannot see a layout except through exports, and my part values are only as good as the datasheet I can read.
Your professors should see the schematic and layout before anything is ordered.

## Your first week
1. Install KiCad (free).
2. Follow one beginner tutorial from start to finish.
3. Ask me to quiz you on the step 0 concepts.
