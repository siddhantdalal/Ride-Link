package com.siddhantdalal.bikelink

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Intents into the Google Maps app. */
object GoogleMaps {

    const val PACKAGE = "com.google.android.apps.maps"

    /**
     * Starts turn-by-turn navigation straight away. `mode=l` is Google Maps' two-wheeler (motorbike/scooter) mode.
     *
     * We tested the alternatives in Phase 0: this link routes differently from car mode (`mode=d`), which shows the
     * mode is applied. The web-style `maps/dir/…&dir_action=navigate` link only opened the place page.
     */
    fun twoWheelerNavigation(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(query)}&mode=l"))
            .setPackage(PACKAGE)

    /** Brings Google Maps to the front as it is, for example showing the navigation already running. */
    fun bringToFront(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)

    fun isInstalled(context: Context): Boolean = bringToFront(context) != null
}
