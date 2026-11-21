package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol
import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode
import com.motonav.prototype.route.RouteFollower.Fix
import com.motonav.prototype.route.RouteFollower.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs

class RouteFollowerTest {

    private val route = TestRoutes.lShape()   // 0-1000 m north, RIGHT at 1000, LEFT at 2000, arrive at 2500

    /** A square block driven once, then its first street again: the start point is on the route twice. */
    private val loop = TestRoutes.build(
        listOf(
            TestRoutes.Leg(90.0, 500.0, Man.DEPART, "A", 50.0),
            TestRoutes.Leg(0.0, 500.0, Man.LEFT, "B", 50.0),
            TestRoutes.Leg(270.0, 500.0, Man.LEFT, "C", 50.0),
            TestRoutes.Leg(180.0, 500.0, Man.LEFT, "D", 50.0),
            TestRoutes.Leg(90.0, 500.0, Man.LEFT, "E", 50.0),
        ),
    )

    /** Drives the rider along [r] at [speed] m/s from second [fromS] to [toS], one fix per second. */
    private fun drive(
        f: RouteFollower,
        r: Route = route,
        fromS: Int = 0,
        toS: Int,
        speed: Double = 10.0,
        onFix: (Int, Snapshot) -> Unit = { _, _ -> },
    ): Snapshot {
        var last: Snapshot? = null
        for (t in fromS..toS) {
            val s = f.update(TestRoutes.fix(r, speed * t, t * 1000L, speedMps = speed))
            onFix(t, s)
            last = s
        }
        return last!!
    }

    @Test
    fun atTheStartItShowsTheFirstTurn() {
        val s = RouteFollower(route).update(TestRoutes.fix(route, 0.0, 0))
        assertEquals(Mode.NAV, s.mode)
        assertEquals(Man.RIGHT, s.maneuver)
        assertEquals("Cunningham Road", s.street)
        assertEquals(1000.0, s.distToManeuverM.toDouble(), 3.0)
        assertEquals(2500.0, s.remainingM.toDouble(), 3.0)
        assertEquals(4, s.etaMinutes)   // 250 s planned = 4.2 min, shown as whole minutes
    }

    @Test
    fun countsDownAlongTheWholeRoute() {
        val f = RouteFollower(route)
        var prevRemaining = Long.MAX_VALUE
        var prevProgress = -1.0
        drive(f, toS = 246) { t, s ->
            val p = 10.0 * t
            assertEquals("mode at $p m", Mode.NAV, s.mode)
            assertTrue("remaining went up at $p m", s.remainingM <= prevRemaining)
            assertTrue("progress went back at $p m", s.progressM >= prevProgress)
            prevRemaining = s.remainingM
            prevProgress = s.progressM
            assertEquals("remaining at $p m", 2500.0 - p, s.remainingM.toDouble(), 3.0)

            // Skip the instants right at a turn, where "which turn is next" is a coin toss by design.
            if (abs(p - 1000.0) > 3 && abs(p - 2000.0) > 3) {
                val (man, street, dist) = when {
                    p < 1000 -> Triple(Man.RIGHT, "Cunningham Road", 1000.0 - p)
                    p < 2000 -> Triple(Man.LEFT, "Ring Road", 2000.0 - p)
                    else -> Triple(Man.ARRIVE, "", 2500.0 - p)
                }
                assertEquals("maneuver at $p m", man, s.maneuver)
                assertEquals("street at $p m", street, s.street)
                assertEquals("distance to maneuver at $p m", dist, s.distToManeuverM.toDouble(), 3.0)
            }
        }
    }

    @Test
    fun switchesToTheNextManeuverOnceThePointIsPassed() {
        val f = RouteFollower(route)
        val before = drive(f, toS = 99)                       // 990 m
        assertEquals(Man.RIGHT, before.maneuver)
        assertEquals(10.0, before.distToManeuverM.toDouble(), 3.0)

        val after = drive(f, fromS = 100, toS = 102)          // 1020 m
        assertEquals(Man.LEFT, after.maneuver)
        assertEquals("Ring Road", after.street)
        assertEquals(980.0, after.distToManeuverM.toDouble(), 3.0)
    }

    @Test
    fun arrivesWithinTheArrivalRadiusAndStaysArrived() {
        val f = RouteFollower(route)
        val near = drive(f, toS = 240)                        // 100 m to go
        assertEquals(Mode.NAV, near.mode)

        val arrived = drive(f, fromS = 241, toS = 248)        // 2480 m: 20 m to go
        assertEquals(Mode.ARRIVED, arrived.mode)
        assertEquals(Man.ARRIVE, arrived.maneuver)
        assertEquals(0, arrived.distToManeuverM)
        assertEquals(0, arrived.remainingM)
        assertEquals(0, arrived.etaMinutes)

        // Later fixes, even ones wandering off, do not un-arrive.
        val later = f.update(TestRoutes.fix(route, 2500.0, 260_000, eastM = 300.0))
        assertEquals(Mode.ARRIVED, later.mode)
    }

    @Test
    fun gpsNoiseDoesNotCauseOffRouteOrFlicker() {
        val f = RouteFollower(route)
        val rnd = Random(5)
        var prevStep = -1
        var prevProgress = -1.0
        var last: Snapshot? = null
        for (t in 0..252) {
            val s = f.update(
                TestRoutes.fix(
                    route, 10.0 * t, t * 1000L,
                    northM = (rnd.nextDouble() - 0.5) * 20, eastM = (rnd.nextDouble() - 0.5) * 20,
                    accuracyM = 10.0,
                ),
            )
            assertTrue("went off route at t=$t", s.mode == Mode.NAV || s.mode == Mode.ARRIVED)
            assertTrue("manoeuvre went backwards at t=$t", s.stepIndex >= prevStep)
            assertTrue("progress went backwards at t=$t", s.progressM >= prevProgress)
            prevStep = s.stepIndex
            prevProgress = s.progressM
            last = s
        }
        assertEquals(Mode.ARRIVED, last!!.mode)
    }

    @Test
    fun offRouteAfterFiveSecondsThenReroutingThenNewRoute() {
        val f = RouteFollower(route)
        drive(f, toS = 20)                                    // 200 m along the route

        // The rider is now 80 m east of the route.
        val modes = (21..30).associateWith { t ->
            f.update(TestRoutes.fix(route, 10.0 * t, t * 1000L, eastM = 80.0)).mode
        }
        for (t in 21..25) assertEquals("t=$t", Mode.NAV, modes[t])        // first 4 s: still showing the route
        for (t in 26..30) assertEquals("t=$t", Mode.OFF_ROUTE, modes[t])  // 5 s off: off route

        f.markRerouting()
        val rerouting = f.update(TestRoutes.fix(route, 310.0, 31_000, eastM = 80.0))
        assertEquals(Mode.REROUTING, rerouting.mode)

        // The phone fetched a new route from where the rider is.
        val here = TestRoutes.offset(route.pointAt(320.0), 90.0, 80.0)
        val newRoute = TestRoutes.build(
            listOf(
                TestRoutes.Leg(90.0, 300.0, Man.DEPART, "Side Road", 30.0),
                TestRoutes.Leg(0.0, 200.0, Man.LEFT, "Park Road", 20.0),
            ),
            start = here,
        )
        f.replaceRoute(newRoute)
        val s = f.update(Fix(here.lat, here.lon, 32_000, 10.0, 5.0))
        assertEquals(Mode.NAV, s.mode)
        assertEquals(Man.LEFT, s.maneuver)
        assertEquals("Park Road", s.street)
        assertEquals(300.0, s.distToManeuverM.toDouble(), 3.0)
        assertEquals(500.0, s.remainingM.toDouble(), 3.0)
    }

    @Test
    fun comingBackNearTheRouteNeedsToBeCloseEnoughToRecover() {
        val f = RouteFollower(route)
        drive(f, toS = 20)
        for (t in 21..27) f.update(TestRoutes.fix(route, 10.0 * t, t * 1000L, eastM = 80.0))
        assertEquals(Mode.OFF_ROUTE, f.update(TestRoutes.fix(route, 280.0, 28_000, eastM = 80.0)).mode)

        // 30 m away is inside the 40 m threshold but not close enough to call it back on route (24 m).
        assertEquals(Mode.OFF_ROUTE, f.update(TestRoutes.fix(route, 290.0, 29_000, eastM = 30.0)).mode)
        // 10 m away is back on route.
        assertEquals(Mode.NAV, f.update(TestRoutes.fix(route, 300.0, 30_000, eastM = 10.0)).mode)
    }

    @Test
    fun startingFarFromTheRouteIsOffRouteUntilTheRiderJoinsIt() {
        val f = RouteFollower(route)
        val modes = (0..7).map { t -> f.update(TestRoutes.fix(route, 0.0, t * 1000L, eastM = 500.0)).mode }
        assertEquals(List(5) { Mode.NAV }, modes.take(5))              // first 4 s: not yet off route
        assertEquals(List(3) { Mode.OFF_ROUTE }, modes.drop(5))        // from 5 s on: off route

        // The rider reaches the route 300 m along it: the whole route is searched, not just near the start.
        val joined = f.update(TestRoutes.fix(route, 300.0, 8_000))
        assertEquals(Mode.NAV, joined.mode)
        assertEquals(300.0, joined.progressM, 3.0)
        assertEquals(Man.RIGHT, joined.maneuver)
        assertEquals(700.0, joined.distToManeuverM.toDouble(), 3.0)
    }

    @Test
    fun poorAccuracyFixesAreIgnored() {
        val f = RouteFollower(route)
        val first = f.update(TestRoutes.fix(route, 100.0, 0))
        // A wild fix 5 km away with 500 m accuracy changes nothing.
        val ignored = f.update(Fix(0.0, 0.0, 1_000, 10.0, 500.0))
        assertEquals(first, ignored)
        val next = f.update(TestRoutes.fix(route, 110.0, 2_000))
        assertEquals(Mode.NAV, next.mode)
        assertEquals(110.0, next.progressM, 3.0)
    }

    @Test
    fun aRouteThatOverlapsItselfDoesNotJumpToTheWrongPass() {
        val f = RouteFollower(loop)
        var prevProgress = -1.0
        drive(f, loop, toS = 230) { t, s ->
            assertTrue("progress went backwards at t=$t", s.progressM >= prevProgress - 1e-9)
            prevProgress = s.progressM
            if (t == 40) {   // 400 m: first pass along street A, not the repeat at 2400 m
                assertEquals(400.0, s.progressM, 3.0)
                assertEquals("B", s.street)
                assertEquals(100.0, s.distToManeuverM.toDouble(), 3.0)
            }
            if (t == 205) {  // 2050 m: second pass along the same street, not 50 m
                assertEquals(2050.0, s.progressM, 3.0)
                assertEquals(Man.ARRIVE, s.maneuver)
                assertEquals(450.0, s.distToManeuverM.toDouble(), 3.0)
            }
        }
    }

    /** Up a road, a short hop across, and back down it 8 m to the east. */
    private val outAndBack = TestRoutes.build(
        listOf(
            TestRoutes.Leg(0.0, 500.0, Man.DEPART, "Out", 50.0),
            TestRoutes.Leg(90.0, 8.0, Man.RIGHT, "Hop", 1.0),
            TestRoutes.Leg(180.0, 500.0, Man.RIGHT, "Back", 50.0),
        ),
    )

    /** Rides the whole out-and-back route with the GPS reading [westM] metres west of the true position. */
    private fun rideOutAndBack(westM: Double, withBearing: Boolean) {
        val f = RouteFollower(outAndBack)
        var prevProgress = -1.0
        var last: Snapshot? = null
        for (t in 0..105) {
            val s = f.update(
                TestRoutes.fix(outAndBack, 10.0 * t, t * 1000L, eastM = -westM, accuracyM = 5.0, withBearing = withBearing),
            )
            assertTrue("mode at t=$t was ${s.mode}", s.mode == Mode.NAV || s.mode == Mode.ARRIVED)
            assertTrue("progress went backwards at t=$t: ${s.progressM} < $prevProgress", s.progressM >= prevProgress - 1e-9)
            prevProgress = s.progressM
            last = s
        }
        assertEquals(Mode.ARRIVED, last!!.mode)
    }

    @Test
    fun anOutAndBackRoadDoesNotSwapLanesWhenGpsDriftsTowardTheOtherOne() {
        // Reading 6 m west puts the rider 2 m from the outbound lane and 6 m from the one they are on.
        // Position alone cannot tell, so the direction of travel does.
        rideOutAndBack(westM = 6.0, withBearing = true)
    }

    @Test
    fun anOutAndBackRoadWithSmallGpsErrorNeedsNoBearing() {
        rideOutAndBack(westM = 2.0, withBearing = false)
    }

    @Test
    fun aRouteThatRevisitsAPlaceFewMetresApartStaysOnTheCurrentPass() {
        // A block driven once, then its first street again, but 10 m to the north of the first pass.
        val revisit = TestRoutes.build(
            listOf(
                TestRoutes.Leg(90.0, 500.0, Man.DEPART, "A", 50.0),
                TestRoutes.Leg(0.0, 500.0, Man.LEFT, "B", 50.0),
                TestRoutes.Leg(270.0, 500.0, Man.LEFT, "C", 50.0),
                TestRoutes.Leg(180.0, 490.0, Man.LEFT, "D", 49.0),
                TestRoutes.Leg(90.0, 500.0, Man.LEFT, "E", 50.0),
            ),
        )
        val f = RouteFollower(revisit)
        var checked = false
        // The GPS always reads 7 m south: on the second pass that is 3 m from the first pass and 7 m from the
        // road being ridden. Only knowing where the rider was a moment ago says which one is right.
        for (t in 0..215) {
            val s = f.update(TestRoutes.fix(revisit, 10.0 * t, t * 1000L, northM = -7.0))
            if (t == 220 - 5) {   // 2150 m: 160 m along the second pass
                assertEquals(2150.0, s.progressM, 5.0)
                assertEquals(Man.ARRIVE, s.maneuver)
                checked = true
            }
        }
        assertTrue(checked)
    }

    @Test
    fun rejoiningAtAPointThatIsOnTheRouteTwiceUsesThePassNearestTheLastProgress() {
        val f = RouteFollower(loop)
        drive(f, loop, toS = 170)                            // 1700 m: heading south, 300 m before the start junction

        // The rider leaves the route for 6 s (far to the west of the block)...
        for (t in 171..176) f.update(TestRoutes.fix(loop, 1700.0, t * 1000L, eastM = -500.0))

        // ...and is next seen 300 m east of the start junction, which is both 300 m and 2300 m along the route.
        val back = f.update(TestRoutes.fix(loop, 2300.0, 177_000))
        assertEquals(Mode.NAV, back.mode)
        assertEquals(2300.0, back.progressM, 3.0)
        assertEquals(Man.ARRIVE, back.maneuver)
    }

    @Test
    fun aGapInFixesDoesNotLoseTheRider() {
        val f = RouteFollower(route)
        drive(f, toS = 50)                                   // 500 m, then a 40 s tunnel
        val out = f.update(TestRoutes.fix(route, 900.0, 90_000))
        assertEquals(Mode.NAV, out.mode)
        assertEquals(900.0, out.progressM, 3.0)
        assertEquals(100.0, out.distToManeuverM.toDouble(), 3.0)
    }

    @Test
    fun slowRidersGetALongerEtaThanTheRoutePlan() {
        // Both riders are 1300 m along, with 120 s of planned travel left. Whole minutes are chosen so that no
        // case sits on a rounding boundary: 120 s -> 2 min, 180 s -> 3 min, 240 s -> 4 min.
        val onPace = RouteFollower(route)
        val onPaceState = drive(onPace, toS = 130, speed = 10.0)         // 1300 m in 130 s: exactly the plan
        val slow = RouteFollower(route)
        val slowState = drive(slow, toS = 260, speed = 5.0)              // 1300 m in 260 s: twice the planned time
        val slowUncapped = RouteFollower(route, RouteFollower.Config(maxPace = 100.0))
        val slowUncappedState = drive(slowUncapped, toS = 260, speed = 5.0)

        assertEquals(onPaceState.remainingM, slowState.remainingM)
        assertEquals(2, onPaceState.etaMinutes)         // 120 s planned left, pace 1.0
        assertEquals(3, slowState.etaMinutes)           // pace 2.0 is capped at 1.5: 180 s
        assertEquals(4, slowUncappedState.etaMinutes)   // without the cap it would be 240 s
    }

    @Test
    fun etaFallsBackToSpeedWhenTheRouteHasNoDurations() {
        val noTimes = TestRoutes.build(
            listOf(
                TestRoutes.Leg(0.0, 1000.0, Man.DEPART, "A", 0.0),
                TestRoutes.Leg(90.0, 1000.0, Man.RIGHT, "B", 0.0),
                TestRoutes.Leg(0.0, 500.0, Man.LEFT, "C", 0.0),
            ),
        )
        val s = RouteFollower(noTimes).update(TestRoutes.fix(noTimes, 0.0, 0, speedMps = 10.0))
        assertEquals(4, s.etaMinutes)             // 2500 m at 10 m/s = 250 s
        assertEquals(360, s.speedKmhX10)          // 10 m/s = 36.0 km/h
    }

    @Test
    fun speedIsDerivedFromMovementWhenTheFixHasNone() {
        val f = RouteFollower(route)
        f.update(TestRoutes.fix(route, 0.0, 0, speedMps = null))
        val s = f.update(TestRoutes.fix(route, 10.0, 1_000, speedMps = null))
        assertEquals(360.0, s.speedKmhX10.toDouble(), 10.0)
    }

    @Test
    fun encodesToTheDeviceProtocol() {
        val s = RouteFollower(route).update(TestRoutes.fix(route, 0.0, 0))
        val core = s.encodeCore(seq = 5)
        assertEquals(NavProtocol.CORE_SIZE, core.size)

        val b = ByteBuffer.wrap(core).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(NavProtocol.VERSION, b.get(0).toInt())
        assertEquals(Mode.NAV, b.get(1).toInt())
        assertEquals(Man.RIGHT, b.get(2).toInt())
        assertEquals(0, b.get(3).toInt())
        assertEquals(5, b.getShort(4).toInt())
        assertEquals(s.distToManeuverM, b.getInt(6).toLong() and 0xFFFFFFFFL)
        assertEquals(s.remainingM, b.getInt(10).toLong() and 0xFFFFFFFFL)
        assertEquals(s.etaMinutes, b.getShort(14).toInt())
        assertEquals(s.speedKmhX10, b.getShort(16).toInt())

        assertEquals("Cunningham Road", String(s.encodeStreet(), Charsets.US_ASCII))
    }

    @Test
    fun replacingTheRouteClearsAnArrival() {
        val f = RouteFollower(route)
        drive(f, toS = 250)
        assertEquals(Mode.ARRIVED, f.update(TestRoutes.fix(route, 2500.0, 251_000)).mode)
        f.replaceRoute(route)
        val s = f.update(TestRoutes.fix(route, 0.0, 300_000))
        assertEquals(Mode.NAV, s.mode)
        assertNotEquals(0, s.remainingM)
    }

    @Test
    fun aVeryLongRouteIsStillFast() {
        // ~200 km of points every 10 m (20,000 points): a full-route search each second must stay cheap.
        val long = TestRoutes.build(
            listOf(TestRoutes.Leg(45.0, 200_000.0, Man.DEPART, "Expressway", 7_200.0)),
            spacingM = 10.0,
        )
        val f = RouteFollower(long)
        val start = System.nanoTime()
        for (t in 0..300) f.update(TestRoutes.fix(long, 25.0 * t, t * 1000L, speedMps = 25.0))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("301 updates took $ms ms", ms < 2_000)
        val jumped = f.update(TestRoutes.fix(long, 150_000.0, 400_000))   // forces a whole-route re-acquire
        assertEquals(Mode.NAV, jumped.mode)
        assertEquals(150_000.0, jumped.progressM, 5.0)
    }
}
