package com.motonav.prototype

import java.net.URLDecoder

/** A destination read from a shared Google Maps link. */
data class SharedPlace(
    val lat: Double?,
    val lng: Double?,
    val name: String?,
    /** True when only the map-view centre (@lat,lng) was available, not the place itself. */
    val approximate: Boolean = false,
) {
    val hasCoords: Boolean get() = lat != null && lng != null
}

/**
 * Pure parsing of what Google Maps shares: text containing a link, a maps.google.com / google.com/maps
 * URL, or a geo: URI. No network and no Android classes, so it is unit-tested on the JVM.
 *
 * Google's URL formats are not a stable API: every case that cannot be read must end in a clear
 * message to the user (see [ShareImporter]), never a wrong destination.
 */
object ShareParser {
    private const val NUM = """-?\d{1,3}(?:\.\d+)?"""

    private val RE_PLACE = Regex("""!3d($NUM)!4d($NUM)""")        // the place itself (best source)
    private val RE_AT = Regex("""@($NUM),($NUM)""")               // map-view centre (approximate)
    private val RE_PAIR = Regex("""^\s*($NUM)\s*,\s*($NUM)\s*$""")
    private val RE_GEO_Q = Regex("""^\s*($NUM)\s*,\s*($NUM)\s*(?:\((.*)\))?\s*$""")
    private val RE_URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val RE_HOST = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE)
    private val RE_GOOGLE_HOST = Regex("""(^|\.)google\.[a-z]{2,3}(\.[a-z]{2})?$""")

    /** Query parameters Google uses to carry a destination, in order of preference. */
    private val COORD_PARAMS = listOf("destination", "daddr", "q", "query", "ll", "center", "sll")

    // ---- small helpers ------------------------------------------------------------------

    private fun pair(latText: String, lngText: String): Pair<Double, Double>? {
        val lat = latText.toDoubleOrNull() ?: return null
        val lng = lngText.toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }

    private fun queryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')).lowercase()
            val value = decode(part.substringAfter('=', ""))
            if (key !in out) out[key] = value
        }
        return out
    }

    // ---- public API ---------------------------------------------------------------------

    /** First http(s) link inside shared text (Google Maps shares "Name\nAddress\nlink"). */
    fun extractUrl(text: String): String? =
        RE_URL.find(text)?.value?.trimEnd('.', ',', ')', ']')

    fun host(url: String): String? = RE_HOST.find(url)?.groupValues?.get(1)?.lowercase()

    /** Only Google Maps hosts are ever opened or trusted. */
    fun isAllowedHost(url: String): Boolean {
        val h = host(url) ?: return false
        return h == "maps.app.goo.gl" || h == "goo.gl" || h == "g.co" || RE_GOOGLE_HOST.containsMatchIn(h)
    }

    /** Short links need one network hop to reveal the real URL. */
    fun isShortLink(url: String): Boolean {
        val h = host(url) ?: return false
        return h == "maps.app.goo.gl" || h == "goo.gl" || h == "g.co"
    }

    /** In some regions Google wraps the link in a consent page: .../m?continue=<real url>. */
    fun unwrapConsent(url: String): String {
        val h = host(url) ?: return url
        if (!h.startsWith("consent.google.")) return url
        return queryParams(url.substringAfter('?', ""))["continue"] ?: url
    }

    /** The place name Google puts on the first line of the shared text, if any. */
    fun nameFromSharedText(text: String, url: String): String? {
        val before = text.substringBefore(url).trim()
        val line = before.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return line.take(80)
    }

    /** Parses a (resolved) Google Maps URL or a geo: URI. */
    fun parse(input: String): SharedPlace {
        val s = input.trim()
        return if (s.startsWith("geo:", ignoreCase = true)) parseGeo(s) else parseUrl(unwrapConsent(s))
    }

    // ---- geo: URIs ----------------------------------------------------------------------

    private fun parseGeo(s: String): SharedPlace {
        val body = s.substring(4)
        val head = body.substringBefore('?').substringBefore(';')
        val query = body.substringAfter('?', "")

        var coords = RE_PAIR.find(head)?.let { pair(it.groupValues[1], it.groupValues[2]) }
        if (coords != null && coords.first == 0.0 && coords.second == 0.0) coords = null   // geo:0,0 = "no point"

        var name: String? = null
        val q = queryParams(query)["q"]
        if (q != null) {
            val m = RE_GEO_Q.find(q)
            if (m != null) {
                if (coords == null) coords = pair(m.groupValues[1], m.groupValues[2])
                name = m.groupValues[3].ifBlank { null }
            } else {
                name = q.ifBlank { null }
            }
        }
        return SharedPlace(coords?.first, coords?.second, name)
    }

    // ---- google.com/maps URLs ------------------------------------------------------------

    private fun parseUrl(url: String): SharedPlace {
        val noFragment = url.substringBefore('#')
        val path = noFragment.substringAfter("://").substringAfter('/', "").substringBefore('?')
        val params = queryParams(noFragment.substringAfter('?', ""))
        val segs = path.split('/').filter { it.isNotEmpty() }
        val isDir = "dir" in segs

        var name: String? = null
        var pathPair: Pair<Double, Double>? = null

        // /maps/place/<name>/...  or  /maps/search/<name or lat,lng>/...
        val i = segs.indexOfFirst { it == "place" || it == "search" }
        if (i >= 0 && i + 1 < segs.size) {
            val seg = decode(segs[i + 1])
            if (!seg.startsWith("@") && !seg.startsWith("data=")) {
                val m = RE_PAIR.find(seg)
                if (m != null) pathPair = pair(m.groupValues[1], m.groupValues[2]) else name = seg
            }
        }

        // /maps/dir/<from>/<via...>/<to>/@view/data=...   -> the last point is the destination
        var dirPair: Pair<Double, Double>? = null
        if (isDir) {
            val points = segs.drop(segs.indexOf("dir") + 1)
                .takeWhile { !it.startsWith("@") && !it.startsWith("data=") }
            val last = points.lastOrNull()?.let { decode(it) }
            if (last != null) {
                val m = RE_PAIR.find(last)
                if (m != null) dirPair = pair(m.groupValues[1], m.groupValues[2]) else name = last
            }
        }

        var coords: Pair<Double, Double>? = null
        var approximate = false

        val dataMatches = RE_PLACE.findAll(path).toList()
        if (dataMatches.isNotEmpty()) {
            val m = if (isDir) dataMatches.last() else dataMatches.first()
            coords = pair(m.groupValues[1], m.groupValues[2])
        }
        if (coords == null) {
            for (key in COORD_PARAMS) {
                val v = params[key] ?: continue
                val m = RE_PAIR.find(v)
                if (m != null) {
                    coords = pair(m.groupValues[1], m.groupValues[2])
                    if (coords != null) break
                } else if (name == null && (key == "q" || key == "query")) {
                    name = v.ifBlank { null }
                }
            }
        }
        if (coords == null) coords = dirPair
        if (coords == null) coords = pathPair
        if (coords == null && !isDir) {   // never guess from the map view of a directions link
            RE_AT.find(path)?.let { m ->
                coords = pair(m.groupValues[1], m.groupValues[2])
                approximate = coords != null
            }
        }
        return SharedPlace(coords?.first, coords?.second, name, approximate)
    }
}
