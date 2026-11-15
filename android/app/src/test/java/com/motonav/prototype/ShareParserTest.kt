package com.motonav.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParserTest {

    private fun assertCoords(expectedLat: Double, expectedLng: Double, p: SharedPlace) {
        assertTrue("expected coordinates but got $p", p.hasCoords)
        assertEquals(expectedLat, p.lat!!, 1e-9)
        assertEquals(expectedLng, p.lng!!, 1e-9)
    }

    @Test
    fun placeUrlPrefersThePlaceCoordinatesOverTheMapCentre() {
        val url = "https://www.google.com/maps/place/Forum+Mall/@12.9345,77.6111,17z/data=" +
            "!3m1!4b1!4m6!3m5!1s0x3bae1456:0x123!8m2!3d12.9344!4d77.6110!16s%2Fg%2F123"
        val p = ShareParser.parse(url)
        assertCoords(12.9344, 77.6110, p)
        assertEquals("Forum Mall", p.name)
        assertFalse(p.approximate)
    }

    @Test
    fun placeUrlWithOnlyTheMapCentreIsMarkedApproximate() {
        val p = ShareParser.parse("https://www.google.com/maps/place/Some+Park/@12.9716,77.5946,15z")
        assertCoords(12.9716, 77.5946, p)
        assertTrue(p.approximate)
        assertEquals("Some Park", p.name)
    }

    @Test
    fun queryStyleLinks() {
        assertCoords(12.9716, 77.5946, ShareParser.parse("https://maps.google.com/?q=12.9716,77.5946"))
        assertCoords(
            12.9716, 77.5946,
            ShareParser.parse("https://www.google.com/maps/search/?api=1&query=12.9716%2C77.5946"),
        )
        assertCoords(
            12.9352, 77.6245,
            ShareParser.parse("https://www.google.com/maps/dir/?api=1&destination=12.9352,77.6245&travelmode=two-wheeler"),
        )
    }

    @Test
    fun queryWithAPlaceNameGivesNameButNoCoordinates() {
        val p = ShareParser.parse("https://www.google.com/maps/search/?api=1&query=Forum+Mall")
        assertFalse(p.hasCoords)
        assertEquals("Forum Mall", p.name)
    }

    @Test
    fun directionsWithCoordinatesUsesTheLastPoint() {
        val p = ShareParser.parse(
            "https://www.google.com/maps/dir/12.9716,77.5946/12.9352,77.6245/@12.95,77.6,13z",
        )
        assertCoords(12.9352, 77.6245, p)
    }

    @Test
    fun directionsWithOnlyNamesNeedsLookupAndNeverGuessesFromTheMapView() {
        val p = ShareParser.parse(
            "https://www.google.com/maps/dir/MG+Road/Koramangala/@12.9,77.6,12z/data=!4m2!4m1!3e0",
        )
        assertFalse("must not use the @ map centre of a directions link", p.hasCoords)
        assertEquals("Koramangala", p.name)
    }

    @Test
    fun viewportOnlyLink() {
        val p = ShareParser.parse("https://www.google.com/maps/@12.9716,77.5946,15z")
        assertCoords(12.9716, 77.5946, p)
        assertTrue(p.approximate)
    }

    @Test
    fun negativeCoordinates() {
        assertCoords(-33.8688, 151.2093, ShareParser.parse("https://maps.google.com/?q=-33.8688,151.2093"))
    }

    @Test
    fun geoUris() {
        val a = ShareParser.parse("geo:12.9716,77.5946?q=12.9716,77.5946(Cubbon%20Park)")
        assertCoords(12.9716, 77.5946, a)
        assertEquals("Cubbon Park", a.name)

        val b = ShareParser.parse("geo:12.9716,77.5946")
        assertCoords(12.9716, 77.5946, b)

        val c = ShareParser.parse("geo:0,0?q=Some%20Place")
        assertFalse(c.hasCoords)
        assertEquals("Some Place", c.name)

        assertFalse(ShareParser.parse("geo:95.0,10.0").hasCoords)   // latitude out of range
    }

    @Test
    fun extractsTheUrlAndNameFromGoogleMapsShareText() {
        val text = "Forum Mall\n21, Hosur Rd, Bengaluru\nhttps://maps.app.goo.gl/AbCdEf123"
        val url = ShareParser.extractUrl(text)
        assertEquals("https://maps.app.goo.gl/AbCdEf123", url)
        assertEquals("Forum Mall", ShareParser.nameFromSharedText(text, url!!))
        assertTrue(ShareParser.isShortLink(url))
        assertNull(ShareParser.extractUrl("no link here"))
    }

    @Test
    fun consentWrapperIsUnwrapped() {
        val wrapped = "https://consent.google.com/m?continue=" +
            "https://www.google.com/maps/place/X/@1.5,2.5,17z&gl=NL&m=0"
        assertEquals("https://www.google.com/maps/place/X/@1.5,2.5,17z", ShareParser.unwrapConsent(wrapped))
        assertCoords(1.5, 2.5, ShareParser.parse(wrapped))
    }

    @Test
    fun onlyGoogleMapsHostsAreAllowed() {
        assertTrue(ShareParser.isAllowedHost("https://maps.app.goo.gl/x"))
        assertTrue(ShareParser.isAllowedHost("https://www.google.com/maps/place/x"))
        assertTrue(ShareParser.isAllowedHost("https://www.google.co.in/maps/place/x"))
        assertTrue(ShareParser.isAllowedHost("https://maps.google.com/?q=1,2"))
        assertFalse(ShareParser.isAllowedHost("https://evil.example.com/maps"))
        assertFalse(ShareParser.isAllowedHost("https://notgoogle.com/maps"))
        assertFalse(ShareParser.isAllowedHost("https://google.com.evil.example/maps"))
        assertFalse(ShareParser.isAllowedHost("not a url"))
    }

    // ---- ShareImporter paths that need no network ------------------------------------------------

    @Test
    fun importerReadsALongLinkWithoutTheNetwork() {
        val r = ShareImporter.import(
            "Forum Mall\nhttps://www.google.com/maps/place/Forum+Mall/@12.9345,77.6111,17z/data=!8m2!3d12.9344!4d77.6110",
        )
        assertTrue(r is ImportResult.Ok)
        assertCoords(12.9344, 77.6110, (r as ImportResult.Ok).place)
    }

    @Test
    fun importerUsesTheSharedNameWhenTheUrlHasNone() {
        val r = ShareImporter.import("Cubbon Park\nhttps://www.google.com/maps/@12.97,77.59,15z")
        assertTrue(r is ImportResult.Ok)
        assertEquals("Cubbon Park", (r as ImportResult.Ok).place.name)
    }

    @Test
    fun importerRejectsNonGoogleLinksAndEmptyText() {
        assertTrue(ShareImporter.import("https://evil.example.com/maps?q=1,2") is ImportResult.Failed)
        assertTrue(ShareImporter.import("hello") is ImportResult.Failed)
        assertTrue(ShareImporter.import("   ") is ImportResult.Failed)
    }

    @Test
    fun importerReportsNeedsLookupForNameOnlyDirections() {
        val r = ShareImporter.import("https://www.google.com/maps/dir/MG+Road/Koramangala/@12.9,77.6,12z")
        assertTrue(r is ImportResult.NeedsLookup)
        assertEquals("Koramangala", (r as ImportResult.NeedsLookup).name)
    }

    @Test
    fun importerHandlesGeoUris() {
        val r = ShareImporter.import("geo:12.9716,77.5946?q=12.9716,77.5946(Cubbon%20Park)")
        assertTrue(r is ImportResult.Ok)
    }
}
