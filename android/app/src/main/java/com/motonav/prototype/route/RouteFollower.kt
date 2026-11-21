package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol
import com.motonav.prototype.NavProtocol.Mode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Follows a [Route] with GPS fixes and produces exactly what the MotoNav device shows: the next
 * manoeuvre, the distance to it, the street, the distance left and the ETA. It does not speak, draw
 * a map or choose routes: the phone asks a routing service for a [Route], and this class turns
 * "where am I now" into display values.
 *
 * Usage:
 * ```
 * val follower = RouteFollower(route)
 * // on every GPS fix (about once a second):
 * val s = follower.update(RouteFollower.Fix(lat, lon, timeMs, speedMps, accuracyM))
 * ble.sendCore(s.encodeCore(seq++))
 * if (s.mode == NavProtocol.Mode.OFF_ROUTE) {
 *     follower.markRerouting()            // device shows "Rerouting"
 *     fetchRoute(from = here).also { follower.replaceRoute(it) }
 * }
 * ```
 *
 * Thread safety: methods are synchronised. Timestamps only need to be on one consistent clock.
 */
class RouteFollower(initialRoute: Route, private val config: Config = Config()) {

    /** Tunable thresholds. The defaults are starting points to tune on real rides. */
    data class Config(
        /** Further than this from the route counts as "possibly off route". */
        val offRouteThresholdM: Double = 40.0,
        /** ...and it must stay that way this long before the mode becomes OFF_ROUTE. */
        val offRouteSeconds: Double = 5.0,
        /** Once off route, the rider must come back within threshold * this to count as back on route. */
        val recoverFactor: Double = 0.6,
        /** Within this distance of the end the trip is ARRIVED. */
        val arriveRadiusM: Double = 25.0,
        /** Fixes with a worse accuracy than this are ignored. */
        val maxAccuracyM: Double = 80.0,
        /** How far behind the current progress a fix may snap before it is treated as a real step back. */
        val backSlackM: Double = 40.0,
        /** The search window ahead of the rider is at least this long. */
        val minLookAheadM: Double = 300.0,
        /** Used to size the search window after a gap in fixes (tunnel, lost signal). */
        val maxSpeedMps: Double = 45.0,
        /** The ETA is scaled by the rider's pace against the plan, clamped to this range. */
        val minPace: Double = 0.7,
        val maxPace: Double = 1.5,
    )

    /**
     * One GPS fix. [speedMps], [accuracyM] and [bearingDeg] are optional; pass them whenever the phone has them
     * (`Location.getSpeed()`, `getAccuracy()`, `getBearing()`). The bearing is the direction of travel in degrees
     * clockwise from north. It is what tells the two lanes of an out-and-back road apart, since position alone
     * cannot when they are a few metres apart.
     */
    data class Fix(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
        val speedMps: Double? = null,
        val accuracyM: Double? = null,
        val bearingDeg: Double? = null,
    )

    /** Everything the device needs, plus a few extras for the phone screen and later features. */
    data class Snapshot(
        /** One of [NavProtocol.Mode]. */
        val mode: Int,
        /** Upcoming manoeuvre, one of [NavProtocol.Man]. */
        val maneuver: Int,
        val roundaboutExit: Int,
        /** Road you turn onto at the upcoming manoeuvre. May be empty. */
        val street: String,
        val distToManeuverM: Long,
        val remainingM: Long,
        val etaMinutes: Int,
        /** Metres travelled along the route. */
        val progressM: Double,
        /** Distance from the fix to the route, in metres. */
        val offRouteM: Double,
        /** The fix snapped onto the route (for the later map view). */
        val position: LatLon,
        /** Index of the upcoming step in [Route.steps]. */
        val stepIndex: Int,
        val speedKmhX10: Int,
    ) {
        /** The 18-byte NavCore message for the device. */
        fun encodeCore(seq: Int): ByteArray = NavProtocol.encodeCore(
            mode = mode,
            maneuver = maneuver,
            roundaboutExit = roundaboutExit,
            seq = seq,
            distToManeuverM = distToManeuverM,
            remainingM = remainingM,
            etaMinutes = etaMinutes,
            speedKmhX10 = speedKmhX10,
        )

        /** The ASCII street name for the NavStreet message. */
        fun encodeStreet(): ByteArray = NavProtocol.encodeStreet(street)
    }

    private class Hit(val seg: Int, val t: Double, val distM: Double)

    var route: Route = initialRoute
        private set

    private var progressM = 0.0
    private var needGlobalSearch = true
    private var haveFix = false
    private var lastFixMs = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var smoothedSpeedMps = 0.0

    private var lastOnRouteMs = -1L
    private var offSinceMs = -1L
    private var offRoute = false
    private var rerouting = false
    private var arrived = false

    private var paceStarted = false
    private var paceStartMs = 0L
    private var plannedAtStartS = 0.0

    private var last: Snapshot? = null

    /** Feed one GPS fix; returns the new display state. Fixes with poor accuracy are ignored. */
    @Synchronized
    fun update(fix: Fix): Snapshot {
        val prev = last
        if (arrived && prev != null) return prev

        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > config.maxAccuracyM) {
            return prev ?: snapshot(route.points.first(), 0.0, fix.timeMs)
        }

        val dtS = if (haveFix) ((fix.timeMs - lastFixMs) / 1000.0).coerceAtLeast(0.0) else 0.0
        updateSpeed(fix, dtS)

        // How far the rider could have got since we last knew where they were on the route.
        val sinceOnRouteS = if (lastOnRouteMs >= 0) ((fix.timeMs - lastOnRouteMs) / 1000.0).coerceAtLeast(0.0) else 0.0
        val threshold = max(config.offRouteThresholdM, 1.5 * (accuracy ?: 0.0))
        // A bearing is only meaningful while actually moving.
        val moving = (fix.speedMps ?: smoothedSpeedMps) >= MIN_BEARING_SPEED_MPS
        val riderBearing = if (moving && fix.bearingDeg != null && fix.bearingDeg.isFinite()) fix.bearingDeg else Double.NaN
        val hit = locate(fix.lat, fix.lon, sinceOnRouteS, threshold, riderBearing)
        val onRoute = hit.distM <= threshold

        if (onRoute) {
            if (offRoute && hit.distM > threshold * config.recoverFactor) {
                // Still too far to call it "back on route": stay off route (hysteresis).
            } else {
                offRoute = false
                offSinceMs = -1L
                val candidate = progressOf(hit)
                progressM = when {
                    needGlobalSearch -> candidate
                    candidate >= progressM - config.backSlackM -> max(progressM, candidate)
                    else -> candidate   // a real step back, e.g. rejoining earlier on the route
                }
                needGlobalSearch = false
                lastOnRouteMs = fix.timeMs
                if (!paceStarted) startPace(fix.timeMs)
            }
        } else {
            if (offSinceMs < 0) offSinceMs = fix.timeMs
            if (fix.timeMs - offSinceMs >= config.offRouteSeconds * 1000) offRoute = true
        }

        if (!needGlobalSearch) {
            val toEnd = Geo.haversineM(fix.lat, fix.lon, route.lat[route.points.lastIndex], route.lon[route.points.lastIndex])
            val remaining = route.totalM - progressM
            if (!offRoute && remaining <= config.arriveRadiusM) {
                arrived = true
            } else if (toEnd <= config.arriveRadiusM && progressM >= 0.5 * route.totalM) {
                arrived = true
            }
        }

        haveFix = true
        lastFixMs = fix.timeMs
        lastLat = fix.lat
        lastLon = fix.lon

        val snap = snapshot(pointOf(hit), hit.distM, fix.timeMs)
        last = snap
        return snap
    }

    /**
     * Call when a reroute request has been sent. The mode becomes REROUTING (the device shows it)
     * until [replaceRoute] is called.
     */
    @Synchronized
    fun markRerouting() {
        rerouting = true
    }

    /** Switches to a freshly fetched route (after off-route, or a new destination). */
    @Synchronized
    fun replaceRoute(newRoute: Route) {
        route = newRoute
        progressM = 0.0
        needGlobalSearch = true
        lastOnRouteMs = -1L
        offSinceMs = -1L
        offRoute = false
        rerouting = false
        arrived = false
        paceStarted = false
        last = null
    }

    // ---- internals --------------------------------------------------------------------------

    private fun updateSpeed(fix: Fix, dtS: Double) {
        val reported = fix.speedMps?.takeIf { it >= 0.0 && it.isFinite() }
        val derived = if (haveFix && dtS > 0.0) Geo.haversineM(lastLat, lastLon, fix.lat, fix.lon) / dtS else null
        val v = reported ?: derived ?: return
        smoothedSpeedMps = if (!haveFix || smoothedSpeedMps == 0.0) v else 0.7 * smoothedSpeedMps + 0.3 * v
    }

    /**
     * Finds where on the route the fix is. Searches near the previous progress first, which keeps a route
     * that crosses or overlaps itself (a block driven twice, an out-and-back ride) from jumping to the wrong
     * pass; falls back to the whole route. When two places on the route are equally close, the one nearest
     * the current progress (and ahead rather than behind) wins.
     */
    private fun locate(lat: Double, lon: Double, sinceOnRouteS: Double, threshold: Double, riderBearing: Double): Hit {
        val r = route
        if (!needGlobalSearch) {
            val ahead = max(config.minLookAheadM, min(sinceOnRouteS, 120.0) * config.maxSpeedMps)
            val from = r.segmentAt(progressM - config.backSlackM)
            val to = r.segmentAt(progressM + ahead)
            val windowed = search(lat, lon, from, to, progressM, riderBearing)
            if (windowed.distM <= threshold) return windowed
        }
        return search(lat, lon, 0, r.points.size - 2, if (needGlobalSearch) Double.NaN else progressM, riderBearing)
    }

    /**
     * Closest point on segments [from]..[to].
     *
     * Segments are ranked by distance, plus [MISALIGN_PENALTY_M] when the rider's [riderBearing] (NaN = unknown)
     * differs from the segment's direction by more than [MISALIGNED_DEG]: the other lane of an out-and-back road
     * must not win just because GPS drift puts the rider nearer to it. Candidates whose ranks are within [TIE_M]
     * count as equally good; among those the one with the best closeness to [prefer] (NaN = no preference:
     * earliest wins) is taken. The returned distance is the real distance, without the penalty.
     */
    private fun search(lat: Double, lon: Double, from: Int, to: Int, prefer: Double, riderBearing: Double): Hit {
        val r = route
        val cosLat = max(cos(Math.toRadians(lat)), 1e-6)
        val tOut = DoubleArray(1)
        var bestSeg = from
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        var bestRank = Double.MAX_VALUE
        var bestScore = Double.MAX_VALUE
        var minRank = Double.MAX_VALUE
        for (i in from..to) {
            if (minRank < Double.MAX_VALUE) {
                // Cheap bounding-box reject: rank is never below the real distance, so a segment further than
                // minRank + TIE_M away cannot be in the tie band.
                val latMargin = (minRank + TIE_M) / Geo.METERS_PER_DEGREE
                val lonMargin = latMargin / cosLat
                val aLat = r.lat[i]
                val bLat = r.lat[i + 1]
                if (lat < min(aLat, bLat) - latMargin || lat > max(aLat, bLat) + latMargin) continue
                val aLon = r.lon[i]
                val bLon = r.lon[i + 1]
                if (lon < min(aLon, bLon) - lonMargin || lon > max(aLon, bLon) + lonMargin) continue
            }
            val d = Geo.distanceToSegmentM(lat, lon, r.lat[i], r.lon[i], r.lat[i + 1], r.lon[i + 1], tOut)
            val rank = d + misalignmentPenalty(i, riderBearing, cosLat)
            if (rank < minRank) minRank = rank
            if (rank > minRank + TIE_M) continue
            val progress = r.cumM[i] + tOut[0] * (r.cumM[i + 1] - r.cumM[i])
            val score = when {
                prefer.isNaN() -> progress
                progress >= prefer -> progress - prefer
                else -> (prefer - progress) * 2.0     // behind counts double: the rider is moving forward
            }
            // Take it if the current pick is no longer within the tie band, or this one is a better match.
            if (bestRank > minRank + TIE_M || score < bestScore) {
                bestSeg = i
                bestT = tOut[0]
                bestD = d
                bestRank = rank
                bestScore = score
            }
        }
        return Hit(bestSeg, bestT, bestD)
    }

    private fun misalignmentPenalty(segment: Int, riderBearing: Double, cosLat: Double): Double {
        if (riderBearing.isNaN()) return 0.0
        val r = route
        val dy = r.lat[segment + 1] - r.lat[segment]
        val dx = (r.lon[segment + 1] - r.lon[segment]) * cosLat
        if (dx * dx + dy * dy < 1e-12) return 0.0          // ~10 cm: no meaningful direction
        val segmentBearing = Math.toDegrees(atan2(dx, dy))
        var diff = abs(segmentBearing - riderBearing) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return if (diff > MISALIGNED_DEG) MISALIGN_PENALTY_M else 0.0
    }

    private fun progressOf(hit: Hit): Double {
        val r = route
        return r.cumM[hit.seg] + hit.t * (r.cumM[hit.seg + 1] - r.cumM[hit.seg])
    }

    private fun pointOf(hit: Hit): LatLon {
        val r = route
        return LatLon(
            r.lat[hit.seg] + hit.t * (r.lat[hit.seg + 1] - r.lat[hit.seg]),
            r.lon[hit.seg] + hit.t * (r.lon[hit.seg + 1] - r.lon[hit.seg]),
        )
    }

    private fun startPace(timeMs: Long) {
        paceStarted = true
        paceStartMs = timeMs
        plannedAtStartS = route.totalDurationS - route.remainingDurationS(progressM)
    }

    /** Rider's actual time against the plan since the trip began, so a slow or fast rider gets a fair ETA. */
    private fun pace(timeMs: Long): Double {
        if (!paceStarted) return 1.0
        val plannedElapsed = (route.totalDurationS - route.remainingDurationS(progressM)) - plannedAtStartS
        val actualElapsed = (timeMs - paceStartMs) / 1000.0
        if (plannedElapsed < 120.0 || actualElapsed < 120.0) return 1.0
        return (actualElapsed / plannedElapsed).coerceIn(config.minPace, config.maxPace)
    }

    private fun estimateEtaS(remainingM: Double, timeMs: Long): Double {
        if (route.totalDurationS > 0.0) return route.remainingDurationS(progressM) * pace(timeMs)
        val v = if (smoothedSpeedMps > 2.0) smoothedSpeedMps else FALLBACK_SPEED_MPS
        return remainingM / v
    }

    private fun snapshot(position: LatLon, offRouteM: Double, timeMs: Long): Snapshot {
        val r = route
        val mode = when {
            arrived -> Mode.ARRIVED
            rerouting -> Mode.REROUTING
            offRoute -> Mode.OFF_ROUTE
            else -> Mode.NAV
        }
        val current = lastAtOrBelow(r.stepStartM, progressM).coerceAtLeast(0)
        val upcoming = if (arrived) r.steps.lastIndex else min(current + 1, r.steps.lastIndex)
        val step = r.steps[upcoming]
        val remaining = if (arrived) 0.0 else max(0.0, r.totalM - progressM)
        val toManeuver = if (arrived) 0.0 else max(0.0, r.stepStartM[upcoming] - progressM)
        val etaS = if (arrived) 0.0 else estimateEtaS(remaining, timeMs)
        return Snapshot(
            mode = mode,
            maneuver = step.maneuver,
            roundaboutExit = step.roundaboutExit,
            street = step.street,
            distToManeuverM = toManeuver.roundToLong(),
            remainingM = remaining.roundToLong(),
            etaMinutes = ((etaS + 30.0) / 60.0).toInt().coerceAtLeast(0),
            progressM = progressM,
            offRouteM = offRouteM,
            position = position,
            stepIndex = upcoming,
            speedKmhX10 = (smoothedSpeedMps * 36.0).roundToInt().coerceAtLeast(0),
        )
    }

    private companion object {
        /** ~30 km/h, used for the ETA only when the route carries no durations. */
        const val FALLBACK_SPEED_MPS = 8.3

        /** Places on the route closer than this to the best match are treated as equally good matches. */
        const val TIE_M = 3.0

        /** Below this speed the GPS bearing is noise and is not used. */
        const val MIN_BEARING_SPEED_MPS = 3.0

        /** A segment pointing more than this far from the rider's direction of travel is "the other lane". */
        const val MISALIGNED_DEG = 100.0

        /** Ranking penalty for such a segment, large enough to beat typical GPS drift between two lanes. */
        const val MISALIGN_PENALTY_M = 20.0
    }
}
