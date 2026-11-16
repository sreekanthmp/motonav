package com.motonav.prototype

import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {

    @Test
    fun navigationLine() {
        assertEquals(
            "In 300 m: Turn left onto Brigade Road\n4.2 km left · 12 min",
            NavMapper.statusLine(Mode.NAV, Man.LEFT, 0, 300, "Brigade Road", 4200, 12),
        )
    }

    @Test
    fun lineWithoutStreetName() {
        assertEquals(
            "In 80 m: Turn right\n900 m left · 3 min",
            NavMapper.statusLine(Mode.NAV, Man.RIGHT, 0, 80, "", 900, 3),
        )
    }

    @Test
    fun roundaboutShowsTheExit() {
        assertEquals(
            "In 150 m: Roundabout: take exit 2 onto Silk Board\n3.0 km left · 8 min",
            NavMapper.statusLine(Mode.NAV, Man.ROUNDABOUT, 2, 150, "Silk Board", 3000, 8),
        )
    }

    @Test
    fun otherModes() {
        assertEquals("Rerouting…", NavMapper.statusLine(Mode.REROUTING, Man.NONE, 0, 0, "", 0, 0))
        assertEquals("You have arrived", NavMapper.statusLine(Mode.ARRIVED, Man.ARRIVE, 0, 0, "", 0, 0))
        assertEquals("Not navigating", NavMapper.statusLine(Mode.IDLE, Man.NONE, 0, 0, "", 0, 0))
    }

    @Test
    fun distanceFormats() {
        assertEquals("950 m", NavMapper.distanceText(950))
        assertEquals("1.2 km", NavMapper.distanceText(1234))
        assertEquals("12 km", NavMapper.distanceText(12000))
    }
}
