package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol.Man
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Builds small synthetic routes (flat-earth, straight legs) and GPS fixes along them. */
internal object TestRoutes {

    /** Central Bengaluru. */
    val ORIGIN = LatLon(12.9716, 77.5946)

    fun offset(from: LatLon, bearingDeg: Double, distM: Double): LatLon {
        val b = Math.toRadians(bearingDeg)
        val north = cos(b) * distM
        val east = sin(b) * distM
        return LatLon(
            from.lat + north / Geo.METERS_PER_DEGREE,
            from.lon + east / (Geo.METERS_PER_DEGREE * cos(Math.toRadians(from.lat))),
        )
    }

    /** A straight leg. [maneuver] and [street] describe the manoeuvre at the START of the leg. */
    class Leg(
        val bearingDeg: Double,
        val lengthM: Double,
        val maneuver: Int,
        val street: String,
        val durationS: Double,
        val exit: Int = 0,
    )

    fun build(legs: List<Leg>, start: LatLon = ORIGIN, spacingM: Double = 10.0): Route {
        val points = arrayListOf(start)
        val steps = ArrayList<RouteStep>()
        for (leg in legs) {
            steps += RouteStep(leg.maneuver, leg.exit, leg.street, points.size - 1, leg.durationS)
            val from = points.last()
            val n = max(1, ceil(leg.lengthM / spacingM).toInt())
            for (k in 1..n) points += offset(from, leg.bearingDeg, leg.lengthM * k / n)
        }
        steps += RouteStep(Man.ARRIVE, 0, "", points.size - 1, 0.0)
        return Route(points, steps)
    }

    /** 1000 m north, 1000 m east (RIGHT turn), 500 m north (LEFT turn). 10 m/s plan, 250 s. */
    fun lShape(): Route = build(
        listOf(
            Leg(0.0, 1000.0, Man.DEPART, "MG Road", 100.0),
            Leg(90.0, 1000.0, Man.RIGHT, "Cunningham Road", 100.0),
            Leg(0.0, 500.0, Man.LEFT, "Ring Road", 50.0),
        ),
    )

    /** Direction of travel (degrees clockwise from north) of the route at [progressM]. */
    fun bearingAt(route: Route, progressM: Double): Double {
        val i = route.segmentAt(progressM.coerceIn(0.0, route.totalM))
        val a = route.points[i]
        val b = route.points[i + 1]
        val dx = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    /**
     * A fix [progressM] along [route], pushed [northM]/[eastM] metres away from it.
     * With [withBearing] it also carries the direction of travel, as a phone's GPS does while moving.
     */
    fun fix(
        route: Route,
        progressM: Double,
        timeMs: Long,
        northM: Double = 0.0,
        eastM: Double = 0.0,
        speedMps: Double? = 10.0,
        accuracyM: Double? = 5.0,
        withBearing: Boolean = false,
    ): RouteFollower.Fix {
        val p = route.pointAt(progressM)
        val q = offset(offset(p, 0.0, northM), 90.0, eastM)
        val bearing = if (withBearing) bearingAt(route, progressM) else null
        return RouteFollower.Fix(q.lat, q.lon, timeMs, speedMps, accuracyM, bearing)
    }
}
