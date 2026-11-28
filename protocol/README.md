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
| `…0005` | `NavRoute` | phone → device | write, write w/o response (**map feed, optional**) |
| `…0006` | `NavPos` | phone → device | write w/o response (**map feed, optional**) |

## NavCore — 18 bytes, little-endian, sent about once a second

| Offset | Type | Field |
|--------|------|-------|
| 0 | u8 | `version` (= 1; other values are rejected) |
| 1 | u8 | `mode`: 0 IDLE · 1 NAV · 2 OFF_ROUTE · 3 REROUTING · 4 ARRIVED. (The Google feed never sends 2; the 412 x 412 display shows its rerouting screen for it) |
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

## Map feed (additive): the route line and your position

`NavCore` is **unchanged** (still version 1). Two new characteristics carry what the device needs to draw a route-line mini-map.
A device that lacks them still works with any phone, and a phone checks for them after connecting and skips the feed if absent.
Coordinates are degrees x 1e-6 as signed 32-bit integers (about 0.11 m), little-endian.

| Detail on the display | Carried by |
|---|---|
| Next-turn arrow, distance, street | `NavCore`, `NavStreet` (already) |
| Distance left, ETA | `NavCore` (already) |
| Route line, destination dot | **`NavRoute`**: a window of the route around the rider |
| Your position | **`NavPos`**, about once a second |
| Scale bar | Drawn by the device |
| "Then left in ...", compass N, heading-up | **Not sent** (dropped from the design) |

### NavRoute: the route window, in chunks
The phone sends a short, simplified piece of the route: from about **200 m behind** the rider to **2,500 m ahead**, reduced to
**at most 96 points** (Douglas-Peucker, 2 m tolerance widening as needed). It sends a new window the first time, after a
reroute, after the device reconnects, and once the rider is more than 1,000 m into the current one. Each window is split into
BLE writes of at most 20 bytes (works without MTU negotiation; larger writes are fine):

```
chunk  : [route id u8][chunk index u8][chunk count u8][data ...]      chunks are sent in order
stream : [version u8 = 1][flags u8][point count u16][first latitude i32][first longitude i32]
         then, for every further point: [d latitude i16][d longitude i16]       (difference from the previous point)
flags  : bit 0 = the last point is the destination
```
Window ids run 1..255 and wrap, skipping 0. A step too long for a 16-bit difference is split by the phone.
The device accepts a window only when **all chunks arrive in order** and the stream is exactly the right length with valid
coordinates; otherwise it keeps the previous window. A window of 96 points is 392 bytes: 24 writes at 20 bytes, 3 at 185.

### NavPos: 10 bytes
`[route id u8][seq u8][latitude i32][longitude i32]`. The id says which window the position belongs to (0 = none yet).

### Golden bytes (asserted by both the Android tests and the firmware PC test)
Five points (12.971600, 77.594600) -> (12.972000, 77.594600) -> (12.972000, 77.595100) -> (12.972500, 77.595100) ->
(12.972300, 77.594900), id 7, ends at the destination, 20-byte writes:
```
chunk 0: 0700020101050050eec500e8ff9f049001000000
chunk 1: 07010200f401f401000038ff38ff
NavPos (id 7, seq 42, 12.972150, 77.594800): 072a76f0c500b000a004
```

## Rules

1. **Send full state, never deltas** — a dropped packet is superseded by the next one.
2. **Send enums, not images** — icons are drawn by the device.
3. **Version byte** in every NavCore; the device ignores unknown versions.
4. **Staleness:** no valid NavCore for 5 s → the device shows "Phone lost" (never a stale arrow).
5. The device **interpolates** `dist_to_maneuver_m` between packets using `speed_kmh_x10`.
6. NavCore is exactly 18 bytes so it fits the default 20-byte BLE payload (no MTU negotiation needed).

## Verified (map feed)

Android: `RouteWindowCodecTest`, `RouteSimplifierTest` and `MapFeedTest`. Firmware decoder: `firmware/prototype-esp32/test_host/test_nav_route.cpp`
(PC test, with sanitizers). Both assert the golden bytes above. **Not yet checked on a phone talking to the device.**

## Verified

The firmware's C struct and the Android unit-test bytes were cross-checked (same offsets and values) with a host-compiled C++ test.
