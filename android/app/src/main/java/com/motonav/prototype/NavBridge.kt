package com.motonav.prototype

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode

/**
 * Glue between the Navigation SDK's turn-by-turn feed and the BLE client.
 * A process-wide singleton because the SDK delivers NavInfo to a Service, not to the Activity.
 */
object NavBridge {
    @Volatile var ble: BleClient? = null
    @Volatile var log: (String) -> Unit = {}

    /** Latest speed from the road-snapped location (NavInfo has no speed). */
    @Volatile var speedKmhX10: Int = 0

    @Volatile var arrived: Boolean = false

    private var seq = 0
    private var lastStreet = ""
    private var lastLogMs = 0L

    @Synchronized
    fun onNavInfo(info: NavInfo) {
        val step = info.currentStep
        val sdkManeuver = step?.maneuver ?: 0
        val deviceManeuver = NavMapper.maneuver(sdkManeuver)
        val exit = if (NavMapper.isRoundabout(deviceManeuver)) step?.roundaboutTurnNumber ?: 0 else 0
        val distToStep = info.distanceToCurrentStepMeters ?: 0
        val remaining = info.distanceToFinalDestinationMeters ?: info.distanceToNextDestinationMeters ?: 0
        val etaSec = info.timeToFinalDestinationSeconds ?: info.timeToNextDestinationSeconds
        val mode = NavMapper.mode(info.navState, arrived)

        val core = NavProtocol.encodeCore(
            mode = mode,
            maneuver = deviceManeuver,
            roundaboutExit = exit,
            seq = seq++,
            distToManeuverM = distToStep.toLong(),
            remainingM = remaining.toLong(),
            etaMinutes = NavMapper.etaMinutes(etaSec),
            speedKmhX10 = speedKmhX10,
        )

        val street = NavProtocol.toAsciiStreet(step?.fullRoadName ?: step?.simpleRoadName)
        if (street != lastStreet) {
            lastStreet = street
            ble?.sendStreet(street.toByteArray(Charsets.US_ASCII))
        }
        ble?.sendCore(core)

        val now = System.currentTimeMillis()
        if (now - lastLogMs >= 2000) {   // keep the on-screen log readable
            lastLogMs = now
            log("NavInfo state=${info.navState} sdkMan=$sdkManeuver->dev=$deviceManeuver " +
                "d=${distToStep}m rem=${remaining}m eta=${NavMapper.etaMinutes(etaSec)}min " +
                "v=${speedKmhX10 / 10}km/h street=\"$street\"")
        }
    }

    @Synchronized
    fun sendMode(mode: Int) {
        val core = NavProtocol.encodeCore(
            mode = mode,
            maneuver = if (mode == Mode.ARRIVED) Man.ARRIVE else Man.NONE,
            roundaboutExit = 0,
            seq = seq++,
            distToManeuverM = 0,
            remainingM = 0,
            etaMinutes = 0,
            speedKmhX10 = speedKmhX10,
        )
        ble?.sendCore(core)
    }

    /** Called when the phone (re)connects to the device so it gets the current street name again. */
    @Synchronized
    fun onBleConnected() {
        if (lastStreet.isNotEmpty()) ble?.sendStreet(lastStreet.toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    fun reset() {
        arrived = false
        lastStreet = ""
    }
}
