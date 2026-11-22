# Power design: bike power only, through USB-C

**Decision (November 2026):** the device has **no internal battery**. It is powered from the bike through a USB-C
lead plugged into the rider's own 12 V USB charger. This is the cheapest of the four options costed in
`hardware/cost_model_final_product.xlsx` (Variants sheet) and the only one that fits a Rs 5,000 all-in cost at about
2,000 units. Parts list: `hardware/bom_v2.csv`. Pin budget: `hardware/io_budget_v2.csv`.

Cost per unit (mid case, rupees): factory cost about 3,920 at 500 units, 3,510 at 2,000, 3,105 at 10,000; all-in
about 8,820, 4,740 and 3,350. These come from assumptions as well as listings: see the workbook's ReadMe sheet.

```
BIKE 12 V ──[rider's fused 12V→5V USB charger]──USB-C lead (in the box, locking, waterproof)──► DEVICE USB-C (IP67)
                                                                                                    │ 5 V
                          ┌───────────────────────────────────────────────────────────────────────┘
                          ▼
            [ESD + TVS + over-voltage protection + ferrite + bulk capacitor] ─┬─► 5 V → backlight boost driver → panel backlight
                                                                              └─► 5 V → 3.3 V buck → ESP32-S3, ambient light sensor
```

## What it removes
Battery, charger and power path, fuel gauge, IMU, pogo contacts and the hardwired bike cable (the rider's USB charger
does the 12 V to 5 V conversion). That removes battery registration, UN38.3 shipping rules, heat and ageing of a LiPo
in the sun, and about Rs 620 of cable board per unit. It does **not** remove: Bluetooth listing, radio approval,
vibration and IP67 tests, EMC.

## Requirements
- **Input:** 5 V from a USB source, at least 500 mA available. Draw is roughly 100-250 mA depending on brightness and CPU load, with higher short peaks (estimate: measure it on the bench).
- **Protection:** USB ESD, a surge TVS, an over-voltage protector, and a ferrite. A cheap or faulty charger must not
  kill the board, and chargers can be noisy.
- **Engine start:** the charger output drops for a moment when the engine cranks, so the device **reboots at each
  start**. Accepted. Boot to a useful screen in a few seconds and reconnect to the phone by itself. A bulk capacitor
  rides through the shortest drops.
- **Off:** no ignition, no power, no standby current. There is no ship mode or battery management to build.
- **Connector:** the USB-C port is the critical part: water-sealed (IP67), locking against vibration, with strain relief
  and a clip so the lead cannot snag. USB-C PD and A-to-C leads both work with 5.1 kohm resistors on CC1 and CC2.

## Risks, in order
1. **USB-C connector on a vibrating, wet handlebar** (loosening, water, corrosion). Test three connector types on a real
   bike for a week and in rain before locking the part. This is the first thing to prove.
2. **Charger quality varies.** Test several brands, publish a tested list in the manual, and consider selling a
   recommended charger later.
3. **Riders without a USB socket** must fit a charger (cheap and common, but it is a step). Check at the demand test.
4. **A reboot at every start** may annoy some riders. Measure boot time; if it matters, add a supercapacitor.

## Firmware changes
Remove charging, fuel gauge, battery temperature and sleep logic. Add brownout handling, a fast boot to a "Waiting for
phone" screen, and automatic Bluetooth reconnect. Keep auto-brightness from the ambient light sensor.

## Bench tests (before the first custom board)
1. Log a charger's voltage on the target bikes: engine off, cranking, idle, revving, lights and indicators.
2. Power the prototype from several chargers through each USB-C lead type: measure draw, noise and resets.
3. Simulate a start on the bench supply (drop to about 3 V for 20 ms to 1 s) and see how the device recovers.
4. Rain and vibration test of each connector on a bike.
5. Sunlight and temperature test of the panel (the stock panel is rated only -20 to +70 C).
