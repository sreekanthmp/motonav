package com.motonav.prototype

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {

    @Test
    fun navCoreIsEighteenBytesLittleEndian() {
        val b = NavProtocol.encodeCore(
            mode = Mode.NAV, maneuver = Man.LEFT, roundaboutExit = 0, seq = 0x0102,
            distToManeuverM = 300, remainingM = 5200, etaMinutes = 12, speedKmhX10 = 420,
        )
        assertEquals(18, b.size)
        // version, mode, maneuver, exit
        assertArrayEquals(byteArrayOf(1, 1, 4, 0), b.copyOfRange(0, 4))
        // seq 0x0102 little-endian
        assertArrayEquals(byteArrayOf(0x02, 0x01), b.copyOfRange(4, 6))
        // dist 300 = 0x012C little-endian u32
        assertArrayEquals(byteArrayOf(0x2C, 0x01, 0, 0), b.copyOfRange(6, 10))
        // remaining 5200 = 0x1450
        assertArrayEquals(byteArrayOf(0x50, 0x14, 0, 0), b.copyOfRange(10, 14))
        // eta 12, speed 420 = 0x01A4
        assertArrayEquals(byteArrayOf(12, 0, 0xA4.toByte(), 0x01), b.copyOfRange(14, 18))
    }

    @Test
    fun valuesAreClamped() {
        val b = NavProtocol.encodeCore(Mode.NAV, Man.STRAIGHT, 999, 70000, -5, 1L shl 40, 100000, 100000)
        assertEquals(18, b.size)
        assertEquals(255, b[3].toInt() and 0xFF)                 // exit clamped
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), b.copyOfRange(6, 10))   // negative distance -> 0
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), b.copyOfRange(10, 14)) // huge -> 0xFFFFFFFF
    }

    @Test
    fun streetIsAsciiAndTruncated() {
        assertEquals("Brigade Road", NavProtocol.toAsciiStreet("Brigade Road"))
        assertEquals("Cafe Rd", NavProtocol.toAsciiStreet("Café Rd"))
        assertEquals(20, NavProtocol.toAsciiStreet("A very very long street name indeed").length)
        assertEquals("", NavProtocol.toAsciiStreet("ಬ್ರಿಗೇಡ್ ರಸ್ತೆ"))   // non-Latin script -> empty for the OLED
        assertEquals("", NavProtocol.toAsciiStreet(null))
    }

    @Test
    fun coordsParsing() {
        assertEquals(12.9352 to 77.6245, Coords.parse("12.9352, 77.6245"))
        assertNull(Coords.parse("12.9352"))
        assertNull(Coords.parse("abc,def"))
        assertNull(Coords.parse("95,10"))
    }

    @Test
    fun maneuverMapping() {
        assertEquals(Man.LEFT, NavMapper.maneuver(Maneuver.TURN_LEFT))
        assertEquals(Man.RIGHT, NavMapper.maneuver(Maneuver.TURN_RIGHT))
        assertEquals(Man.SLIGHT_LEFT, NavMapper.maneuver(Maneuver.TURN_SLIGHT_LEFT))
        assertEquals(Man.SHARP_RIGHT, NavMapper.maneuver(Maneuver.TURN_SHARP_RIGHT))
        assertEquals(Man.KEEP_RIGHT, NavMapper.maneuver(Maneuver.FORK_RIGHT))
        assertEquals(Man.UTURN_RIGHT, NavMapper.maneuver(Maneuver.TURN_U_TURN_CLOCKWISE))
        assertEquals(Man.UTURN_LEFT, NavMapper.maneuver(Maneuver.TURN_U_TURN_COUNTERCLOCKWISE))
        assertEquals(Man.RAMP_LEFT, NavMapper.maneuver(Maneuver.OFF_RAMP_LEFT))
        assertEquals(Man.RAMP_RIGHT, NavMapper.maneuver(Maneuver.ON_RAMP_SLIGHT_RIGHT))
        assertEquals(Man.ARRIVE_LEFT, NavMapper.maneuver(Maneuver.DESTINATION_LEFT))
        assertEquals(Man.FERRY, NavMapper.maneuver(Maneuver.FERRY_TRAIN))
        assertEquals(Man.NONE, NavMapper.maneuver(Maneuver.UNKNOWN))
    }

    @Test
    fun everyRoundaboutVariantMapsToRoundabout() {
        for (m in Maneuver.ROUNDABOUT_CLOCKWISE..Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE) {
            assertEquals("sdk maneuver $m", Man.ROUNDABOUT, NavMapper.maneuver(m))
        }
    }

    @Test
    fun everySdkManeuverMapsToSomethingValid() {
        for (m in 0..65) {
            val d = NavMapper.maneuver(m)
            assertEquals("sdk maneuver $m -> $d", true, d in Man.NONE..Man.ARRIVE_RIGHT)
        }
    }

    @Test
    fun modeMapping() {
        assertEquals(Mode.NAV, NavMapper.mode(NavState.ENROUTE, false))
        assertEquals(Mode.REROUTING, NavMapper.mode(NavState.REROUTING, false))
        assertEquals(Mode.IDLE, NavMapper.mode(NavState.STOPPED, false))
        assertEquals(Mode.ARRIVED, NavMapper.mode(NavState.ENROUTE, true))
    }

    @Test
    fun unitConversions() {
        assertEquals(12, NavMapper.etaMinutes(700))   // 11.67 min -> 12
        assertEquals(0, NavMapper.etaMinutes(null))
        assertEquals(360, NavMapper.speedKmhX10(10f)) // 10 m/s = 36 km/h
        assertEquals(0, NavMapper.speedKmhX10(-1f))
    }
}
