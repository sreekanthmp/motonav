package com.motonav.prototype

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode
import com.motonav.prototype.route.LatLon
import com.motonav.prototype.route.MapFeed

/**
 * Glue between the Navigation SDK's turn-by-turn feed and the BLE client.
 * A process-wide singleton because the SDK delivers NavInfo to a Service, not to the Activity.
 */
object NavBridge {
    @Volatile var ble: BleClient? = null
    @Volatile var log: (String) -> Unit = {}

    /** Readable "what is happening now" text for the phone screen. */
    @Volatile var onStatus: (String) -> Unit = {}

    /** Latest speed from the road-snapped location (NavInfo has no speed). */
    @Volatile var speedKmhX10: Int = 0

    @Volatile var arrived: Boolean = false

    /** How many NavInfo messages arrived from the Navigation SDK so far (used to detect a silent feed). */
    @Volatile var messageCount: Int = 0
        private set

    private var seq = 0
    private var lastStreet = ""
    private var lastLogMs = 0L

    /** Route line + position for the device's map. Used only under this object's lock. */
    private val mapFeed = MapFeed(object : MapFeed.Sink {
        override fun sendRoute(chunks: List<ByteArray>) {
            ble?.sendRoute(chunks)
        }

        override fun sendPos(bytes: ByteArray) {
            ble?.sendPos(bytes)
        }
    })

    @Synchronized
    fun onNavInfo(info: NavInfo) {
        val step = info.currentStep
        val sdkManeuver = step?.maneuver ?: 0
        val deviceManeuver = NavMapper.maneuver(sdkManeuver)
        val exit = if (NavMapper.isRoundabout(deviceManeuver)) step?.roundaboutTurnNumber ?: 0 else 0
        val distToStep = info.distanceToCurrentStepMeters ?: 0
        val remaining = info.distanceToFinalDestinationMeters ?: info.distanceToNextDestinationMeters ?: 0
        val etaSec = info.timeToFinalDestinationSeconds ?: info.timeToNextDestinationSeconds
        val mode = NavMapper.mode(info.navState, arrived, hasStep = step != null)
        messageCount++

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

        val line = NavMapper.statusLine(
            mode, deviceManeuver, exit, distToStep, street, remaining, NavMapper.etaMinutes(etaSec),
        )
        onStatus(line + if (ble?.isConnected == true) "" else "\n(MotoNav display not connected)")

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
        onStatus(NavMapper.statusLine(mode, Man.NONE, 0, 0, "", 0, 0))
    }

    /** Called when the phone (re)connects to the device so it gets the current street name and route again. */
    @Synchronized
    fun onBleConnected() {
        if (lastStreet.isNotEmpty()) ble?.sendStreet(lastStreet.toByteArray(Charsets.US_ASCII))
        mapFeed.onConnected()
    }

    /** A new route (first route, reroute, traffic change): the points of the whole route. */
    @Synchronized
    fun onRoute(points: List<LatLon>) {
        val route = MapFeed.routeFrom(points)
        if (route == null) {
            log("Map feed: the route has no usable points")
            return
        }
        mapFeed.setRoute(route)
        log("Map feed: route of ${route.points.size} points, ${route.totalM.toInt()} m")
    }

    /** The rider's position, about once a second while navigating. */
    @Synchronized
    fun onLocation(lat: Double, lon: Double) {
        mapFeed.onPosition(lat, lon)
    }

    @Synchronized
    fun reset() {
        arrived = false
        lastStreet = ""
        mapFeed.clearRoute()
    }
}
