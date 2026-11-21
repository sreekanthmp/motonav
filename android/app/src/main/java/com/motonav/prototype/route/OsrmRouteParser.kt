package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol.Man
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The routing response could not be turned into a usable [Route]. The message is safe to show or log. */
class RouteParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns an OSRM-style route response (`/route/v1/...?steps=true&geometries=polyline6`) into a [Route].
 * OSRM, Valhalla's OSRM-compatible output and Mapbox Directions share this shape (routes -> legs -> steps,
 * each step with `maneuver.type`, `maneuver.modifier`, `name`, `duration` and `geometry`).
 *
 * Verified against hand-written responses that follow OSRM's documented format. It has NOT been
 * checked against live Ola Maps or Mappls responses yet: run one real route through each and check the
 * street names and roundabouts before relying on it.
 *
 * Request the geometry as `polyline6` and pass `geometryPrecision = 6` (the default here), or request
 * `polyline` and pass 5. GeoJSON geometry is also accepted. A wrong precision is detected by comparing
 * each step's geometry with its `maneuver.location`.
 */
object OsrmRouteParser {

    /** How far a step's first geometry point may be from `maneuver.location` before we call it a mismatch. */
    private const val LOCATION_TOLERANCE_M = 50.0

    /** Consecutive points closer than this are the same point. */
    private const val DUPLICATE_POINT_M = 0.5

    /**
     * @param leftHandTraffic only used for U-turn direction when a step has no `driving_side`
     *   (India drives on the left, so the default is true)
     * @throws RouteParseException if the response is not a usable route
     */
    fun parse(json: String, geometryPrecision: Int = 6, leftHandTraffic: Boolean = true): Route {
        try {
            return parseUnchecked(json, geometryPrecision, leftHandTraffic)
        } catch (e: RouteParseException) {
            throw e
        } catch (e: JSONException) {
            throw RouteParseException("Not a valid route response: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw RouteParseException(e.message ?: "Bad route data", e)
        }
    }

    private class KeptStep(val maneuver: Int, val exit: Int, val street: String, val startIndex: Int, var durationS: Double)

    private fun parseUnchecked(json: String, precision: Int, leftHandTraffic: Boolean): Route {
        val root = JSONObject(json)
        val code = str(root, "code").ifEmpty { "Ok" }
        if (code != "Ok") {
            val msg = str(root, "message")
            throw RouteParseException("Routing service said: $code" + if (msg.isNotEmpty()) " ($msg)" else "")
        }
        val routes = root.optJSONArray("routes") ?: throw RouteParseException("The response has no routes")
        if (routes.length() == 0) throw RouteParseException("No route found")
        val route = routes.getJSONObject(0)
        val legs = route.optJSONArray("legs") ?: JSONArray()

        val points = ArrayList<LatLon>()
        val kept = ArrayList<KeptStep>()
        var prevMode = ""

        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val s = steps.getJSONObject(si)
                val maneuver = s.optJSONObject("maneuver")
                val location = maneuver?.let { readLocation(it) }

                var geometry = readGeometry(s, precision)
                if (geometry.isEmpty() && location != null) geometry = listOf(location)
                if (geometry.isEmpty()) continue
                if (location != null && Geo.haversineM(location, geometry.first()) > LOCATION_TOLERANCE_M) {
                    throw RouteParseException(
                        "Step geometry does not match its manoeuvre location: wrong geometry precision " +
                            "(expected polyline$precision)?",
                    )
                }

                // Adjacent steps share their joint point, and an arrive step is two identical points:
                // never store a point twice in a row.
                val joined = points.isNotEmpty() && Geo.haversineM(points.last(), geometry.first()) < DUPLICATE_POINT_M
                val startIndex = if (joined) points.size - 1 else points.size
                for (p in geometry) {
                    if (points.isEmpty() || Geo.haversineM(points.last(), p) >= DUPLICATE_POINT_M) points.add(p)
                }

                val duration = s.optDouble("duration", 0.0).let { if (it.isNaN() || it < 0) 0.0 else it }
                val mode = str(s, "mode")
                val drivingSide = str(s, "driving_side")
                val uturnToRight = when (drivingSide) {
                    "left" -> true
                    "right" -> false
                    else -> leftHandTraffic
                }
                val mapped = mapManeuver(
                    type = maneuver?.let { str(it, "type") } ?: "",
                    modifier = maneuver?.let { str(it, "modifier") } ?: "",
                    exit = maneuver?.optInt("exit", 0) ?: 0,
                    mode = mode,
                    prevMode = prevMode,
                    isFirstLeg = li == 0,
                    isLastLeg = li == legs.length() - 1,
                    uturnToRight = uturnToRight,
                )
                prevMode = mode

                if (mapped == null) {
                    // A dropped step keeps its time: add it to the manoeuvre before it.
                    kept.lastOrNull()?.let { it.durationS += duration }
                } else {
                    kept.add(KeptStep(mapped.first, mapped.second, street(s), startIndex, duration))
                }
            }
        }

        if (points.size < 2 || kept.isEmpty()) {
            // Some services return only an overview line. Follow it with a plain depart/arrive.
            val overview = readGeometry(route, precision)
            if (overview.size < 2) throw RouteParseException("The route has no usable geometry")
            val total = route.optDouble("duration", 0.0).let { if (it.isNaN() || it < 0) 0.0 else it }
            return Route(overview, listOf(RouteStep(Man.DEPART, 0, "", 0, total)))
        }
        return Route(points, kept.map { RouteStep(it.maneuver, it.exit, it.street, it.startIndex, it.durationS) })
    }

    /** Returns (deviceManeuver, roundaboutExit), or null when the step should not be announced. */
    internal fun mapManeuver(
        type: String,
        modifier: String,
        exit: Int,
        mode: String,
        prevMode: String,
        isFirstLeg: Boolean,
        isLastLeg: Boolean,
        uturnToRight: Boolean,
    ): Pair<Int, Int>? {
        // Boarding a ferry is one announcement; later steps in ferry mode are not.
        if (mode == "ferry") return if (prevMode != "ferry") Man.FERRY to 0 else null

        return when (type) {
            "depart" -> if (isFirstLeg) Man.DEPART to 0 else null
            "arrive" -> if (isLastLeg) arrival(modifier) to 0 else null
            "roundabout", "rotary" -> Man.ROUNDABOUT to exit.coerceAtLeast(0)
            // The exit was already announced with the entry step.
            "exit roundabout", "exit rotary", "notification" -> null
            "merge" -> Man.MERGE to 0
            "fork" -> when (side(modifier)) {
                Side.LEFT -> Man.KEEP_LEFT
                Side.RIGHT -> Man.KEEP_RIGHT
                Side.NONE -> Man.STRAIGHT
            } to 0
            "on ramp" -> when (side(modifier)) {
                Side.LEFT -> Man.RAMP_LEFT
                Side.RIGHT -> Man.RAMP_RIGHT
                Side.NONE -> Man.MERGE
            } to 0
            "off ramp" -> when (side(modifier)) {
                Side.LEFT -> Man.RAMP_LEFT
                Side.RIGHT -> Man.RAMP_RIGHT
                Side.NONE -> Man.STRAIGHT
            } to 0
            // "turn", "end of road", "new name", "continue", "roundabout turn" and any type we do not know:
            // OSRM says unknown types should be treated like a turn.
            else -> byModifier(modifier, uturnToRight) to 0
        }
    }

    private enum class Side { LEFT, RIGHT, NONE }

    private fun side(modifier: String): Side = when (modifier) {
        "left", "slight left", "sharp left" -> Side.LEFT
        "right", "slight right", "sharp right" -> Side.RIGHT
        else -> Side.NONE
    }

    private fun arrival(modifier: String): Int = when (side(modifier)) {
        Side.LEFT -> Man.ARRIVE_LEFT
        Side.RIGHT -> Man.ARRIVE_RIGHT
        Side.NONE -> Man.ARRIVE
    }

    /** A U-turn is made by turning towards the middle of the road: clockwise (right) when driving on the left. */
    private fun byModifier(modifier: String, uturnToRight: Boolean): Int = when (modifier) {
        "left" -> Man.LEFT
        "right" -> Man.RIGHT
        "slight left" -> Man.SLIGHT_LEFT
        "slight right" -> Man.SLIGHT_RIGHT
        "sharp left" -> Man.SHARP_LEFT
        "sharp right" -> Man.SHARP_RIGHT
        "uturn" -> if (uturnToRight) Man.UTURN_RIGHT else Man.UTURN_LEFT
        else -> Man.STRAIGHT
    }

    /** The road shown next to the arrow: its name, or its reference ("NH 48") when it has no name. */
    private fun street(step: JSONObject): String = str(step, "name").ifBlank { str(step, "ref") }.trim()

    /** Reads a string field, treating missing and JSON null as "". (org.json turns null into the text "null".) */
    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "")

    private fun readLocation(maneuver: JSONObject): LatLon? {
        val loc = maneuver.optJSONArray("location") ?: return null
        if (loc.length() < 2) return null
        return LatLon(loc.getDouble(1), loc.getDouble(0))   // OSRM order is [lon, lat]
    }

    private fun readGeometry(holder: JSONObject, precision: Int): List<LatLon> {
        if (holder.isNull("geometry")) return emptyList()
        return when (val g = holder.get("geometry")) {
            is String -> if (g.isEmpty()) emptyList() else PolylineCodec.decode(g, precision)
            is JSONObject -> {
                val coords = g.optJSONArray("coordinates") ?: return emptyList()
                List(coords.length()) {
                    val c = coords.getJSONArray(it)
                    LatLon(c.getDouble(1), c.getDouble(0))
                }
            }
            else -> emptyList()
        }
    }
}
