package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol.Man

/**
 * One manoeuvre of a route.
 *
 * Semantics match the device protocol: the step describes the manoeuvre that happens at
 * [startIndex], and [street] is the road you turn ONTO there (what the device shows next to the arrow).
 *
 * @property maneuver one of [com.motonav.prototype.NavProtocol.Man]
 * @property roundaboutExit exit number for roundabouts, 0 when unknown
 * @property startIndex index into [Route.points] where the manoeuvre happens
 * @property durationS the provider's planned travel time from this manoeuvre to the next one
 */
class RouteStep(
    val maneuver: Int,
    val roundaboutExit: Int,
    val street: String,
    val startIndex: Int,
    val durationS: Double,
)

/**
 * A route as the follower needs it: a polyline plus the manoeuvres along it. Source-independent:
 * [OsrmRouteParser] builds one from an OSRM/Mapbox-style response, tests build them by hand.
 *
 * The constructor tidies the steps so the follower can rely on them:
 * steps are ordered along the route, the first is a DEPART at point 0, and the last is an ARRIVE at the
 * final point (both are added if the provider left them out).
 */
class Route(val points: List<LatLon>, steps: List<RouteStep>) {

    val steps: List<RouteStep>

    internal val lat: DoubleArray
    internal val lon: DoubleArray

    /** Distance along the route, in metres, at each point. */
    internal val cumM: DoubleArray

    /** Distance along the route at which each step's manoeuvre happens. */
    internal val stepStartM: DoubleArray

    val totalM: Double
    val totalDurationS: Double

    /** durationFrom[j] = planned seconds from the start of step j to the end of the route. */
    private val durationFrom: DoubleArray

    init {
        require(points.size >= 2) { "A route needs at least two points" }
        val ordered = steps.sortedBy { it.startIndex }
        require(ordered.all { it.startIndex in points.indices }) { "A step points outside the route geometry" }

        val list = ordered.toMutableList()
        if (list.isEmpty() || list.first().startIndex > 0) {
            list.add(0, RouteStep(Man.DEPART, 0, "", 0, 0.0))
        }
        val last = list.last()
        val endsWithArrival = last.maneuver in Man.ARRIVE..Man.ARRIVE_RIGHT && last.startIndex == points.lastIndex
        if (!endsWithArrival) list.add(RouteStep(Man.ARRIVE, 0, "", points.lastIndex, 0.0))
        this.steps = list

        lat = DoubleArray(points.size) { points[it].lat }
        lon = DoubleArray(points.size) { points[it].lon }
        cumM = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumM[i] = cumM[i - 1] + Geo.haversineM(lat[i - 1], lon[i - 1], lat[i], lon[i])
        }
        totalM = cumM[points.lastIndex]
        stepStartM = DoubleArray(list.size) { cumM[list[it].startIndex] }

        durationFrom = DoubleArray(list.size + 1)
        for (j in list.indices.reversed()) durationFrom[j] = durationFrom[j + 1] + list[j].durationS.coerceAtLeast(0.0)
        totalDurationS = durationFrom[0]
    }

    /** Index of the segment (points[i] -> points[i+1]) that contains [distM] metres along the route. */
    internal fun segmentAt(distM: Double): Int = lastAtOrBelow(cumM, distM).coerceIn(0, points.size - 2)

    /** The position [progressM] metres along the route (clamped to the route). */
    fun pointAt(progressM: Double): LatLon {
        val p = progressM.coerceIn(0.0, totalM)
        val i = segmentAt(p)
        val span = cumM[i + 1] - cumM[i]
        val t = if (span <= 0.0) 0.0 else ((p - cumM[i]) / span).coerceIn(0.0, 1.0)
        return LatLon(lat[i] + t * (lat[i + 1] - lat[i]), lon[i] + t * (lon[i + 1] - lon[i]))
    }

    /** Planned seconds still to go from [progressM], prorating the step the rider is in. */
    fun remainingDurationS(progressM: Double): Double {
        val p = progressM.coerceIn(0.0, totalM)
        val c = lastAtOrBelow(stepStartM, p).coerceAtLeast(0)
        val end = if (c + 1 < steps.size) stepStartM[c + 1] else totalM
        val span = end - stepStartM[c]
        val frac = if (span <= 0.0) 0.0 else ((end - p) / span).coerceIn(0.0, 1.0)
        return steps[c].durationS.coerceAtLeast(0.0) * frac + durationFrom[c + 1]
    }
}

/** Greatest index i with arr[i] <= x, or -1 if x is below every element. [arr] must be sorted ascending. */
internal fun lastAtOrBelow(arr: DoubleArray, x: Double): Int {
    var lo = 0
    var hi = arr.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (arr[mid] <= x) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}
