package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol

/**
 * Decides what the device's map needs and when: the **route window** (a short, simplified piece of the route around the
 * rider, NavRoute) and the **position** (NavPos, every update). It does not talk to Bluetooth itself: it hands bytes to a
 * [Sink]. Pure Kotlin, so it is unit-tested without a phone.
 *
 * - A window goes out the first time, after the route changes, after the device (re)connects, and again once the rider has
 *   used up [Config.resendAfterM] metres of it. Each window gets a new id, and positions carry the id they belong to.
 * - A position goes out on every [onPosition] call (about once a second).
 * - Not thread-safe: call from one thread (NavBridge serialises it).
 */
class MapFeed(private val sink: Sink, private val config: Config = Config()) {

    interface Sink {
        /** All chunks of one window, in order. A newer call replaces anything still waiting. */
        fun sendRoute(chunks: List<ByteArray>)
        fun sendPos(bytes: ByteArray)
    }

    data class Config(
        /** How much of the route behind the rider is included (so the map shows where they came from). */
        val behindM: Double = 200.0,
        /** How much of the route ahead is included. */
        val aheadM: Double = 2500.0,
        /** Send a fresh window once the rider is this far past the start of the current one. */
        val resendAfterM: Double = 1000.0,
        val maxPoints: Int = NavProtocol.ROUTE_MAX_POINTS,
        /** Bytes per Bluetooth write. 20 works on every phone; raise it after negotiating a larger MTU. */
        val writePayload: Int = NavProtocol.DEFAULT_WRITE_PAYLOAD,
        /** Farther than this from the route, the rider is off it: keep the last progress and wait for a new route. */
        val lostM: Double = 150.0,
    )

    private var route: Route? = null
    private var endsAtDestination = true
    private var progressM = 0.0
    private var haveProgress = false
    private var windowStartM = 0.0
    private var windowId = 0          // the window the device holds; 0 = none
    private var nextId = 1
    private var seq = 0
    private var needWindow = false

    companion object {
        /**
         * A [Route] from raw points (for example the Navigation SDK's route segments, whose joints repeat a point).
         * Returns null when there are fewer than two distinct points.
         */
        fun routeFrom(points: List<LatLon>): Route? {
            val clean = ArrayList<LatLon>(points.size)
            for (p in points) if (clean.isEmpty() || Geo.haversineM(clean.last(), p) >= 0.5) clean.add(p)
            return if (clean.size >= 2) Route(clean, emptyList()) else null
        }
    }

    /** The id carried by positions right now (0 = no route window on the device). */
    val currentWindowId: Int get() = windowId

    /** A new route (first route, or after a reroute). [endsAtDestination]: the route's last point is the destination. */
    fun setRoute(route: Route, endsAtDestination: Boolean = true) {
        this.route = route
        this.endsAtDestination = endsAtDestination
        haveProgress = false
        progressM = 0.0
        needWindow = true
    }

    fun clearRoute() {
        route = null
        windowId = 0
        needWindow = false
        haveProgress = false
    }

    /** The device (re)connected: it has no window, so send one again at the next position. */
    fun onConnected() {
        windowId = 0
        if (route != null) needWindow = true
    }

    /** Call with every location update (about once a second). */
    fun onPosition(lat: Double, lon: Double) {
        val r = route
        if (r != null) {
            val dist = track(r, lat, lon)
            val offRoute = dist > config.lostM
            val used = progressM - windowStartM > config.resendAfterM || progressM < windowStartM - 50.0
            if (!offRoute && (needWindow || windowId == 0 || used)) sendWindow(r)
        }
        sink.sendPos(NavProtocol.encodePos(windowId, seq++ and 0xFF, NavProtocol.toE6(lat), NavProtocol.toE6(lon)))
    }

    // ---- internals --------------------------------------------------------------------------

    private fun sendWindow(r: Route) {
        val start = maxOf(0.0, progressM - config.behindM)
        val end = minOf(r.totalM, progressM + config.aheadM)
        val pts = ArrayList<LatLon>()
        pts.add(r.pointAt(start))
        val first = lastAtOrBelow(r.cumM, start) + 1
        val last = lastAtOrBelow(r.cumM, end)
        for (i in first..last) {
            val p = r.points[i]
            if (Geo.haversineM(pts.last(), p) >= 0.5) pts.add(p)
        }
        val tail = r.pointAt(end)
        if (Geo.haversineM(pts.last(), tail) >= 0.5) pts.add(tail)
        if (pts.size < 2) pts.add(tail)

        // Simplify to the point limit; if splitting very long steps pushes it over, simplify a little harder.
        var limit = config.maxPoints
        var stream: ByteArray? = null
        while (stream == null) {
            val simple = RouteSimplifier.simplifyToMax(pts, limit)
            stream = try {
                RouteWindowCodec.encodeStream(simple, endsAtDestination && end >= r.totalM - 1.0)
            } catch (e: IllegalArgumentException) {
                if (limit <= 4) throw e
                limit = (limit * 0.8).toInt().coerceAtLeast(4)
                null
            }
        }
        val id = nextId
        nextId = if (nextId >= 255) 1 else nextId + 1
        val chunks = RouteWindowCodec.chunk(stream, id, config.writePayload)
        windowId = id
        windowStartM = start
        needWindow = false
        sink.sendRoute(chunks)
    }

    /**
     * Updates [progressM] from the rider's position and returns the distance from the route in metres.
     * Searches near the last progress first (so a route that crosses itself does not jump), then the whole route.
     */
    private fun track(r: Route, lat: Double, lon: Double): Double {
        var best = Double.MAX_VALUE
        var bestProgress = progressM
        val t = DoubleArray(1)
        fun scan(from: Int, to: Int) {
            for (i in from..to) {
                val d = Geo.distanceToSegmentM(lat, lon, r.lat[i], r.lon[i], r.lat[i + 1], r.lon[i + 1], t)
                if (d < best) {
                    best = d
                    bestProgress = r.cumM[i] + t[0] * (r.cumM[i + 1] - r.cumM[i])
                }
            }
        }
        if (haveProgress) scan(r.segmentAt(progressM - 150.0), r.segmentAt(progressM + 3000.0))
        if (!haveProgress || best > config.lostM) {
            best = Double.MAX_VALUE
            scan(0, r.points.size - 2)
        }
        if (best <= config.lostM) {
            progressM = bestProgress
            haveProgress = true
        }
        return best
    }
}
