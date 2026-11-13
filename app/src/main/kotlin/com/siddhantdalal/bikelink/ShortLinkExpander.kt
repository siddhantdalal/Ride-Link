package com.siddhantdalal.bikelink

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/** Follows a short map link's redirects, without downloading any page, to the full link that names the place. */
object ShortLinkExpander {

    private const val MAX_REDIRECTS = 5
    private const val TIMEOUT_MS = 6_000

    /** Returns the first redirect target that isn't itself a short link, or null if offline or the link is dead. */
    fun expand(shortUrl: String): String? {
        var url = parse(null, shortUrl) ?: return null
        repeat(MAX_REDIRECTS) {
            val location = redirectTarget(url) ?: return null
            url = parse(url, location) ?: return null
            if (!DestinationParser.isShortLink(url.toString())) return url.toString()
        }
        return null
    }

    private fun parse(base: URL?, spec: String): URL? = try {
        URL(base, spec).takeIf { it.protocol == "https" || it.protocol == "http" }
    } catch (e: MalformedURLException) {
        null
    }

    private fun redirectTarget(url: URL): String? {
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode in 300..399) connection.getHeaderField("Location") else null
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
