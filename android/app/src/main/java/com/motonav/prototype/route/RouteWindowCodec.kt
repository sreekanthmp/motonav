package com.motonav.prototype.route

import com.motonav.prototype.NavProtocol
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encoder (and a test-side decoder) for the NavRoute map feed. The wire format is in protocol/README.md and is
 * decoded on the device by protocol/nav_route.h; both are tested against the same golden bytes.
 *
 * A route window is sent as one byte stream, split into chunks of at most the write payload:
 * ```
 * chunk  : [route id u8][chunk index u8][chunk count u8][data ...]
 * stream : [version u8 = 1][flags u8: bit0 = ends at the destination][point count u16]
 *          [first latitude i32][first longitude i32]            degrees x 1e-6, little-endian
 *          then, for every further point: [d latitude i16][d longitude i16]   (difference from the previous point)
 * ```
 */
object RouteWindowCodec {
    private const val MAX_DELTA = 32767
    private const val HEADER = 12

    /** A decoded window (used by tests and tools; the device has its own decoder). */
    class Window(val routeId: Int, val endsAtDestination: Boolean, val latE6: IntArray, val lonE6: IntArray) {
        val count: Int get() = latE6.size
    }

    /**
     * @param points at least two points; after splitting steps that are too long for the 16-bit difference, at most
     *   [NavProtocol.ROUTE_MAX_POINTS]
     * @throws IllegalArgumentException when there are fewer than two points or too many
     */
    fun encodeStream(points: List<LatLon>, endsAtDestination: Boolean): ByteArray {
        require(points.size >= 2) { "A route window needs at least two points" }
        val lat = IntArray(points.size) { NavProtocol.toE6(points[it].lat) }
        val lon = IntArray(points.size) { NavProtocol.toE6(points[it].lon) }
        val (la, lo) = splitLongSteps(lat, lon)
        require(la.size <= NavProtocol.ROUTE_MAX_POINTS) { "Too many points for one window: ${la.size}" }

        val b = ByteBuffer.allocate(HEADER + 4 * (la.size - 1)).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NavProtocol.ROUTE_STREAM_VERSION.toByte())
        b.put((if (endsAtDestination) 1 else 0).toByte())
        b.putShort(la.size.toShort())
        b.putInt(la[0])
        b.putInt(lo[0])
        for (i in 1 until la.size) {
            b.putShort((la[i] - la[i - 1]).toShort())
            b.putShort((lo[i] - lo[i - 1]).toShort())
        }
        return b.array()
    }

    /** Splits [stream] into BLE writes of at most [writePayload] bytes each (default 20: works without MTU negotiation). */
    fun chunk(stream: ByteArray, routeId: Int, writePayload: Int = NavProtocol.DEFAULT_WRITE_PAYLOAD): List<ByteArray> {
        val perChunk = writePayload - NavProtocol.ROUTE_CHUNK_HEADER
        require(perChunk >= 1) { "Write payload too small" }
        val total = (stream.size + perChunk - 1) / perChunk
        require(total in 1..255) { "A window must fit in 255 chunks" }
        return List(total) { i ->
            val from = i * perChunk
            val to = minOf(stream.size, from + perChunk)
            val out = ByteArrayOutputStream(NavProtocol.ROUTE_CHUNK_HEADER + to - from)
            out.write(routeId and 0xFF)
            out.write(i)
            out.write(total)
            out.write(stream, from, to - from)
            out.toByteArray()
        }
    }

    /** Mirror of the device's decoder, for tests. Returns null for anything the device would reject. */
    fun decode(chunks: List<ByteArray>): Window? {
        if (chunks.isEmpty()) return null
        val total = chunks[0].getOrNull(2)?.toInt()?.and(0xFF) ?: return null
        val id = chunks[0][0].toInt() and 0xFF
        if (total == 0 || chunks.size != total) return null
        val stream = ByteArrayOutputStream()
        for ((i, c) in chunks.withIndex()) {
            if (c.size <= NavProtocol.ROUTE_CHUNK_HEADER) return null
            if ((c[0].toInt() and 0xFF) != id || (c[1].toInt() and 0xFF) != i || (c[2].toInt() and 0xFF) != total) return null
            stream.write(c, NavProtocol.ROUTE_CHUNK_HEADER, c.size - NavProtocol.ROUTE_CHUNK_HEADER)
        }
        val s = stream.toByteArray()
        if (s.size < HEADER || s[0].toInt() != NavProtocol.ROUTE_STREAM_VERSION) return null
        val bb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.getShort(2).toInt() and 0xFFFF
        if (count < 2 || count > NavProtocol.ROUTE_MAX_POINTS || s.size != HEADER + 4 * (count - 1)) return null
        val lat = IntArray(count)
        val lon = IntArray(count)
        lat[0] = bb.getInt(4)
        lon[0] = bb.getInt(8)
        for (k in 1 until count) {
            lat[k] = lat[k - 1] + bb.getShort(HEADER + 4 * (k - 1)).toInt()
            lon[k] = lon[k - 1] + bb.getShort(HEADER + 4 * (k - 1) + 2).toInt()
        }
        if (lat.any { it !in -90_000_000..90_000_000 } || lon.any { it !in -180_000_000..180_000_000 }) return null
        return Window(id, (s[1].toInt() and 1) != 0, lat, lon)
    }

    /** Adds in-between points wherever a step is too long for a 16-bit difference, so no detail is lost. */
    private fun splitLongSteps(lat: IntArray, lon: IntArray): Pair<IntArray, IntArray> {
        val la = ArrayList<Int>(lat.size)
        val lo = ArrayList<Int>(lon.size)
        la.add(lat[0]); lo.add(lon[0])
        for (i in 1 until lat.size) {
            val dLat = lat[i].toLong() - lat[i - 1]
            val dLon = lon[i].toLong() - lon[i - 1]
            val parts = maxOf(1L, (maxOf(Math.abs(dLat), Math.abs(dLon)) + MAX_DELTA - 1) / MAX_DELTA).toInt()
            for (k in 1 until parts) {
                la.add((lat[i - 1] + dLat * k / parts).toInt())
                lo.add((lon[i - 1] + dLon * k / parts).toInt())
            }
            la.add(lat[i]); lo.add(lon[i])
        }
        return la.toIntArray() to lo.toIntArray()
    }
}
