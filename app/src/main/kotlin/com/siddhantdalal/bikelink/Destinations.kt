package com.siddhantdalal.bikelink

import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * Where to ride. [query] is either coordinates ("18.5622,73.9167") or a place name to look up, and [label] is the
 * name we tell the rider.
 */
data class Destination(val query: String, val label: String) {

    /** Latitude and longitude when [query] holds coordinates, or null when it's a place name to look up. */
    fun coordinates(): Pair<Double, Double>? {
        val parts = query.split(',')
        if (parts.size != 2) return null
        val latitude = parts[0].trim().toDoubleOrNull() ?: return null
        val longitude = parts[1].trim().toDoubleOrNull() ?: return null
        return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) latitude to longitude else null
    }
}

/** The result of reading what the iPhone sent, before any online lookups. */
sealed interface ParseResult

/** The final result, after any short link has been expanded. */
sealed interface Resolution

data class Found(val destination: Destination) : ParseResult, Resolution

data class Unusable(val reason: String) : ParseResult, Resolution

/** A short link (maps.app.goo.gl, maps.apple/p/…) that has to be expanded online to find the place. */
data class ShortLink(val url: String, val fallbackName: String?) : ParseResult

/**
 * Understands everything the iPhone can send as a destination:
 *  - a place name or address, for example one dictated to Siri ("Phoenix Mall")
 *  - coordinates ("18.5622,73.9167")
 *  - a Google Maps link, shared from Google Maps or from a WhatsApp location
 *  - an Apple Maps link, shared from Apple Maps
 *  - shared text that contains one of those links ("Phoenix Marketcity\nhttps://maps.app.goo.gl/…")
 *
 * When a link includes coordinates we use them, because they route to the exact pin. A name can match a different
 * place with the same name.
 */
object DestinationParser {

    private val URL_IN_TEXT = Regex("""https?://\S+""")
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '>', '"', '\'')
    private val COORDINATES = Regex("""(?:loc:\s*)?(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)(?:\s*\(.*\))?""")
    private val SHORT_LINK_HOSTS = setOf("maps.app.goo.gl", "maps.apple")
    private val GOOGLE_DOMAIN = Regex("""google\.[a-z]{2,3}(?:\.[a-z]{2})?""")

    // In a Google Maps place link, "!3d<lat>!4d<lng>" is the place's own pin: the most precise location in it.
    private val GOOGLE_PIN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")

    // "@<lat>,<lng>,<zoom>z" is where the map was centred when the link was shared, usually on the place itself.
    private val GOOGLE_VIEWPORT = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val GOOGLE_PLACE_NAME = Regex("""/maps/place/([^/@]+)""")

    /** Parses [input], expanding a short link with [expandShortLink] if needed (that function goes online). */
    fun resolve(input: String, expandShortLink: (String) -> String?): Resolution =
        when (val parsed = parse(input)) {
            is Found -> parsed
            is Unusable -> parsed
            is ShortLink -> {
                val expanded = expandShortLink(parsed.url)?.let { parseUrl(it, parsed.fallbackName) }
                when {
                    expanded is Found -> expanded
                    parsed.fallbackName != null -> named(parsed.fallbackName)
                    else -> Unusable("Couldn't open that map link. Check that the bike phone has internet.")
                }
            }
        }

    fun parse(input: String): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) return Unusable("No destination was sent.")
        coordinates(text)?.let { return Found(Destination(it, it)) }

        val url = URL_IN_TEXT.find(text)?.value?.trimEnd(*TRAILING_PUNCTUATION)
            ?: return named(text.lines().map(String::trim).filter(String::isNotEmpty).joinToString(", "))
        val name = text.lines().map { it.replace(url, "").trim() }.firstOrNull(String::isNotEmpty)
        return parseUrl(url, name)
    }

    fun parseUrl(url: String, fallbackName: String?): ParseResult {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return fallback(fallbackName)
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return fallback(fallbackName)
        val path = uri.rawPath.orEmpty()
        return when {
            isShortLink(host, path) -> ShortLink(url, fallbackName)
            host == "maps.apple.com" -> fromAppleMaps(queryParameters(uri.rawQuery), fallbackName)
            isGoogleMaps(host, path) -> fromGoogleMaps(url, path, queryParameters(uri.rawQuery), fallbackName)
            else -> fallback(fallbackName)
        }
    }

    fun isShortLink(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        return isShortLink(host, uri.rawPath.orEmpty())
    }

    private fun isShortLink(host: String, path: String) =
        host in SHORT_LINK_HOSTS || (host == "goo.gl" && path.startsWith("/maps"))

    private fun isGoogleMaps(host: String, path: String): Boolean {
        val isMapsSubdomain = host.startsWith("maps.")
        return GOOGLE_DOMAIN.matches(host.removePrefix("maps.")) && (isMapsSubdomain || path.startsWith("/maps"))
    }

    private fun fromGoogleMaps(
        url: String,
        path: String,
        params: Map<String, String>,
        fallbackName: String?,
    ): ParseResult {
        val name = GOOGLE_PLACE_NAME.find(path)?.groupValues?.get(1)?.let(::decode)?.takeIf(String::isNotBlank)
            ?: fallbackName
        GOOGLE_PIN.find(url)?.let { return pinned(it.groupValues[1], it.groupValues[2], name) }
        for (key in listOf("destination", "daddr", "q", "query")) {
            val value = params[key]?.takeIf(String::isNotBlank) ?: continue
            return coordinates(value)?.let { Found(Destination(it, name ?: it)) } ?: named(value)
        }
        GOOGLE_VIEWPORT.find(path)?.let { return pinned(it.groupValues[1], it.groupValues[2], name) }
        return fallback(name)
    }

    private fun fromAppleMaps(params: Map<String, String>, fallbackName: String?): ParseResult {
        // In Apple Maps links, "q" is the place's name, unless it holds coordinates.
        val name = params["name"] ?: params["q"]?.takeIf { coordinates(it) == null } ?: fallbackName
        for (key in listOf("coordinate", "ll", "daddr", "sll", "q")) {
            val coordinates = params[key]?.let(::coordinates) ?: continue
            return Found(Destination(coordinates, name ?: coordinates))
        }
        val address = params["daddr"] ?: params["address"]
        return if (address != null) Found(Destination(address, name ?: address)) else fallback(name)
    }

    private fun pinned(lat: String, lng: String, name: String?): ParseResult {
        val coordinates = coordinates("$lat,$lng") ?: return fallback(name)
        return Found(Destination(coordinates, name ?: coordinates))
    }

    /** Normalises "18.5622,73.9167", "loc:18.5622,73.9167" or "18.5622, 73.9167 (Home)" to "18.5622,73.9167". */
    private fun coordinates(text: String): String? {
        val (lat, lng) = COORDINATES.matchEntire(text.trim())?.destructured ?: return null
        if (lat.toDouble() !in -90.0..90.0 || lng.toDouble() !in -180.0..180.0) return null
        return "$lat,$lng"
    }

    private fun named(name: String) = Found(Destination(name, name))

    private fun fallback(name: String?): ParseResult =
        name?.let(::named) ?: Unusable("That link isn't a Google Maps or Apple Maps place.")

    private fun queryParameters(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty()
            .split('&')
            .filter { it.substringBefore('=').isNotEmpty() }
            .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
            .distinctBy { it.first }
            .toMap()

    // The Charset overload of URLDecoder.decode only exists from Android 13, so use the String one.
    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        value // a malformed %-escape: better to keep the raw text than to fail
    } catch (e: UnsupportedEncodingException) {
        value
    }
}
