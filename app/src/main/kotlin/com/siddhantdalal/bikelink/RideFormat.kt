package com.siddhantdalal.bikelink

import java.util.Locale

/** Trip numbers as the ride screen shows them: "16 min", "1 h 05 min", "800 m", "6.2 km". */
object RideFormat {

    fun duration(seconds: Int): String {
        // Round up: with 30 seconds to go, "1 min" is more honest than "0 min".
        val minutes = (seconds.coerceAtLeast(0) + 59) / 60
        return if (minutes < 60) {
            "$minutes min"
        } else {
            String.format(Locale.getDefault(), "%d h %02d min", minutes / 60, minutes % 60)
        }
    }

    fun distance(meters: Int): String {
        val m = meters.coerceAtLeast(0)
        return when {
            m < 1_000 -> "${(m + 5) / 10 * 10} m"
            m < 10_000 -> String.format(Locale.getDefault(), "%.1f km", m / 1_000.0)
            else -> "${(m + 500) / 1_000} km"
        }
    }
}
