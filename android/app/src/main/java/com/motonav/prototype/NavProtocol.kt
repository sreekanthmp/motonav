package com.motonav.prototype

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.UUID

/**
 * MotoNav BLE protocol v1 — mirror of protocol/nav_protocol.h. Keep the two in sync.
 */
object NavProtocol {
    const val VERSION = 1
    const val CORE_SIZE = 18
    const val STREET_MAX = 20
    const val DEVICE_NAME = "MotoNav"

    val SERVICE_UUID: UUID = UUID.fromString("4d6f746f-4e61-7601-8a5c-3f2d1b0e0001")
    val NAVCORE_UUID: UUID = UUID.fromString("4d6f746f-4e61-7601-8a5c-3f2d1b0e0002")
    val NAVSTREET_UUID: UUID = UUID.fromString("4d6f746f-4e61-7601-8a5c-3f2d1b0e0003")
    val EVENTS_UUID: UUID = UUID.fromString("4d6f746f-4e61-7601-8a5c-3f2d1b0e0004")

    // Map feed (additive: a device without these characteristics still works). See protocol/README.md.
    val NAVROUTE_UUID: UUID = UUID.fromString("4d6f746f-4e61-7601-8a5c-3f2d1b0e0005")
    val NAVPOS_UUID: UUID = UUID.fromString("4d6f746f-4e61-7601-8a5c-3f2d1b0e0006")
    const val POS_SIZE = 10
    const val ROUTE_STREAM_VERSION = 1
    const val ROUTE_MAX_POINTS = 96
    const val ROUTE_CHUNK_HEADER = 3
    /** Bytes per BLE write that always work (default ATT MTU 23 minus 3). */
    const val DEFAULT_WRITE_PAYLOAD = 20

    object Mode {
        const val IDLE = 0
        const val NAV = 1
        const val OFF_ROUTE = 2
        const val REROUTING = 3
        const val ARRIVED = 4
    }

    object Man {
        const val NONE = 0
        const val DEPART = 1
        const val STRAIGHT = 2
        const val SLIGHT_LEFT = 3
        const val LEFT = 4
        const val SHARP_LEFT = 5
        const val SLIGHT_RIGHT = 6
        const val RIGHT = 7
        const val SHARP_RIGHT = 8
        const val UTURN_LEFT = 9
        const val UTURN_RIGHT = 10
        const val KEEP_LEFT = 11
        const val KEEP_RIGHT = 12
        const val MERGE = 13
        const val RAMP_LEFT = 14
        const val RAMP_RIGHT = 15
        const val ROUNDABOUT = 16
        const val FERRY = 17
        const val ARRIVE = 18
        const val ARRIVE_LEFT = 19
        const val ARRIVE_RIGHT = 20
    }

    /** 18-byte NavCore, little-endian. Values are clamped to what the fields can hold. */
    fun encodeCore(
        mode: Int,
        maneuver: Int,
        roundaboutExit: Int,
        seq: Int,
        distToManeuverM: Long,
        remainingM: Long,
        etaMinutes: Int,
        speedKmhX10: Int,
    ): ByteArray {
        val b = ByteBuffer.allocate(CORE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(VERSION.toByte())
        b.put(mode.coerceIn(0, 255).toByte())
        b.put(maneuver.coerceIn(0, 255).toByte())
        b.put(roundaboutExit.coerceIn(0, 255).toByte())
        b.putShort((seq and 0xFFFF).toShort())
        b.putInt(distToManeuverM.coerceIn(0L, 0xFFFFFFFFL).toInt())
        b.putInt(remainingM.coerceIn(0L, 0xFFFFFFFFL).toInt())
        b.putShort(etaMinutes.coerceIn(0, 0xFFFF).toShort())
        b.putShort(speedKmhX10.coerceIn(0, 0xFFFF).toShort())
        return b.array()
    }

    /** Degrees to the wire unit (degrees x 1e-6, about 0.11 m). */
    fun toE6(degrees: Double): Int = Math.round(degrees * 1e6).toInt()

    /** 10-byte NavPos, little-endian: route window id, sequence, latitude, longitude. */
    fun encodePos(routeId: Int, seq: Int, latE6: Int, lonE6: Int): ByteArray {
        val b = ByteBuffer.allocate(POS_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put((routeId and 0xFF).toByte())
        b.put((seq and 0xFF).toByte())
        b.putInt(latE6)
        b.putInt(lonE6)
        return b.array()
    }

    /**
     * The OLED font is ASCII-only, so strip accents and drop names that are mostly
     * non-Latin (Devanagari, Tamil, ...). TODO: prefer an English / transliterated name.
     */
    fun toAsciiStreet(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val stripped = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val nonAscii = stripped.count { it.code !in 0x20..0x7E }
        if (nonAscii * 2 > stripped.length) return ""
        val ascii = buildString { stripped.forEach { append(if (it.code in 0x20..0x7E) it else ' ') } }
        return ascii.trim().replace(Regex("\\s+"), " ").take(STREET_MAX)
    }

    fun encodeStreet(name: String?): ByteArray = toAsciiStreet(name).toByteArray(Charsets.US_ASCII)
}
