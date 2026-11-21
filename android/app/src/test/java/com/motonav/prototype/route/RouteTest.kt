package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol.Man
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {

    private val a = TestRoutes.ORIGIN
    private val b = TestRoutes.offset(a, 0.0, 1000.0)

    @Test
    fun lengthIsTheSumOfTheSegments() {
        val r = TestRoutes.lShape()
        assertEquals(2500.0, r.totalM, 2.0)
        assertEquals(250.0, r.totalDurationS, 1e-9)
    }

    @Test
    fun needsAtLeastTwoPoints() {
        assertThrows(IllegalArgumentException::class.java) { Route(listOf(a), emptyList()) }
    }

    @Test
    fun missingDepartAndArriveAreAdded() {
        val r = Route(listOf(a, b), emptyList())
        assertEquals(listOf(Man.DEPART, Man.ARRIVE), r.steps.map { it.maneuver })
        assertEquals(0, r.steps.first().startIndex)
        assertEquals(1, r.steps.last().startIndex)
    }

    @Test
    fun stepsAreOrderedAlongTheRoute() {
        val c = TestRoutes.offset(b, 90.0, 500.0)
        val r = Route(
            listOf(a, b, c),
            listOf(
                RouteStep(Man.ARRIVE, 0, "", 2, 0.0),
                RouteStep(Man.RIGHT, 0, "Second", 1, 10.0),
                RouteStep(Man.DEPART, 0, "First", 0, 10.0),
            ),
        )
        assertEquals(listOf("First", "Second", ""), r.steps.map { it.street })
    }

    @Test
    fun stepOutsideTheGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Route(listOf(a, b), listOf(RouteStep(Man.LEFT, 0, "", 5, 1.0)))
        }
    }

    @Test
    fun pointAtInterpolatesAndClamps() {
        val r = TestRoutes.lShape()
        val half = r.pointAt(500.0)
        assertEquals(500.0, Geo.haversineM(r.points.first(), half), 1.0)
        assertEquals(r.points.first(), r.pointAt(-50.0))
        assertEquals(r.points.last(), r.pointAt(99_999.0))
    }

    @Test
    fun remainingDurationCountsDownFromTheTotalToZero() {
        val r = TestRoutes.lShape()
        assertEquals(250.0, r.remainingDurationS(0.0), 1e-6)
        assertEquals(0.0, r.remainingDurationS(r.totalM), 1e-6)
        // 500 m into the first 1000 m / 100 s step: 50 s of it left plus the 150 s after it.
        assertEquals(200.0, r.remainingDurationS(500.0), 1.0)
        var prev = Double.MAX_VALUE
        var p = 0.0
        while (p <= r.totalM) {
            val d = r.remainingDurationS(p)
            assertTrue("not decreasing at $p", d <= prev + 1e-9)
            prev = d
            p += 25.0
        }
    }
}
