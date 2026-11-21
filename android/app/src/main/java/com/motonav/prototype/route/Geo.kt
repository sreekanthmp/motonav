package com.motonav.prototype.route

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 position in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Small geometry helpers with no Android or Google dependencies.
 *
 * Distances to a segment use a flat-earth projection centred on the query point. At street scale
 * (a few hundred metres) that is accurate to centimetres, and it is far cheaper than great-circle
 * maths when scanning thousands of segments.
 */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Metres per degree of latitude (and of longitude at the equator). */
    const val METERS_PER_DEGREE = EARTH_RADIUS_M * PI / 180.0

    /** Great-circle distance in metres. */
    fun haversineM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = p2 - p1
        val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun haversineM(a: LatLon, b: LatLon): Double = haversineM(a.lat, a.lon, b.lat, b.lon)

    /**
     * Distance in metres from point P to the segment A-B (not the infinite line).
     * [outT], when given (size >= 1), receives the position of the closest point along A->B, 0..1.
     */
    fun distanceToSegmentM(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
        outT: DoubleArray? = null,
    ): Double {
        val kx = cos(Math.toRadians(pLat)) * METERS_PER_DEGREE
        val ky = METERS_PER_DEGREE
        val ax = (aLon - pLon) * kx
        val ay = (aLat - pLat) * ky
        val bx = (bLon - pLon) * kx
        val by = (bLat - pLat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        // P is the origin, so the projection parameter is -(A . d) / |d|^2.
        val t = if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        if (outT != null) outT[0] = t
        return hypot(ax + t * dx, ay + t * dy)
    }
}
