# MotoNav BLE protocol v1

Single source of truth: [`nav_protocol.h`](nav_protocol.h) (firmware). The Android side mirrors it in
`android/app/src/main/java/com/motonav/prototype/NavProtocol.kt`. Change both together and keep the
unit test (`ProtocolTest`) and the firmware `static_assert` in step.

The phone is the BLE **central**; the device is the **peripheral** advertising as `MotoNav`.

| UUID | Characteristic | Direction | Properties |
|------|----------------|-----------|------------|
| `4d6f746f-4e61-7601-8a5c-3f2d1b0e0001` | **Service** | | |
| `…0002` | `NavCore` | phone → device | write, write w/o response |
| `…0003` | `NavStreet` | phone → device | write, write w/o response |
| `…0004` | `Events` | device → phone | notify |

## NavCore — 18 bytes, little-endian, sent about once a second

| Offset | Type | Field |
|--------|------|-------|
| 0 | u8 | `version` (= 1; other values are rejected) |
| 1 | u8 | `mode`: 0 IDLE · 1 NAV · 2 OFF_ROUTE · 3 REROUTING · 4 ARRIVED |
| 2 | u8 | `maneuver` (enum below) |
| 3 | u8 | `roundabout_exit` (0 = unknown) |
| 4 | u16 | `seq` — increments per packet; gaps reveal dropped packets |
| 6 | u32 | `dist_to_maneuver_m` |
| 10 | u32 | `remaining_dist_m` |
| 14 | u16 | `eta_minutes` |
| 16 | u16 | `speed_kmh_x10` (0.1 km/h units) |

**Maneuver enum:** 0 NONE · 1 DEPART · 2 STRAIGHT · 3 SLIGHT_LEFT · 4 LEFT · 5 SHARP_LEFT · 6 SLIGHT_RIGHT · 7 RIGHT · 8 SHARP_RIGHT · 9 UTURN_LEFT · 10 UTURN_RIGHT · 11 KEEP_LEFT · 12 KEEP_RIGHT · 13 MERGE · 14 RAMP_LEFT · 15 RAMP_RIGHT · 16 ROUNDABOUT · 17 FERRY · 18 ARRIVE · 19 ARRIVE_LEFT · 20 ARRIVE_RIGHT

## NavStreet — up to 20 bytes, ASCII

Street name of the upcoming maneuver. Written only when it changes (and again after a reconnect). Non-ASCII bytes are shown as `?`.

## Events — 3 bytes, device → phone

`[type, id, 0]` with `type 1` = button press (`id` = button number).

## Rules

1. **Send full state, never deltas** — a dropped packet is superseded by the next one.
2. **Send enums, not images** — icons are drawn by the device.
3. **Version byte** in every NavCore; the device ignores unknown versions.
4. **Staleness:** no valid NavCore for 5 s → the device shows "Phone lost" (never a stale arrow).
5. The device **interpolates** `dist_to_maneuver_m` between packets using `speed_kmh_x10`.
6. NavCore is exactly 18 bytes so it fits the default 20-byte BLE payload (no MTU negotiation needed).

## Verified

The firmware's C struct and the Android unit-test bytes were cross-checked (same offsets and values) with a host-compiled C++ test.
