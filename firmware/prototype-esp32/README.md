# MotoNav prototype firmware (ESP32 DevKit V1 + SSD1306 OLED)

Receives navigation state from the phone over BLE and draws it on a 128×64 OLED.
Protocol: [`../../protocol/README.md`](../../protocol/README.md).

**Status:** compiles cleanly with PlatformIO (RAM 11.8%, flash 48.7%) for both display variants. Not yet flashed to real hardware — milestones M0/M1 below are the first real test.

## Wiring

| OLED pin | ESP32 DevKit V1 |
|----------|-----------------|
| VCC | 3V3 |
| GND | GND |
| SDA | GPIO21 |
| SCL | GPIO22 |

If the screen stays blank, change `OLED_I2C_ADDR` to `0x3D` in `src/config.h`. If it is a 1.3" SH1106 (not 0.96" SSD1306), build the `esp32dev_sh1106` environment.

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
| Blank screen | Wrong I2C address (try `0x3D`), SDA/SCL swapped, or SH1106 module |
| Garbled/shifted picture | SH1106 module — use `pio run -e esp32dev_sh1106 -t upload` |
| Phone can't find "MotoNav" | Not advertising — check serial log; reset the board |
| `NavCore REJECTED` in serial log | Wrong length (must be 18 bytes) or first byte is not `01` |
| Upload fails | Hold BOOT while "Connecting..." shows; try another USB cable (data, not charge-only) |
