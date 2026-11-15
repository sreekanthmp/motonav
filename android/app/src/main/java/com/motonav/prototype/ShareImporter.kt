package com.motonav.prototype

import java.net.HttpURLConnection
import java.net.URL

sealed class ImportResult {
    data class Ok(val place: SharedPlace) : ImportResult()

    /** The link was understood but carries no coordinates (e.g. a directions link with place names only). */
    data class NeedsLookup(val name: String?) : ImportResult()

    data class Failed(val reason: String) : ImportResult()
}

/**
 * Turns whatever Google Maps shared into a destination.
 * Network access happens only for Google Maps short links, and only to Google hosts.
 * Call from a background thread.
 */
object ShareImporter {

    fun import(sharedText: String): ImportResult {
        val text = sharedText.trim()
        if (text.isEmpty()) return ImportResult.Failed("Nothing was shared")

        if (text.startsWith("geo:", ignoreCase = true)) return toResult(ShareParser.parse(text))

        val url = ShareParser.extractUrl(text)
            ?: return ImportResult.Failed("No link found in the shared text")
        if (!ShareParser.isAllowedHost(url)) return ImportResult.Failed("That is not a Google Maps link")

        val nameFromText = ShareParser.nameFromSharedText(text, url)

        var finalUrl = url
        if (ShareParser.isShortLink(url)) {
            finalUrl = LinkResolver.resolve(url)
                ?: return ImportResult.Failed("Could not open the short link — check your internet connection")
        }

        val parsed = ShareParser.parse(finalUrl)
        val place = if (parsed.name == null && nameFromText != null) parsed.copy(name = nameFromText) else parsed
        return toResult(place)
    }

    private fun toResult(place: SharedPlace): ImportResult =
        if (place.hasCoords) ImportResult.Ok(place) else ImportResult.NeedsLookup(place.name)
}

/** Follows redirects of a Google Maps short link by hand, staying on Google hosts only. */
object LinkResolver {
    private const val MAX_HOPS = 6
    private const val TIMEOUT_MS = 8_000

    fun resolve(shortUrl: String): String? {
        var url = shortUrl
        repeat(MAX_HOPS) {
            if (!ShareParser.isAllowedHost(url)) return null
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MotoNav/0.1")
                val code = conn.responseCode
                if (code !in 300..399) return ShareParser.unwrapConsent(url)
                val location = conn.getHeaderField("Location") ?: return url
                url = ShareParser.unwrapConsent(URL(URL(url), location).toString())
            } finally {
                conn.disconnect()
            }
        }
        return url
    }
}
