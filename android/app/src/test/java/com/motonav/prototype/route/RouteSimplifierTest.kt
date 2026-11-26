package com.motonav.prototype.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RouteSimplifierTest {

    private fun line(n: Int, bearing: Double, lengthM: Double, from: LatLon = TestRoutes.ORIGIN) =
        List(n) { TestRoutes.offset(from, bearing, lengthM * it / (n - 1)) }

    /** Largest distance from any original point to the simplified line. */
    private fun maxDeviationM(original: List<LatLon>, simple: List<LatLon>): Double {
        var worst = 0.0
        val t = DoubleArray(1)
        for (p in original) {
            var best = Double.MAX_VALUE
            for (i in 0 until simple.size - 1) {
                best = minOf(best, Geo.distanceToSegmentM(p.lat, p.lon, simple[i].lat, simple[i].lon, simple[i + 1].lat, simple[i + 1].lon, t))
            }
            worst = maxOf(worst, best)
        }
        return worst
    }

    @Test
    fun aStraightLineCollapsesToItsEnds() {
        val pts = line(1000, 45.0, 3000.0)
        val s = RouteSimplifier.simplify(pts, 2.0)
        assertEquals(listOf(pts.first(), pts.last()), s)
    }

    @Test
    fun aCornerIsKept() {
        val a = line(200, 0.0, 1000.0)
        val b = line(200, 90.0, 1000.0, from = a.last())
        val s = RouteSimplifier.simplify(a + b.drop(1), 5.0)
        assertEquals(3, s.size)
        assertEquals(a.last(), s[1])
    }

    @Test
    fun removedPointsStayWithinTheTolerance() {
        val rnd = Random(3)
        val pts = ArrayList<LatLon>()
        var p = TestRoutes.ORIGIN
        for (i in 0 until 3000) {
            p = TestRoutes.offset(p, rnd.nextDouble() * 40 - 20 + 30, 8.0)   // a wandering road
            pts += p
        }
        for (tol in listOf(2.0, 5.0, 20.0)) {
            val s = RouteSimplifier.simplify(pts, tol)
            assertTrue("tolerance $tol: ${maxDeviationM(pts, s)}", maxDeviationM(pts, s) <= tol + 0.05)
            assertEquals(pts.first(), s.first())
            assertEquals(pts.last(), s.last())
        }
    }

    @Test
    fun simplifyToMaxRespectsTheLimitAndKeepsTheEnds() {
        val rnd = Random(8)
        val pts = ArrayList<LatLon>()
        var p = TestRoutes.ORIGIN
        for (i in 0 until 5000) {
            p = TestRoutes.offset(p, rnd.nextDouble() * 360, 6.0)    // a very wiggly line
            pts += p
        }
        for (limit in listOf(96, 40, 10, 2)) {
            val s = RouteSimplifier.simplifyToMax(pts, limit)
            assertTrue("limit $limit gave ${s.size}", s.size <= limit)
            assertEquals(pts.first(), s.first())
            assertEquals(pts.last(), s.last())
        }
    }

    @Test
    fun shortRoutesAreReturnedAsTheyAre() {
        val pts = line(10, 10.0, 500.0)
        assertEquals(pts, RouteSimplifier.simplifyToMax(pts, 96))
    }

    @Test
    fun aHugeRouteIsFastAndDoesNotOverflowTheStack() {
        val pts = List(60_000) { TestRoutes.offset(TestRoutes.ORIGIN, 30.0 + (it % 3), 5.0 * it) }
        val start = System.nanoTime()
        val s = RouteSimplifier.simplifyToMax(pts, 96)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 3_000)
        assertTrue(s.size <= 96)
    }
}
