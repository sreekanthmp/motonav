package com.motonav.prototype.route

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Reduces a route line to few points (Douglas-Peucker) so a window fits in a handful of Bluetooth writes.
 * Iterative, so a route of tens of thousands of points cannot overflow the stack. The first and last points are kept.
 */
object RouteSimplifier {

    /** Keeps every point that sticks out more than [toleranceM] metres from the straight line between kept points. */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        val n = points.size
        if (n <= 2) return points
        val lat0 = points[0].lat
        val kx = cos(Math.toRadians(lat0)) * Geo.METERS_PER_DEGREE
        val ky = Geo.METERS_PER_DEGREE
        val x = DoubleArray(n) { (points[it].lon - points[0].lon) * kx }
        val y = DoubleArray(n) { (points[it].lat - lat0) * ky }

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = IntArray(2 * n)
        var top = 0
        stack[top++] = 0
        stack[top++] = n - 1
        while (top > 0) {
            val hi = stack[--top]
            val lo = stack[--top]
            if (hi - lo < 2) continue
            var worst = -1.0
            var worstIdx = -1
            for (i in lo + 1 until hi) {
                val d = distanceToSegment(x[i], y[i], x[lo], y[lo], x[hi], y[hi])
                if (d > worst) {
                    worst = d
                    worstIdx = i
                }
            }
            if (worst > toleranceM) {
                keep[worstIdx] = true
                stack[top++] = lo; stack[top++] = worstIdx
                stack[top++] = worstIdx; stack[top++] = hi
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** A map window never needs more than this many input points; more are thinned first (keeps the work bounded). */
    private const val MAX_INPUT_POINTS = 4000

    /**
     * Widens the tolerance (2 m, 4 m, 8 m ...) until at most [maxPoints] remain; evenly thins as a last resort.
     * An input of more than 4,000 points is first thinned evenly: Douglas-Peucker can be quadratic on a pathological line,
     * and a window of a few kilometres has no use for sub-metre detail.
     */
    fun simplifyToMax(points: List<LatLon>, maxPoints: Int): List<LatLon> {
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        if (points.size <= maxPoints) return points
        val base = if (points.size > MAX_INPUT_POINTS) evenly(points, MAX_INPUT_POINTS) else points
        var tolerance = 2.0
        while (tolerance <= 1024.0) {
            val s = simplify(base, tolerance)
            if (s.size <= maxPoints) return s
            tolerance *= 2
        }
        // Still too many (a very wiggly line): keep evenly spaced points, always including both ends.
        return evenly(base, maxPoints)
    }

    private fun evenly(points: List<LatLon>, count: Int): List<LatLon> =
        List(count) { k -> points[(k.toLong() * (points.size - 1) / (count - 1)).toInt()] }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12) return hypot(px - ax, py - ay)
        val t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
