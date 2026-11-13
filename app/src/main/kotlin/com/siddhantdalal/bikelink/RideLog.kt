package com.siddhantdalal.bikelink

import android.content.Context
import android.content.SharedPreferences

/** Remembers the last ride: where to, when, and whether it has ended. It's used for the dashboard's Navigate tile. */
object RideLog {

    class Entry(
        val destination: String,
        val latitude: Double,
        val longitude: Double,
        val at: Long,
        val ended: Boolean,
    )

    private const val PREFS = "ride_log"
    private const val KEY_DESTINATION = "destination"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_AT = "at"
    private const val KEY_ENDED = "ended"

    /** The dashboard listens to these preferences, so it updates the moment a ride starts or ends. */
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun started(context: Context, destination: String, latitude: Double, longitude: Double) {
        prefs(context).edit()
            .putString(KEY_DESTINATION, destination)
            // SharedPreferences has no Double, and a Float would lose metres of precision.
            .putString(KEY_LATITUDE, latitude.toString())
            .putString(KEY_LONGITUDE, longitude.toString())
            .putLong(KEY_AT, System.currentTimeMillis())
            .putBoolean(KEY_ENDED, false)
            .apply()
    }

    fun ended(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENDED, true).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun last(context: Context): Entry? {
        val prefs = prefs(context)
        val destination = prefs.getString(KEY_DESTINATION, null) ?: return null
        val latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return Entry(destination, latitude, longitude, prefs.getLong(KEY_AT, 0L), prefs.getBoolean(KEY_ENDED, false))
    }
}
