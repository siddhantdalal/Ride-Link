package com.siddhantdalal.bikelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationParserTest {

    private fun found(input: String) = (DestinationParser.parse(input) as Found).destination

    @Test
    fun `plain place name is searched as-is`() {
        assertEquals(Destination("Phoenix Mall", "Phoenix Mall"), found("  Phoenix Mall "))
    }

    @Test
    fun `multi-line address is joined into one line`() {
        assertEquals("12 MG Road, Pune", found("12 MG Road\n\nPune").query)
    }

    @Test
    fun `coordinates are normalised`() {
        assertEquals("18.5622,73.9167", found("18.5622, 73.9167").query)
        assertEquals("18.5622,73.9167", found("loc:18.5622,73.9167").query)
        assertEquals("-33.85,151.2", found("-33.85,151.2 (Opera House)").query)
    }

    @Test
    fun `out-of-range numbers are searched as text, not used as coordinates`() {
        // Latitude can't be 95, so this must not be routed to as a location. It's searched like any other text.
        assertEquals(Destination("95.1,73.2", "95.1,73.2"), found("95.1,73.2"))
    }

    @Test
    fun `google place link uses the exact pin and the place name`() {
        val url = "https://www.google.com/maps/place/Phoenix+Marketcity/@18.5600,73.9100,17z/" +
            "data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d18.5622!4d73.9167!16s%2Fg%2F1tf"
        assertEquals(Destination("18.5622,73.9167", "Phoenix Marketcity"), found(url))
    }

    @Test
    fun `google place link without pin falls back to the map centre`() {
        val url = "https://www.google.com/maps/place/Shaniwar+Wada/@18.5195,73.8553,17z"
        assertEquals(Destination("18.5195,73.8553", "Shaniwar Wada"), found(url))
    }

    @Test
    fun `whatsapp location link`() {
        assertEquals("18.52043,73.85674", found("https://maps.google.com/?q=18.52043,73.85674").query)
    }

    @Test
    fun `google search and directions links use their query`() {
        assertEquals("Pune Railway Station", found("https://www.google.com/maps/search/?api=1&query=Pune+Railway+Station").query)
        assertEquals("India Gate", found("https://www.google.co.in/maps/dir/?api=1&destination=India%20Gate").query)
    }

    @Test
    fun `apple maps link with coordinates and name`() {
        val url = "https://maps.apple.com/?ll=18.5622,73.9167&q=Phoenix%20Marketcity&t=m"
        assertEquals(Destination("18.5622,73.9167", "Phoenix Marketcity"), found(url))
    }

    @Test
    fun `newer apple maps place link`() {
        val url = "https://maps.apple.com/place?address=Viman+Nagar,+Pune&coordinate=18.5622,73.9167&name=Phoenix+Marketcity"
        assertEquals(Destination("18.5622,73.9167", "Phoenix Marketcity"), found(url))
    }

    @Test
    fun `apple maps link with only an address`() {
        assertEquals("Viman Nagar, Pune", found("https://maps.apple.com/?daddr=Viman+Nagar,+Pune").query)
    }

    @Test
    fun `shared text keeps the name and finds the link`() {
        val parsed = DestinationParser.parse("Phoenix Marketcity\nhttps://maps.app.goo.gl/AbC123.")
        assertEquals(ShortLink("https://maps.app.goo.gl/AbC123", "Phoenix Marketcity"), parsed)
    }

    @Test
    fun `short link is expanded and then parsed`() {
        val expanded = "https://www.google.com/maps/place/Phoenix+Marketcity/data=!3d18.5622!4d73.9167"
        val result = DestinationParser.resolve("https://maps.app.goo.gl/AbC123") { expanded }
        assertEquals(Found(Destination("18.5622,73.9167", "Phoenix Marketcity")), result)
    }

    @Test
    fun `short link that cannot be expanded falls back to the shared name`() {
        val result = DestinationParser.resolve("Phoenix Marketcity https://maps.app.goo.gl/AbC123") { null }
        assertEquals(Found(Destination("Phoenix Marketcity", "Phoenix Marketcity")), result)
    }

    @Test
    fun `short link that cannot be expanded and has no name is unusable`() {
        assertTrue(DestinationParser.resolve("https://maps.app.goo.gl/AbC123") { null } is Unusable)
    }

    @Test
    fun `unrelated link without a name is unusable`() {
        assertTrue(DestinationParser.parse("https://example.com/page") is Unusable)
    }

    @Test
    fun `empty input is unusable`() {
        assertTrue(DestinationParser.parse("   ") is Unusable)
    }

    @Test
    fun `recognises short links`() {
        assertTrue(DestinationParser.isShortLink("https://maps.app.goo.gl/x"))
        assertTrue(DestinationParser.isShortLink("https://goo.gl/maps/x"))
        assertTrue(DestinationParser.isShortLink("https://maps.apple/p/x"))
        assertTrue(!DestinationParser.isShortLink("https://www.google.com/maps/place/x"))
    }
}
