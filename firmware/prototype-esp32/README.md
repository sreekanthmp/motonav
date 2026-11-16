# MotoNav prototype firmware (ESP32 DevKit V1 + SSD1306 OLED)

Receives navigation state from the phone over BLE and draws it on a 128×64 OLED.
Protocol: [`../../protocol/README.md`](../../protocol/README.md).

**Status:** compiles with PlatformIO and **runs on real hardware** (ESP32 DevKit V1 + 0.96" I2C OLED). Verified: display self-test, BLE link to the Android app, arrows/distance/street updates, and switching the display driver with a long BOOT press.

## Wiring

| OLED pin | ESP32 DevKit V1 |
|----------|-----------------|
| VCC | 3V3 |
| GND | GND |
| SDA | GPIO21 |
| SCL | GPIO22 |

At boot the firmware scans the I2C bus, picks the display address (0x3C or 0x3D) automatically, and prints what it finds on the serial monitor. If it finds nothing on GPIO21/22 it also searches other pin pairs and tells you where the display is really wired.

**Blank or garbled screen?** Cheap "SSD1306" modules are often SH1106 or clones. **Hold the BOOT button for 2 seconds and release** to switch to the next display driver (SSD1306 → SH1106 → SSD1306 alt). The screen shows the driver name; the choice is remembered after a reboot. No re-flash needed. At every boot you also get a quick self-test: all pixels on, then a frame with the driver name.

## Build and flash

```bash
pip install platformio            # once
cd firmware/prototype-esp32
pio run -t upload                 # hold the BOOT button if it hangs on "Connecting..."
pio device monitor                # 115200 baud serial log
```

On Windows/Linux you may need the board's USB driver (CP210x or CH340). On Linux, add yourself to the `dialout` group if the port is not accessible.

## M0 — test the display alone

With no phone connected the screen shows **"Waiting for phone"**. Press the **BOOT** button to cycle seven demo screens (straight, left, right, slight right, roundabout exit 2, U-turn, arrive).

## M1 — drive it from the free **nRF Connect** phone app (no code)

1. Open nRF Connect → scan → connect to **MotoNav**.
2. Open the service `4d6f746f-4e61-7601-8a5c-3f2d1b0e0001`.
3. Write a street name to characteristic `…0003` (NavStreet) — type it as **text**, or hex `4D4720526F6164` = "MG Road".
4. Write a hex value to characteristic `…0002` (NavCore). **Repeat within 5 seconds** — after that the device shows "Phone lost" on purpose.

| Hex to write to NavCore (18 bytes) | Shows |
|------------------------------------|-------|
| `0101040001002C010000501400000C002C01` | LEFT in 300 m, 30 km/h, 5.2 km left, ETA 12 min |
| `01010700020050000000501400000C000000` | RIGHT in 80 m |
| `01011002030096000000501400000C000000` | ROUNDABOUT, exit 2, in 150 m |

Mode byte (2nd byte): `00` idle, `01` navigating, `02` off route, `03` rerouting, `04` arrived.

Serial monitor prints every packet it accepts or rejects.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| Serial says `NO I2C DEVICE FOUND` | Wiring/power. The serial log also says where the display answers (SDA/SCL may be swapped) |
| Display found but screen blank or garbled | Hold BOOT 2 s and release to switch display driver |
| Phone can't find "MotoNav" | Not advertising — check serial log; reset the board |
| `NavCore REJECTED` in serial log | Wrong length (must be 18 bytes) or first byte is not `01` |
| Upload fails | Hold BOOT while "Connecting..." shows; try another USB cable (data, not charge-only) |
