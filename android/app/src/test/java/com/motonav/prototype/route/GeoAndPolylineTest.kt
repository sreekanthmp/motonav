package com.motonav.prototype.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GeoTest {

    @Test
    fun oneDegreeIsAbout111Km() {
        assertEquals(111_195.0, Geo.haversineM(0.0, 0.0, 1.0, 0.0), 50.0)
        assertEquals(111_195.0, Geo.haversineM(0.0, 0.0, 0.0, 1.0), 50.0)
        assertEquals(0.0, Geo.haversineM(12.97, 77.59, 12.97, 77.59), 1e-9)
    }

    @Test
    fun distanceToSegmentIsPerpendicularInTheMiddle() {
        val a = TestRoutes.ORIGIN
        val b = TestRoutes.offset(a, 0.0, 100.0)               // 100 m due north
        val p = TestRoutes.offset(TestRoutes.offset(a, 0.0, 50.0), 90.0, 30.0)   // 50 m along, 30 m east
        val t = DoubleArray(1)
        assertEquals(30.0, Geo.distanceToSegmentM(p.lat, p.lon, a.lat, a.lon, b.lat, b.lon, t), 0.2)
        assertEquals(0.5, t[0], 0.01)
    }

    @Test
    fun distanceToSegmentClampsAtTheEnds() {
        val a = TestRoutes.ORIGIN
        val b = TestRoutes.offset(a, 0.0, 100.0)
        val t = DoubleArray(1)

        val beyond = TestRoutes.offset(a, 0.0, 200.0)         // 100 m past B
        assertEquals(100.0, Geo.distanceToSegmentM(beyond.lat, beyond.lon, a.lat, a.lon, b.lat, b.lon, t), 0.2)
        assertEquals(1.0, t[0], 1e-9)

        val before = TestRoutes.offset(a, 180.0, 40.0)        // 40 m before A
        assertEquals(40.0, Geo.distanceToSegmentM(before.lat, before.lon, a.lat, a.lon, b.lat, b.lon, t), 0.2)
        assertEquals(0.0, t[0], 1e-9)
    }

    @Test
    fun zeroLengthSegmentIsJustAPoint() {
        val a = TestRoutes.ORIGIN
        val p = TestRoutes.offset(a, 90.0, 25.0)
        assertEquals(25.0, Geo.distanceToSegmentM(p.lat, p.lon, a.lat, a.lon, a.lat, a.lon), 0.2)
    }
}

class PolylineCodecTest {

    @Test
    fun decodesTheCanonicalGoogleExample() {
        val pts = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-9);   assertEquals(-120.2, pts[0].lon, 1e-9)
        assertEquals(40.7, pts[1].lat, 1e-9);   assertEquals(-120.95, pts[1].lon, 1e-9)
        assertEquals(43.252, pts[2].lat, 1e-9); assertEquals(-126.453, pts[2].lon, 1e-9)
    }

    @Test
    fun encodesTheCanonicalGoogleExample() {
        val pts = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(pts))
    }

    @Test
    fun roundTripsAtPrecision6AroundIndia() {
        val rnd = Random(11)
        val pts = List(200) { LatLon(8.0 + rnd.nextDouble() * 25, 68.0 + rnd.nextDouble() * 30) }
        val back = PolylineCodec.decode(PolylineCodec.encode(pts, 6), 6)
        assertEquals(pts.size, back.size)
        for (i in pts.indices) {
            assertEquals(pts[i].lat, back[i].lat, 1e-6)
            assertEquals(pts[i].lon, back[i].lon, 1e-6)
        }
    }

    @Test
    fun roundTripsNegativeCoordinates() {
        val pts = listOf(LatLon(-33.8688, 151.2093), LatLon(-33.8700, 151.2100), LatLon(-34.0, -151.5))
        val back = PolylineCodec.decode(PolylineCodec.encode(pts, 5), 5)
        for (i in pts.indices) {
            assertEquals(pts[i].lat, back[i].lat, 1e-5)
            assertEquals(pts[i].lon, back[i].lon, 1e-5)
        }
    }

    @Test
    fun emptyStringIsAnEmptyLine() {
        assertTrue(PolylineCodec.decode("").isEmpty())
    }

    @Test
    fun truncatedInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`")
        }
    }

    @Test
    fun illegalCharactersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("_p~iF ~ps|U") }
    }
}
