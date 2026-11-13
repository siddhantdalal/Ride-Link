package com.siddhantdalal.bikelink

import android.content.Context
import android.content.SharedPreferences

/** Remembers the last command from the iPhone, for the "Last ride" card. */
object RideLog {

    /** [destination] is null when the last command was Stop Ride. */
    class Entry(val destination: String?, val at: Long)

    private const val PREFS = "ride_log"
    private const val KEY_DESTINATION = "destination"
    private const val KEY_AT = "at"

    /** The screen listens to these preferences, so it updates the moment a command arrives. */
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun navigatingTo(context: Context, destination: String) = record(context, destination)

    fun stopped(context: Context) = record(context, destination = null)

    fun last(context: Context): Entry? {
        val prefs = prefs(context)
        val at = prefs.getLong(KEY_AT, 0L)
        return if (at == 0L) null else Entry(prefs.getString(KEY_DESTINATION, null), at)
    }

    private fun record(context: Context, destination: String?) {
        prefs(context).edit()
            .putString(KEY_DESTINATION, destination)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }
}
