package com.motonav.prototype.route

/**
 * The "encoded polyline" format used by Google, OSRM, Mapbox, Valhalla and most routing services.
 * [precision] is the number of decimal places: 5 for the classic format, 6 for "polyline6".
 * Decoding the wrong precision gives coordinates that are off by a factor of 10, so callers should
 * sanity-check the result (see [OsrmRouteParser]).
 */
object PolylineCodec {

    /** @throws IllegalArgumentException on truncated or corrupt input. */
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        require(precision in 0..9) { "precision must be 0..9" }
        val factor = Math.pow(10.0, precision.toDouble())
        val cursor = Cursor(encoded)
        val out = ArrayList<LatLon>(encoded.length / 4 + 1)
        var lat = 0L
        var lon = 0L
        while (cursor.hasMore()) {
            lat += cursor.nextValue()
            lon += cursor.nextValue()
            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }

    fun encode(points: List<LatLon>, precision: Int = 5): String {
        require(precision in 0..9) { "precision must be 0..9" }
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder(points.size * 6)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lon = Math.round(p.lon * factor)
            put(sb, lat - prevLat)
            put(sb, lon - prevLon)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun put(sb: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20L or (v and 0x1fL)) + 63).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }

    private class Cursor(private val s: String) {
        private var i = 0

        fun hasMore(): Boolean = i < s.length

        fun nextValue(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Truncated polyline")
                val b = s[i++].code - 63
                if (b < 0 || b > 63) throw IllegalArgumentException("Invalid polyline character at ${i - 1}")
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
                if (shift > 60) throw IllegalArgumentException("Polyline number too long")
            }
            return if ((result and 1L) != 0L) (result shr 1).inv() else result shr 1
        }
    }
}
