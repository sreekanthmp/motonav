package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmRouteParserTest {

    private val a = TestRoutes.ORIGIN
    private val b = TestRoutes.offset(a, 0.0, 1000.0)          // 1000 m north of A
    private val c = TestRoutes.offset(b, 90.0, 1000.0)         // 1000 m east of B
    private val d = TestRoutes.offset(c, 0.0, 500.0)           // 500 m north of C

    // ---- builders for OSRM-shaped JSON -------------------------------------------------------

    private fun step(
        points: List<LatLon>,
        type: String,
        modifier: String? = null,
        name: String? = "",
        duration: Double = 10.0,
        exit: Int? = null,
        mode: String = "driving",
        drivingSide: String? = "left",
        ref: String? = null,
        precision: Int = 6,
    ): JSONObject {
        val maneuver = JSONObject()
            .put("location", JSONArray().put(points.first().lon).put(points.first().lat))
            .put("bearing_before", 0).put("bearing_after", 0)
            .put("type", type)
        if (modifier != null) maneuver.put("modifier", modifier)
        if (exit != null) maneuver.put("exit", exit)
        val s = JSONObject()
            .put("geometry", PolylineCodec.encode(points, precision))
            .put("maneuver", maneuver)
            .put("mode", mode)
            .put("duration", duration)
            .put("distance", 0.0)
            .put("weight", duration)
            .put("intersections", JSONArray())
        if (name != null) s.put("name", name) else s.put("name", JSONObject.NULL)
        if (drivingSide != null) s.put("driving_side", drivingSide)
        if (ref != null) s.put("ref", ref)
        return s
    }

    private fun response(vararg legs: List<JSONObject>): String {
        val legArray = JSONArray()
        for (steps in legs) legArray.put(JSONObject().put("steps", JSONArray(steps)).put("distance", 0.0))
        return JSONObject()
            .put("code", "Ok")
            .put("routes", JSONArray().put(JSONObject().put("legs", legArray).put("distance", 0.0).put("duration", 0.0)))
            .put("waypoints", JSONArray())
            .toString()
    }

    /** depart A->B on MG Road, right onto Cunningham Road B->C, left onto Ring Road C->D, arrive at D. */
    private fun lShapeResponse(): String = response(
        listOf(
            step(listOf(a, b), "depart", "straight", "MG Road", 100.0),
            step(listOf(b, c), "turn", "right", "Cunningham Road", 100.0),
            step(listOf(c, d), "turn", "left", "Ring Road", 50.0),
            step(listOf(d, d), "arrive", "right", "Ring Road", 0.0),
        ),
    )

    // ---- parsing -----------------------------------------------------------------------------

    @Test
    fun parsesADocumentedResponse() {
        val r = OsrmRouteParser.parse(lShapeResponse())
        assertEquals(listOf(Man.DEPART, Man.RIGHT, Man.LEFT, Man.ARRIVE_RIGHT), r.steps.map { it.maneuver })
        assertEquals(listOf("MG Road", "Cunningham Road", "Ring Road", "Ring Road"), r.steps.map { it.street })
        assertEquals(listOf(0, 1, 2, 3), r.steps.map { it.startIndex })
        assertEquals(4, r.points.size)                       // the shared joints and the doubled arrive point are stored once
        assertEquals(2500.0, r.totalM, 2.0)
        assertEquals(250.0, r.totalDurationS, 1e-9)
    }

    @Test
    fun theParsedRouteDrivesTheFollowerEndToEnd() {
        val r = OsrmRouteParser.parse(lShapeResponse())
        val f = RouteFollower(r)
        val start = f.update(TestRoutes.fix(r, 0.0, 0))
        assertEquals(Mode.NAV, start.mode)
        assertEquals(Man.RIGHT, start.maneuver)
        assertEquals("Cunningham Road", start.street)
        assertEquals(1000.0, start.distToManeuverM.toDouble(), 3.0)

        val later = f.update(TestRoutes.fix(r, 1500.0, 150_000))
        assertEquals(Man.LEFT, later.maneuver)
        assertEquals("Ring Road", later.street)
        assertEquals(500.0, later.distToManeuverM.toDouble(), 3.0)
        assertEquals(1000.0, later.remainingM.toDouble(), 3.0)
    }

    @Test
    fun unknownFieldsAreIgnoredAndNamesCanBeNullOrMissing() {
        val json = response(
            listOf(
                step(listOf(a, b), "depart", "straight", name = null),
                step(listOf(b, c), "turn", null, name = "", ref = "NH 48"),
                step(listOf(c, c), "arrive"),
            ),
        )
        val r = OsrmRouteParser.parse(json)
        assertEquals("", r.steps[0].street)                  // JSON null is an empty name, not the text "null"
        assertEquals(Man.STRAIGHT, r.steps[1].maneuver)      // no modifier: straight on
        assertEquals("NH 48", r.steps[1].street)             // an unnamed highway shows its reference
        assertEquals(Man.ARRIVE, r.steps.last().maneuver)
    }

    @Test
    fun roundaboutKeepsItsExitNumberAndTheExitStepKeepsItsTime() {
        val mid = TestRoutes.offset(b, 45.0, 30.0)
        val json = response(
            listOf(
                step(listOf(a, b), "depart", "straight", "MG Road", 100.0),
                step(listOf(b, mid), "roundabout", "left", "Ring Road", 10.0, exit = 2),
                step(listOf(mid, c), "exit roundabout", "left", "Ring Road", 20.0),
                step(listOf(c, c), "arrive", "straight", "Ring Road", 0.0),
            ),
        )
        val r = OsrmRouteParser.parse(json)
        assertEquals(listOf(Man.DEPART, Man.ROUNDABOUT, Man.ARRIVE), r.steps.map { it.maneuver })
        assertEquals(2, r.steps[1].roundaboutExit)
        assertEquals(30.0, r.steps[1].durationS, 1e-9)       // the dropped "exit" step's 20 s is not lost
        assertEquals(130.0, r.totalDurationS, 1e-9)
    }

    @Test
    fun anOddMultiLegRouteDropsTheViaPointsButKeepsTheTime() {
        val json = response(
            listOf(step(listOf(a, b), "depart", "straight", "X", 100.0), step(listOf(b, b), "arrive", "straight", "X", 0.0)),
            listOf(step(listOf(b, c), "depart", "right", "Y", 100.0), step(listOf(c, c), "arrive", "straight", "Y", 0.0)),
        )
        val r = OsrmRouteParser.parse(json)
        assertEquals(listOf(Man.DEPART, Man.ARRIVE), r.steps.map { it.maneuver })
        assertEquals(200.0, r.totalDurationS, 1e-9)
        assertEquals(2000.0, r.totalM, 2.0)
    }

    @Test
    fun aFerryIsAnnouncedOnceWhenBoarding() {
        val ferry = TestRoutes.offset(b, 90.0, 500.0)
        val json = response(
            listOf(
                step(listOf(a, b), "depart", "straight", "Pier Road", 100.0),
                step(listOf(b, ferry), "turn", "straight", "Ferry", 600.0, mode = "ferry"),
                step(listOf(ferry, ferry), "notification", "straight", "", 0.0, mode = "ferry"),
                step(listOf(ferry, c), "notification", "straight", "Shore Road", 50.0),
                step(listOf(c, c), "arrive", "straight", "Shore Road", 0.0),
            ),
        )
        val r = OsrmRouteParser.parse(json)
        assertEquals(listOf(Man.DEPART, Man.FERRY, Man.ARRIVE), r.steps.map { it.maneuver })
    }

    @Test
    fun geoJsonGeometryIsAccepted() {
        fun gj(vararg p: LatLon) = JSONObject().put("type", "LineString")
            .put("coordinates", JSONArray().also { arr -> p.forEach { arr.put(JSONArray().put(it.lon).put(it.lat)) } })
        val s1 = step(listOf(a, b), "depart", "straight", "MG Road", 100.0).put("geometry", gj(a, b))
        val s2 = step(listOf(b, c), "turn", "right", "Cunningham Road", 100.0).put("geometry", gj(b, c))
        val s3 = step(listOf(c, c), "arrive", "straight", "", 0.0).put("geometry", gj(c, c))
        val r = OsrmRouteParser.parse(response(listOf(s1, s2, s3)))
        assertEquals(listOf(Man.DEPART, Man.RIGHT, Man.ARRIVE), r.steps.map { it.maneuver })
        assertEquals(2000.0, r.totalM, 2.0)
    }

    @Test
    fun aRouteWithOnlyAnOverviewLineStillWorks() {
        val json = JSONObject()
            .put("code", "Ok")
            .put(
                "routes",
                JSONArray().put(
                    JSONObject()
                        .put("geometry", PolylineCodec.encode(listOf(a, b, c), 6))
                        .put("duration", 200.0),
                ),
            )
            .toString()
        val r = OsrmRouteParser.parse(json)
        assertEquals(listOf(Man.DEPART, Man.ARRIVE), r.steps.map { it.maneuver })
        assertEquals(200.0, r.totalDurationS, 1e-9)
        assertEquals(2000.0, r.totalM, 2.0)
    }

    // ---- failures ----------------------------------------------------------------------------

    @Test
    fun aWrongGeometryPrecisionIsDetected() {
        val encodedAt5 = response(listOf(step(listOf(a, b), "depart", precision = 5), step(listOf(b, b), "arrive", precision = 5)))
        val e1 = assertThrows(RouteParseException::class.java) { OsrmRouteParser.parse(encodedAt5, geometryPrecision = 6) }
        assertTrue(e1.message!!, e1.message!!.contains("precision"))

        val encodedAt6 = response(listOf(step(listOf(a, b), "depart"), step(listOf(b, b), "arrive")))
        val e2 = assertThrows(RouteParseException::class.java) { OsrmRouteParser.parse(encodedAt6, geometryPrecision = 5) }
        assertTrue(e2.message!!, e2.message!!.contains("precision"))

        // And the right precision is fine.
        OsrmRouteParser.parse(encodedAt5, geometryPrecision = 5)
    }

    @Test
    fun serviceErrorsAreReported() {
        val e = assertThrows(RouteParseException::class.java) {
            OsrmRouteParser.parse("""{"code":"NoRoute","message":"Impossible route between points"}""")
        }
        assertTrue(e.message!!, e.message!!.contains("NoRoute"))
        assertTrue(e.message!!, e.message!!.contains("Impossible route"))
    }

    @Test
    fun garbageAndEmptyResponsesAreReportedNotCrashed() {
        assertThrows(RouteParseException::class.java) { OsrmRouteParser.parse("not json at all") }
        assertThrows(RouteParseException::class.java) { OsrmRouteParser.parse("""{"code":"Ok","routes":[]}""") }
        assertThrows(RouteParseException::class.java) { OsrmRouteParser.parse("""{"code":"Ok"}""") }
        assertThrows(RouteParseException::class.java) {
            OsrmRouteParser.parse("""{"code":"Ok","routes":[{"legs":[{"steps":[{"geometry":"_p~iF~ps|U_ulLnnqC_mqNvxq`"}]}]}]}""")
        }
    }

    // ---- manoeuvre mapping -------------------------------------------------------------------

    private fun map(
        type: String,
        modifier: String = "",
        exit: Int = 0,
        mode: String = "driving",
        prevMode: String = "driving",
        firstLeg: Boolean = true,
        lastLeg: Boolean = true,
        uturnToRight: Boolean = true,
    ) = OsrmRouteParser.mapManeuver(type, modifier, exit, mode, prevMode, firstLeg, lastLeg, uturnToRight)

    @Test
    fun turnsMapByModifier() {
        assertEquals(Man.LEFT to 0, map("turn", "left"))
        assertEquals(Man.RIGHT to 0, map("turn", "right"))
        assertEquals(Man.SLIGHT_LEFT to 0, map("turn", "slight left"))
        assertEquals(Man.SLIGHT_RIGHT to 0, map("turn", "slight right"))
        assertEquals(Man.SHARP_LEFT to 0, map("turn", "sharp left"))
        assertEquals(Man.SHARP_RIGHT to 0, map("turn", "sharp right"))
        assertEquals(Man.STRAIGHT to 0, map("turn", "straight"))
        assertEquals(Man.RIGHT to 0, map("end of road", "right"))
        assertEquals(Man.STRAIGHT to 0, map("new name", "straight"))
        assertEquals(Man.SLIGHT_LEFT to 0, map("continue", "slight left"))
        assertEquals(Man.STRAIGHT to 0, map("turn"))                        // no modifier
        assertEquals(Man.LEFT to 0, map("roundabout turn", "left"))         // a small roundabout is just a turn
        assertEquals(Man.RIGHT to 0, map("some future type", "right"))      // unknown types behave like a turn
    }

    @Test
    fun uTurnFollowsTheSideOfTheRoadTheTrafficUses() {
        assertEquals(Man.UTURN_RIGHT to 0, map("turn", "uturn", uturnToRight = true))    // left-hand traffic (India)
        assertEquals(Man.UTURN_LEFT to 0, map("turn", "uturn", uturnToRight = false))    // right-hand traffic
    }

    @Test
    fun drivingSideInTheResponseOverridesTheDefault() {
        fun uturn(side: String?, default: Boolean): Int {
            val json = response(
                listOf(
                    step(listOf(a, b), "depart", "straight", "MG Road", 100.0, drivingSide = side),
                    step(listOf(b, c), "turn", "uturn", "MG Road", 100.0, drivingSide = side),
                    step(listOf(c, c), "arrive", "straight", "", 0.0, drivingSide = side),
                ),
            )
            return OsrmRouteParser.parse(json, leftHandTraffic = default).steps[1].maneuver
        }
        assertEquals(Man.UTURN_RIGHT, uturn("left", default = false))
        assertEquals(Man.UTURN_LEFT, uturn("right", default = true))
        assertEquals(Man.UTURN_RIGHT, uturn(null, default = true))
        assertEquals(Man.UTURN_LEFT, uturn(null, default = false))
    }

    @Test
    fun rampsForksAndMerges() {
        assertEquals(Man.RAMP_LEFT to 0, map("off ramp", "slight left"))
        assertEquals(Man.RAMP_RIGHT to 0, map("on ramp", "right"))
        assertEquals(Man.MERGE to 0, map("on ramp", "straight"))
        assertEquals(Man.STRAIGHT to 0, map("off ramp", "straight"))
        assertEquals(Man.KEEP_LEFT to 0, map("fork", "slight left"))
        assertEquals(Man.KEEP_RIGHT to 0, map("fork", "right"))
        assertEquals(Man.MERGE to 0, map("merge", "slight left"))
    }

    @Test
    fun arrivalSides() {
        assertEquals(Man.ARRIVE to 0, map("arrive", "straight"))
        assertEquals(Man.ARRIVE_LEFT to 0, map("arrive", "left"))
        assertEquals(Man.ARRIVE_RIGHT to 0, map("arrive", "slight right"))
        assertNull(map("arrive", "left", lastLeg = false))                   // a via-point is not the destination
        assertNull(map("depart", "straight", firstLeg = false))              // nor is the start of a later leg
        assertEquals(Man.DEPART to 0, map("depart", "right"))
    }

    @Test
    fun roundaboutsKeepTheExitAndDropTheExitSteps() {
        assertEquals(Man.ROUNDABOUT to 3, map("roundabout", "right", exit = 3))
        assertEquals(Man.ROUNDABOUT to 1, map("rotary", "left", exit = 1))
        assertEquals(Man.ROUNDABOUT to 0, map("roundabout", "right"))
        assertNull(map("exit roundabout", "right"))
        assertNull(map("exit rotary", "right"))
        assertNull(map("notification", "straight"))
    }

    @Test
    fun ferryBoardingIsOneAnnouncement() {
        assertEquals(Man.FERRY to 0, map("turn", "straight", mode = "ferry", prevMode = "driving"))
        assertNull(map("turn", "straight", mode = "ferry", prevMode = "ferry"))
    }
}
