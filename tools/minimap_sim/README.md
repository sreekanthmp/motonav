# MotoNav display simulator (412 × 412 round panel)

A PC program that draws what the handlebar display will show, so the layout can be judged **before the panel exists**.
It draws the device's screen from the same bytes the phone sends over Bluetooth, not from hand-placed demo values.

```
scenario (made-up 6.9 km ride)                      ┌──────────── what the phone does ────────────┐
   │                                                │ cut a window of the route (200 m behind,    │
   └─► position every 25 m ──────────────────────►  │ 2.5 km ahead), simplify to ≤ 96 points,     │
                                                    │ delta-encode, split into 20-byte writes     │
        wire.py / phone.py / ride.py                └───────────────────────┬─────────────────────┘
                                                                            │ NavCore, NavStreet, NavRoute, NavPos
                                                    ┌───────────────────────▼─────────────────────┐
                                                    │ what the device does: reassemble in order,  │
                                                    │ reject anything malformed, keep last good   │
                                                    └───────────────────────┬─────────────────────┘
                                                                            │ decoded values only
                                                                  render.py draws 412 × 412
```

## Run it

Needs Python 3.10+, `Pillow` and `numpy` (`pip install pillow numpy`). `g++` is optional (one test uses it).

```bash
cd tools/minimap_sim
python3 minimap_sim.py all            # contact sheets, legibility table, sunlight preview, ride GIFs -> samples/
python3 minimap_sim.py one --step 3 --dist 120 --layout B     # a single screen
python3 -m unittest -v                # 41 tests, about 1 minute (includes the firmware renderer, needs g++)
```

Already generated in [`samples/`](samples): `sheet_A.png`, `sheet_B.png` (11 screens each, shown 1:1), `sun_preview.png`,
`legibility.md`, `ride_A.gif`, `ride_B.gif`.

## The two layouts

* **A: everything on one screen**: arrow and distance, street, mini-map (route line, your position, destination, scale bar),
  distance left and minutes left. The map is north-up; there is no compass and no "then" arrow.
* **B: adaptive**: layout A while the next turn is far away; when it is 300 m or less the screen switches to a large arrow
  and distance and the map is hidden. (This answers "will it be hard to read?" by making the turn screen big.)

Both also draw: rerouting, arrived, "choose a destination" and "waiting for phone". There is no "off route" screen: the Google
feed never reports it (it goes straight to rerouting), so the protocol's OFF_ROUTE mode shows the rerouting screen. India drives on the left, so roundabouts run
clockwise (exit 1 is a left turn) and the standard U-turn is the clockwise one.

## What the checks say (read these before choosing a layout)

1. **Everything fits inside the circle.** Every lit pixel of every element is checked to be at least 12 px from the edge
   (`render.outside_violations`, asserted in the tests for all 11 screens in both layouts). The 12 px is an *assumption*
   for the bezel/bonding mask; replace it with the panel drawing's value.
2. **Text size.** Distance and arrow meet the target; **street and "distance · minutes left" do not** on layout A. They are
   limited by the circle: the bottom row sits where the chord is narrow, so it cannot be bigger.

   | Element | Cap height | At 55 cm | Target |
   |---|---|---|---|
   | Arrow (A / B turn screen) | 6.6 / 9.3 mm | 41′ / 58′ | n/a |
   | Distance (A / B turn screen) | 3.6 / 5.7 mm | 22′ / 35′ | 3.2 mm → OK |
   | Street (A) | 2.1–2.5 mm | 13–15′ | 2.6 mm → below |
   | Distance left · minutes left | 2.1 mm | 13′ | 2.6 mm → below |
   | Scale-bar label | 1.6 mm | 10′ | none (not read while riding) |

   The target (cap height ≥ 16–20′ of arc, i.e. 2.6–3.2 mm at 55 cm) is guidance I recalled and **did not verify against a
   standard**. Treat the verdicts as relative, not as a pass/fail. Fix by taking the street/remaining row off the turn
   screen (layout B already does it for the arrow and distance) or by showing street and remaining on alternate seconds.
3. **Street names are cut to 20 characters by the protocol**: "Residency Road Extension" arrives as "Residency Road Exten".
   The renderer shrinks the font to 34 px, then abbreviates (Road→Rd, Junction→Jn), then ends with "…". Better: the *phone*
   should abbreviate before it truncates (not done yet; `NavProtocol.toAsciiStreet` just takes the first 20 characters).
4. **Sunlight** (`sun_preview.png`): see the model below. Under its assumptions, direct sun leaves a white-on-black screen at
   2.7:1 white-to-black contrast on an 800-nit optically bonded panel, 1.9:1 on a 450-nit one, and **1.6:1 on the 800-nit panel
   if it has an air gap**. Bonding and an anti-reflection coating matter as much as the nits. Make them a requirement when
   quoting panels.

## What is exact and what is assumed

Exact (tested):

* Protocol bytes. The Python encoder reproduces the golden vectors of the Android and firmware tests, and
  `test_real_firmware_decoder_agrees` feeds a whole simulated ride through the **real** `protocol/nav_route.h` (compiled with
  `g++`, see `device_decode.cpp`) and checks every window and position matches.
* Phone logic: window extraction, Douglas–Peucker (tolerance 2, 4, 8 … m until ≤ 96 points), splitting of long steps,
  window ids 1..255 skipping 0, resend after 1 km of use. Mirrors `MapFeed.kt` and `RouteSimplifier.kt`.
  Not mirrored: off-route and reroute handling.
* Device logic: in-order chunks only, exact length, coordinate range, a bad window is dropped and the previous one kept,
  a position belonging to another window is not drawn on this route.

Assumed (change them in `render.py` / `sun.py` when the real numbers are known):

| Assumption | Value | Where |
|---|---|---|
| Active diameter of the 1.45" round panel | 36.8 mm (0.0894 mm/px); **datasheet not yet read** | `PITCH_MM` |
| Viewing distance | 550 mm | `VIEW_DIST_MM` |
| Safe margin from the edge | 12 px | `SAFE_MARGIN_PX` |
| Font | Liberation Sans Bold stands in for the firmware font (LVGL has its own rasteriser) | `fonts.py` |
| Colours | white + amber on black (saturated blue/green dim in sun); sRGB as drawn, the panel's real gamut and white point will differ | `render.py` |
| Sunlight | glare = E·R/π added to every pixel; E = 10 / 100 klux, R = 1.5 % bonded + AR, 4 % air gap; gamma 2.2 | `sun.py` |
| Route | made-up, in no real city; street names are examples | `ride.py` |
| Speed for ETA | 26 km/h | `ride.SPEED_MS` |

Not simulated: frame timing, Bluetooth latency or packet loss, panel response time, viewing-angle shift, brightness
control, vibration, the touch of the real display driver (QSPI) and any firmware memory limit.

## Files

| File | Role |
|---|---|
| `wire.py` | Byte-exact encoders/decoders: NavCore, NavStreet, NavPos, NavRoute, and the device's reassembler |
| `phone.py` | Route model, simplifier, window/chunk feed (mirror of the Android `MapFeed`) |
| `ride.py` | The made-up route, the device state, a ride that drives both |
| `arrows.py` | Manoeuvre icons drawn from geometry (no image files) |
| `render.py` | The panel: layouts A and B, mini-map, scale bar, circle-fit and text-size measurements |
| `sun.py` | Rough sunlight preview and contrast numbers |
| `minimap_sim.py` | Command line: sheets, tables, GIFs |
| `device_decode.cpp` | Test harness around the real firmware decoder |
| `test_minimap_sim.py` | 32 tests of the simulator |
| `test_firmware_parity.py` | 9 tests: the firmware renderer (`firmware/product-esp32s3`) compiled for the PC against this simulator |

## Next

* Pick a layout (A, B, or A with street/remaining alternating) and the colours.
* Read the panel datasheet: active diameter, bezel, brightness, bonding. Update the assumptions above.
* Layout B is now ported to firmware: see [`firmware/product-esp32s3`](../../firmware/product-esp32s3). When the panel
  arrives, compare a photo of it with the simulator image side by side.
