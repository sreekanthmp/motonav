package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteWindowCodecTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // The SAME bytes are asserted by the firmware's PC test (firmware/prototype-esp32/test_host/test_nav_route.cpp).
    private val goldenPoints = listOf(
        LatLon(12.971600, 77.594600), LatLon(12.972000, 77.594600), LatLon(12.972000, 77.595100),
        LatLon(12.972500, 77.595100), LatLon(12.972300, 77.594900),
    )
    private val goldenChunk0 = "0700020101050050eec500e8ff9f049001000000"
    private val goldenChunk1 = "07010200f401f401000038ff38ff"
    private val goldenPos = "072a76f0c500b000a004"

    @Test
    fun encodesTheGoldenBytesTheDeviceTestExpects() {
        val chunks = RouteWindowCodec.chunk(RouteWindowCodec.encodeStream(goldenPoints, true), routeId = 7)
        assertEquals(listOf(goldenChunk0, goldenChunk1), chunks.map { hex(it) })
    }

    @Test
    fun encodesTheGoldenPosition() {
        val pos = NavProtocol.encodePos(7, 42, 12972150, 77594800)
        assertEquals(goldenPos, hex(pos))
        assertEquals(NavProtocol.POS_SIZE, pos.size)
    }

    @Test
    fun decodesBackToTheSamePoints() {
        val chunks = RouteWindowCodec.chunk(RouteWindowCodec.encodeStream(goldenPoints, true), 7)
        val w = requireNotNull(RouteWindowCodec.decode(chunks))
        assertEquals(7, w.routeId)
        assertTrue(w.endsAtDestination)
        assertEquals(5, w.count)
        for (i in goldenPoints.indices) {
            assertEquals(NavProtocol.toE6(goldenPoints[i].lat), w.latE6[i])
            assertEquals(NavProtocol.toE6(goldenPoints[i].lon), w.lonE6[i])
        }
    }

    @Test
    fun theDestinationFlagIsCarried() {
        val chunks = RouteWindowCodec.chunk(RouteWindowCodec.encodeStream(goldenPoints, false), 1)
        assertEquals(false, RouteWindowCodec.decode(chunks)!!.endsAtDestination)
    }

    @Test
    fun chunksNeverExceedTheWritePayload() {
        val pts = List(96) { LatLon(12.9 + it * 0.0005, 77.5 + (it % 7) * 0.0004) }
        val stream = RouteWindowCodec.encodeStream(pts, false)
        assertEquals(12 + 4 * 95, stream.size)
        for (payload in listOf(20, 100, 185, 512)) {
            val chunks = RouteWindowCodec.chunk(stream, 3, payload)
            assertTrue("payload $payload", chunks.all { it.size <= payload })
            assertEquals((stream.size + payload - 4) / (payload - 3), chunks.size)
            assertNotNull(RouteWindowCodec.decode(chunks))
        }
        // The whole point of a larger MTU: far fewer writes.
        assertEquals(24, RouteWindowCodec.chunk(stream, 3, 20).size)
        assertEquals(3, RouteWindowCodec.chunk(stream, 3, 185).size)
    }

    @Test
    fun stepsTooLongForSixteenBitsAreSplitNotLost() {
        val far = listOf(LatLon(12.0, 77.0), LatLon(12.2, 77.3))   // ~20,000 microdegrees... times ten: far beyond 32,767
        val w = RouteWindowCodec.decode(RouteWindowCodec.chunk(RouteWindowCodec.encodeStream(far, false), 1))!!
        assertTrue(w.count > 2)
        assertEquals(NavProtocol.toE6(12.0), w.latE6.first())
        assertEquals(NavProtocol.toE6(77.3), w.lonE6.last())
        for (i in 1 until w.count) {
            assertTrue(Math.abs(w.latE6[i] - w.latE6[i - 1]) <= 32767)
            assertTrue(Math.abs(w.lonE6[i] - w.lonE6[i - 1]) <= 32767)
        }
    }

    @Test
    fun refusesTooFewOrTooManyPoints() {
        assertThrows(IllegalArgumentException::class.java) { RouteWindowCodec.encodeStream(listOf(LatLon(1.0, 1.0)), false) }
        val tooMany = List(97) { LatLon(12.0 + it * 0.0001, 77.0) }
        assertThrows(IllegalArgumentException::class.java) { RouteWindowCodec.encodeStream(tooMany, false) }
    }

    @Test
    fun theTestDecoderRejectsWhatTheDeviceWouldReject() {
        val good = RouteWindowCodec.chunk(RouteWindowCodec.encodeStream(goldenPoints, true), 7)
        assertNotNull(RouteWindowCodec.decode(good))
        assertNull(RouteWindowCodec.decode(good.take(1)))                       // a chunk is missing
        assertNull(RouteWindowCodec.decode(good.reversed()))                    // out of order
        val wrongVersion = good.map { it.copyOf() }.also { it[0][3] = 2 }        // stream version byte
        assertNull(RouteWindowCodec.decode(wrongVersion))
        val trailing = good.toMutableList().also { it[1] = it[1] + byteArrayOf(0) }
        assertNull(RouteWindowCodec.decode(trailing))
        assertNull(RouteWindowCodec.decode(emptyList()))
    }

    @Test
    fun positionIsLittleEndianAndWraps() {
        val b = NavProtocol.encodePos(300, 260, -12345678, 177000000)
        assertArrayEquals(byteArrayOf(0x2C, 0x04), b.copyOfRange(0, 2))          // ids wrap to one byte
        assertEquals(10, b.size)
    }
}
