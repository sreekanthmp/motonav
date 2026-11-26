package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol
import com.motonav.prototype.NavProtocol.Man
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MapFeedTest {

    private class FakeSink : MapFeed.Sink {
        val routes = ArrayList<List<ByteArray>>()
        val positions = ArrayList<ByteArray>()
        override fun sendRoute(chunks: List<ByteArray>) { routes += chunks }
        override fun sendPos(bytes: ByteArray) { positions += bytes }
    }

    private class Pos(val routeId: Int, val seq: Int, val lat: Double, val lon: Double)

    private fun pos(b: ByteArray): Pos {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Pos(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, bb.getInt(2) / 1e6, bb.getInt(6) / 1e6)
    }

    private val route = TestRoutes.lShape()   // 0-2500 m: north 1000, east 1000, north 500

    private fun at(feed: MapFeed, progressM: Double, eastM: Double = 0.0) {
        val p = TestRoutes.offset(route.pointAt(progressM), 90.0, eastM)
        feed.onPosition(p.lat, p.lon)
    }

    @Test
    fun withoutARouteOnlyPositionsGoOutWithIdZero() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        at(feed, 0.0); at(feed, 10.0); at(feed, 20.0)
        assertEquals(0, sink.routes.size)
        assertEquals(listOf(0, 1, 2), sink.positions.map { pos(it).seq })
        assertTrue(sink.positions.all { pos(it).routeId == 0 })
    }

    @Test
    fun theFirstPositionSendsAWindowThenThePosition() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        at(feed, 100.0)
        assertEquals(1, sink.routes.size)
        val w = requireNotNull(RouteWindowCodec.decode(sink.routes[0]))
        assertEquals(1, w.routeId)
        assertTrue("the window covers the rest of the route, so it ends at the destination", w.endsAtDestination)
        assertTrue(w.count in 2..NavProtocol.ROUTE_MAX_POINTS)
        // the window starts at the route start (100 m in, 200 m behind is clamped) and ends at the destination
        assertEquals(route.points.first().lat, w.latE6.first() / 1e6, 1e-5)
        assertEquals(route.points.last().lat, w.latE6.last() / 1e6, 1e-5)
        assertEquals(1, pos(sink.positions.single()).routeId)
    }

    @Test
    fun noNewWindowWhileTheRiderStaysInsideTheCurrentOne() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        for (p in 0..900 step 10) at(feed, p.toDouble())
        assertEquals(1, sink.routes.size)
        assertEquals(91, sink.positions.size)
    }

    @Test
    fun aFreshWindowGoesOutOnceTheOldOneIsUsedUp() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        for (p in 0..1200 step 10) at(feed, p.toDouble())
        assertEquals(2, sink.routes.size)
        val second = requireNotNull(RouteWindowCodec.decode(sink.routes[1]))
        assertEquals(2, second.routeId)
        // The rider has just passed ~1,000 m, so the window starts ~200 m behind that: about 800-810 m along the route.
        val expected = route.pointAt(805.0)
        assertEquals(expected.lat, second.latE6.first() / 1e6, 1.5e-4)    // 1.5e-4 degrees is about 17 m
        assertEquals(expected.lon, second.lonE6.first() / 1e6, 1.5e-4)
        assertEquals(2, pos(sink.positions.last()).routeId)
    }

    @Test
    fun aNewRouteIsSentAtOnce() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        at(feed, 50.0)
        val other = TestRoutes.build(listOf(TestRoutes.Leg(90.0, 800.0, Man.DEPART, "Side Road", 80.0)), start = route.pointAt(60.0))
        feed.setRoute(other)
        at(feed, 60.0)
        assertEquals(2, sink.routes.size)
        assertEquals(2, RouteWindowCodec.decode(sink.routes[1])!!.routeId)
    }

    @Test
    fun reconnectingResendsTheWindow() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        at(feed, 50.0)
        feed.onConnected()
        assertEquals("until the window is resent the device holds none", 0, feed.currentWindowId)
        at(feed, 60.0)
        assertEquals(2, sink.routes.size)
        assertEquals(2, pos(sink.positions.last()).routeId)
    }

    @Test
    fun anOffRouteRiderGetsPositionsButNoNewWindow() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        at(feed, 100.0)
        at(feed, 120.0, eastM = 400.0)      // 400 m off the route
        at(feed, 140.0, eastM = 400.0)
        assertEquals(1, sink.routes.size)
        assertEquals(3, sink.positions.size)
        val lastPos = pos(sink.positions.last())
        assertEquals(1, lastPos.routeId)    // still the old window; Google will send a new route after rerouting
    }

    @Test
    fun aRouteTheRiderIsNotOnIsNotSentUntilTheyJoinIt() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        at(feed, 100.0, eastM = 400.0)             // the first position is 400 m from the new route
        at(feed, 110.0, eastM = 400.0)
        assertEquals("no window for a place the rider is not at", 0, sink.routes.size)
        assertTrue(sink.positions.all { pos(it).routeId == 0 })
        at(feed, 120.0)                            // now on the route
        assertEquals(1, sink.routes.size)
        assertEquals(1, pos(sink.positions.last()).routeId)
    }

    @Test
    fun routeFromDropsRepeatedJointsAndRejectsTooFewPoints() {
        val a = TestRoutes.ORIGIN
        val b = TestRoutes.offset(a, 0.0, 100.0)
        val c = TestRoutes.offset(b, 90.0, 100.0)
        // The Navigation SDK's route segments repeat the joint point between neighbouring segments.
        val r = requireNotNull(MapFeed.routeFrom(listOf(a, b, b, c, c)))
        assertEquals(3, r.points.size)
        assertEquals(200.0, r.totalM, 1.0)
        assertNull(MapFeed.routeFrom(listOf(a, a)))
        assertNull(MapFeed.routeFrom(emptyList()))
    }

    @Test
    fun windowIdsSkipZeroAndSequenceWraps() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        val ids = ArrayList<Int>()
        for (i in 0 until 600) {
            feed.onConnected()
            at(feed, 10.0)
            ids += RouteWindowCodec.decode(sink.routes.last())!!.routeId
        }
        assertTrue(ids.none { it == 0 })
        assertEquals((1..255).toList(), ids.take(255))
        assertEquals(1, ids[255])                                  // wraps 255 -> 1
        assertEquals(0, pos(sink.positions[256]).seq)              // sequence wraps 255 -> 0
    }

    @Test
    fun chunksFitTheWritePayloadAndALargerPayloadNeedsFewerWrites() {
        val zigzag = TestRoutes.build(List(60) { i ->
            TestRoutes.Leg(if (i % 2 == 0) 30.0 else 80.0, 40.0, Man.STRAIGHT, "Z", 5.0)
        })
        val small = FakeSink()
        MapFeed(small).also { it.setRoute(zigzag) }.also { f -> f.onPosition(zigzag.points[0].lat, zigzag.points[0].lon) }
        val big = FakeSink()
        MapFeed(big, MapFeed.Config(writePayload = 185)).also { it.setRoute(zigzag) }.also { f -> f.onPosition(zigzag.points[0].lat, zigzag.points[0].lon) }
        assertTrue(small.routes[0].all { it.size <= 20 })
        assertTrue(big.routes[0].all { it.size <= 185 })
        assertTrue(big.routes[0].size < small.routes[0].size)
        assertNotNull(RouteWindowCodec.decode(small.routes[0]))
        assertNotNull(RouteWindowCodec.decode(big.routes[0]))
    }

    @Test
    fun aLongRouteWindowEndsBeforeTheDestination() {
        val long = TestRoutes.build(listOf(TestRoutes.Leg(0.0, 20_000.0, Man.DEPART, "Highway", 1200.0)))
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(long)
        feed.onPosition(long.points[0].lat, long.points[0].lon)
        val w = RouteWindowCodec.decode(sink.routes[0])!!
        assertEquals(false, w.endsAtDestination)
        // a straight 2.5 km window needs only its two ends
        assertEquals(2, w.count)
    }

    @Test
    fun aWigglyWindowIsSimplifiedToTheLimitAndStillDecodes() {
        val wiggly = TestRoutes.build(List(400) { i ->
            TestRoutes.Leg(if (i % 2 == 0) 20.0 else 100.0, 12.0, Man.STRAIGHT, "W", 1.0)
        }, spacingM = 4.0)
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(wiggly)
        feed.onPosition(wiggly.points[0].lat, wiggly.points[0].lon)
        val w = requireNotNull(RouteWindowCodec.decode(sink.routes[0]))
        assertTrue(w.count in 2..NavProtocol.ROUTE_MAX_POINTS)
    }

    @Test
    fun clearingTheRouteStopsWindowsButPositionsContinue() {
        val sink = FakeSink()
        val feed = MapFeed(sink)
        feed.setRoute(route)
        at(feed, 50.0)
        feed.clearRoute()
        at(feed, 60.0)
        assertEquals(1, sink.routes.size)
        assertEquals(0, pos(sink.positions.last()).routeId)
    }
}
