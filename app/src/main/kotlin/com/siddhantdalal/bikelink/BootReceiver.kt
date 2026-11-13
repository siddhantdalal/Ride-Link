package com.siddhantdalal.bikelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts listening when the phone boots, and again after the app is updated. Nobody has to open the app first. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> BikeLinkService.start(context)
        }
    }
}
