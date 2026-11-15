# MotoNav Android prototype

Small Kotlin app: connects to the ESP32 over BLE and forwards Google Navigation SDK turn-by-turn data to it.

**Status:** all code written against the real Navigation SDK 7.0.0 API (signatures read from the SDK itself). **It has not been compiled yet** — the build sandbox was rate-limited by Maven Central. Open it in Android Studio first and expect to fix a few small compile errors. Pure-logic tests (`ProtocolTest`) are included.

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
   1. Grant the permissions.
   2. **Connect to MotoNav** (ESP32 powered and showing "Waiting for phone").
   3. **Test buttons** (Left 300m, Right 80m, …) — proves the Bluetooth path with no Google account.
   4. **Init Navigation SDK** → accept Google's terms → wait for "Navigator ready".
   5. Edit the start/destination coordinates to your city, then **Simulated route** (desk test) or **Real navigation**.

Start and destination are plain `lat,lng`. The default values are in Bengaluru — change them.

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
