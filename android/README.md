# MotoNav Android prototype

Small Kotlin app: connects to the ESP32 over BLE and forwards Google Navigation SDK turn-by-turn data to it.

**Status**

- All Kotlin sources **compile without errors** with the Kotlin compiler against the **real** Navigation SDK 7.0.0 and Android 36 jars. Only the AndroidX classes used by `MainActivity` were checked against small stand-ins.
- The **26 unit tests pass** (`ProtocolTest`, `ShareParserTest`).
- A full **Gradle build was not run** (Maven Central rate-limited the build sandbox), and the app has **not been run on a phone**. The first Android Studio sync/build may still surface Gradle, resource or runtime issues — send me the first error lines.

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
