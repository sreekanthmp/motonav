# MotoNav Android prototype

Small Kotlin app: connects to the ESP32 over BLE and forwards Google Navigation SDK turn-by-turn data to it.

**Status**

- All Kotlin sources **compile without errors** with the Kotlin compiler against the **real** Navigation SDK 7.0.0 and Android 36 jars. Only the AndroidX classes used by `MainActivity` were checked against small stand-ins.
- **117 unit tests pass in the real Android Gradle build** (`./gradlew :app:testDebugUnitTest`): the original 31 plus the route follower and map feed tests. The debug APK assembles.
- The app has been **built in Android Studio and run on a real phone** (Samsung SM-G781B). Verified there: Share from Google Maps, Navigation SDK guidance, the turn-by-turn feed, and Bluetooth to the ESP32.
- **Not yet verified:** locked-screen behaviour over long rides, other phone brands.

## What you need

- **Android Studio** Meerkat (2024.3.1) or newer (uses Android Gradle Plugin 8.9.2, Gradle 8.14.3 via the wrapper, JDK 17+ is bundled).
- A **physical Android phone** (Android 7+, Google Play services, 2 GB+ RAM) with USB debugging on. BLE does not work in the emulator.
- A **Google Cloud project with billing enabled** — see the pricing notes in `docs/PROTOTYPE_PLAN.md` (India: first 7,000 route requests/month free).

## One-time Google setup

1. Create a Cloud project and **enable billing**.
2. Enable **Navigation SDK for Android** (`navigationsdk.googleapis.com`).
3. Create an **API key**: restrict it to *Navigation SDK for Android*, and to this app (`com.motonav.prototype` + your debug SHA-1).
4. Set a **budget alert** and a low **quota** (budgets alone do not cap spending).
5. Copy `local.properties.example` to `local.properties` and paste the key:
   ```
   MAPS_API_KEY=AIza...
   ```

## Run

1. Android Studio → *Open* → select this `android/` folder → let Gradle sync.
2. Plug in the phone → *Run*.
3. In the app:
   1. Grant the permissions (location; "Allow all the time" for locked-screen use).
   2. **Connect to MotoNav** (ESP32 powered and showing "Waiting for phone").
   3. **Choose a destination: in Google Maps open a place → Share → MotoNav.** The destination card fills in.
   4. **Start navigation.** The first time it initialises the Navigation SDK and shows Google's terms — accept them.

The first start uses your real GPS. Under *Advanced / testing* you can still type `lat,lng`, run a
**Simulated route** (desk test), and use the display test buttons (no Google account needed).

### What the share feature understands

| Shared from Google Maps | Result |
|-------------------------|--------|
| A place (link, or "name + link" text) | Exact destination |
| Short links (`maps.app.goo.gl/...`) | Resolved over the network (Google hosts only), then as above |
| `geo:` links | Coordinates, or a name only |
| Directions with coordinates | The last point is the destination |
| Directions with place **names only** | Not enough information — the app says so and asks you to share the place itself |
| A map view with no place | Used as an *approximate* destination and labelled as such |

Google's link formats are not a stable API, so the parser is covered by unit tests (`ShareParserTest`)
and every failure ends in a message — never a wrong destination. Searching by name inside the app
(Google Places autocomplete) is a possible later addition.

## How it works

```
Navigation SDK ──(Messenger, ~1/s)──► NavInfoReceivingService ──► NavBridge ──► BleClient ──► ESP32
                                                   NavMapper: SDK Maneuver/NavState → device enums
```

| File | Role |
|------|------|
| `NavController.kt` | Initialises the SDK, sets the destination, starts guidance / simulator |
| `NavInfoReceivingService.kt` | Receives `NavInfo` from the SDK's turn-by-turn feed |
| `NavBridge.kt` | Builds NavCore/NavStreet from `NavInfo` + speed and hands them to BLE |
| `NavMapper.kt` | Pure mapping of Google maneuver/state values to the device protocol |
| `NavProtocol.kt` | Byte encoding of the BLE messages (mirror of `protocol/nav_protocol.h`) |
| `BleClient.kt` | Scan, connect, auto-reconnect, latest-wins write queue |
| `MainActivity.kt` | Buttons, permissions, on-screen log |
| `route/` | **Route follower** (guidance data from any routing service) and the **map feed** (route line + position for the device); see below |

## Map feed: the route line and position the device draws

The device's screen shows a mini-map with the route line and your position (north-up, no compass). The app now sends both,
in addition to the arrow, distance, street, distance left and ETA it already sent:

```
Navigation SDK route (getRouteSegments)  ─► NavBridge.onRoute ─┐
Navigation SDK road-snapped location (1 Hz) ─► NavBridge.onLocation ─► MapFeed ─► BleClient ─► NavRoute / NavPos ─► ESP32
```

| File | Role |
|------|------|
| `route/MapFeed.kt` | Decides when to send a route window (first time, after a reroute or reconnect, once the rider has used up the current one) and sends the position every update |
| `route/RouteWindowCodec.kt` | Encodes a window into BLE chunks (format: `protocol/README.md`) |
| `route/RouteSimplifier.kt` | Cuts a window to at most 96 points |
| `NavController.kt` | Reads the SDK's route segments after a route is found and on every route change; forwards locations |
| `BleClient.kt` | Discovers the two optional characteristics; sends NavCore and NavStreet first, then the position, then route chunks |

If the firmware has no map feed the app logs "This firmware has no map feed" and carries on as before. The Navigation SDK calls
used (`Navigator.getRouteSegments()`, `RouteSegment.getLatLngs()`, `addRouteChangedListener`) were checked against the real SDK 7.0.0 jar.
**Not yet tried on a phone with the device.** Check the on-screen log for "Map feed: route of N points" after starting a route.

## Route follower: the device's data without Google's Navigation SDK

The device only *displays*; it never navigates. `route/` produces the same values the Navigation SDK feed gives
(next manoeuvre, distance to it, street, distance left, ETA) from **a route fetched from any routing service
plus the phone's GPS fixes**. It speaks no instructions and draws no map. It is plain Kotlin with no Android or
Google imports, so it runs in ordinary JVM unit tests.

```
routing service ──JSON──► OsrmRouteParser ──► Route ──┐
                                                      ├─► RouteFollower.update(fix) ──► Snapshot ──► encodeCore() ──► BleClient
phone GPS fix (lat, lon, time, speed, accuracy, bearing) ─┘
```

| File | Role |
|------|------|
| `route/Route.kt` | A route: points, manoeuvre steps, planned durations. Adds a missing DEPART/ARRIVE |
| `route/RouteFollower.kt` | Snaps each GPS fix to the route; returns mode, manoeuvre, distance, street, remaining, ETA |
| `route/OsrmRouteParser.kt` | OSRM/Mapbox-style response (`steps=true`, `geometries=polyline6`) → `Route` |
| `route/PolylineCodec.kt`, `route/Geo.kt` | Encoded-polyline codec and distance maths |

**Using it** (this wiring is not in the app yet):

```kotlin
val follower = RouteFollower(OsrmRouteParser.parse(responseJson))     // polyline6 geometry
// on every location update:
val s = follower.update(RouteFollower.Fix(loc.latitude, loc.longitude, loc.elapsedRealtimeNanos / 1_000_000,
    if (loc.hasSpeed()) loc.speed.toDouble() else null,
    if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
    if (loc.hasBearing()) loc.bearing.toDouble() else null))
ble.sendCore(s.encodeCore(seq++)); if (street changed) ble.sendStreet(s.encodeStreet())
if (s.mode == NavProtocol.Mode.OFF_ROUTE) { follower.markRerouting(); /* fetch a route from here */ follower.replaceRoute(newRoute) }
```

What it handles: off route after 5 s more than 40 m away (then rerouting), arrival within 25 m, GPS noise (a fix
never moves progress backwards by jitter), fixes with poor accuracy (ignored), gaps such as tunnels, routes that
cross or overlap themselves, out-and-back roads (the fix's **bearing** tells the two lanes apart, so always pass
it), and an ETA scaled by the rider's real pace. U-turns are right-hand turns in left-hand traffic (India). All
thresholds are in `RouteFollower.Config` and are starting values to tune on real rides.

**Known limits / not verified**

- The parser is checked against **hand-written responses that follow OSRM's documented format**, not against live
  Ola Maps, Mappls or OSRM responses. Before relying on a service, run one real Indian route through it and check
  street names, roundabouts and U-turns. Ola Maps' exact response schema is unconfirmed.
- Via-points (more than one leg) are not announced; only the final destination is.
- Straight-ahead "new name" steps are kept (as the Google mapper does), so a long route can show many of them.
- Street names missing in the map data show as empty (an unnamed road with a reference, e.g. "NH 48", shows the reference).
- The route follower's 57 unit tests (`app/src/test/.../route/`) pass, standalone and inside the Android Gradle build. It is **not yet
  used as a data source** in the app: today the map feed uses the Google Navigation SDK's route.

## Bluetooth: don't pair MotoNav in Android's Bluetooth settings

Connect only through the app's **Connect to MotoNav** button. If you pair the device in Android's Bluetooth
settings, Android keeps a link open, the ESP32 stops advertising, and the app's scan sees nothing
("Bluetooth: scanning for MotoNav..." forever). Fix: Settings → Bluetooth → gear next to MotoNav →
**Forget**, press **EN** on the ESP32, then tap Disconnect → Connect in the app. (The app also falls back to
connecting directly to a paired MotoNav after 3 s of scanning.)

## Verified on a real phone (Samsung SM-G781B)

The Navigation SDK turn-by-turn feed works: `NavInfo` arrives about once a second with the state, maneuver,
distance to it, remaining distance, ETA and street name, and the maneuver mapping produced the expected
device values.

## Things to check on the first run

- `NavInfo` semantics: the code assumes `currentStep` is the **upcoming** maneuver and `distanceToCurrentStepMeters` the distance to it. Compare the on-screen log lines with what the SDK's own navigation UI shows.
- Two-wheeler routing is on by default (supported in India). Turn the switch off if the route request fails in your region.
- Voice guidance is set to **silent** for desk testing (`NavController.kt`).
- Does the feed keep flowing with the screen locked? Not stated in Google's docs — test it (milestone M5).

## Upgrading the SDK

`app/build.gradle.kts` pins Navigation SDK **7.0.0** (needs AGP ≥ 8.2). SDK 8.x needs **AGP ≥ 9.0.1**: bump the plugin in the root `build.gradle.kts` and the Gradle wrapper first.

## Unit tests

```bash
./gradlew :app:testDebugUnitTest
```
