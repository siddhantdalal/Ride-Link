package com.siddhantdalal.bikelink

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

/**
 * Opens Google Maps for a command that arrived in the background.
 *
 * When the screen is on and unlocked, Maps is opened directly. Otherwise this invisible activity runs first, to wake
 * the screen and dismiss a swipe-only lock screen. With a PIN lock, Android asks the rider to unlock. Opening apps
 * from the background is allowed because the user granted "Display over other apps", or because the Maps helper is on.
 */
class LaunchMapsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = openMapsAndFinish()
                override fun onDismissCancelled() = openMapsAndFinish()
                override fun onDismissError() = openMapsAndFinish()
            })
        } else {
            openMapsAndFinish()
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun openMapsAndFinish() {
        openMaps(this, intent.getStringExtra(EXTRA_QUERY))
        finish()
    }

    companion object {
        private const val TAG = "LaunchMapsActivity"
        private const val EXTRA_QUERY = "query"

        fun navigate(context: Context, query: String) = start(context, query)

        fun showMaps(context: Context) = start(context, query = null)

        private fun start(context: Context, query: String?) {
            if (isAwakeAndUnlocked(context)) {
                // Open Maps directly. Any activity shown over a navigating Maps, even this invisible one, pushes Maps
                // into picture-in-picture, and the new route then lands in that small window (seen on device).
                openMaps(context, query)
            } else {
                val intent = Intent(context, LaunchMapsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (query != null) intent.putExtra(EXTRA_QUERY, query)
                context.startActivity(intent)
            }
        }

        /** Opens Maps in its own task, so an existing Maps (already navigating) gets the new route. */
        private fun openMaps(context: Context, query: String?) {
            val maps = query?.let(GoogleMaps::twoWheelerNavigation) ?: GoogleMaps.bringToFront(context) ?: return
            try {
                context.startActivity(maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Google Maps couldn't be opened", e)
            }
        }

        private fun isAwakeAndUnlocked(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }
}
