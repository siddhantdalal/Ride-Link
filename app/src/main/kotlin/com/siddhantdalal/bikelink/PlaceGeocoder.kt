package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.io.IOException

/**
 * Finds a place name or address ("Phoenix Mall", "FC Road, Pune") on the map using Android's built-in geocoder, which
 * is free and needs no API key. It looks near the rider first, so a common name finds the place in your own city
 * rather than one elsewhere.
 */
object PlaceGeocoder {

    /** How far around the rider to look first, in degrees: about 55 km each way. */
    private const val NEARBY_DEGREES = 0.5

    /** Blocks while it looks the place up, so call it off the main thread. */
    fun locate(context: Context, query: String): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        val near = lastKnownLocation(context)
        return near?.let { search(geocoder, query, it) } ?: search(geocoder, query, near = null)
    }

    // The blocking lookup is deprecated on Android 13+ in favour of a callback version, but it's the one that works on
    // every version this app supports, and the caller is already on a background thread.
    @Suppress("DEPRECATION")
    private fun search(geocoder: Geocoder, query: String, near: Location?): Pair<Double, Double>? = try {
        val results = if (near == null) {
            geocoder.getFromLocationName(query, 1)
        } else {
            geocoder.getFromLocationName(
                query, 1,
                (near.latitude - NEARBY_DEGREES).coerceAtLeast(-90.0), near.longitude - NEARBY_DEGREES,
                (near.latitude + NEARBY_DEGREES).coerceAtMost(90.0), near.longitude + NEARBY_DEGREES,
            )
        }
        results?.firstOrNull()?.let { it.latitude to it.longitude }
    } catch (e: IOException) {
        null // offline, or the geocoding service is unavailable
    } catch (e: IllegalArgumentException) {
        null
    }

    @SuppressLint("MissingPermission") // checked just above the call
    private fun lastKnownLocation(context: Context): Location? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val manager = context.getSystemService(LocationManager::class.java)
        return manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }
}
