package com.siddhantdalal.bikelink

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Opens Google Maps for a command that arrived in the background.
 *
 * Going through this invisible activity means the screen gets woken and a swipe-only lock screen dismissed first.
 * With a PIN lock, Android asks the rider to unlock. Launching it from the background is allowed because the user
 * granted "Display over other apps", or because the Maps helper is turned on.
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
        val maps = intent.getStringExtra(EXTRA_QUERY)?.let(GoogleMaps::twoWheelerNavigation)
            ?: GoogleMaps.bringToFront(this)
        try {
            if (maps != null) startActivity(maps)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Google Maps couldn't be opened", e)
        }
        finish()
    }

    companion object {
        private const val TAG = "LaunchMapsActivity"
        private const val EXTRA_QUERY = "query"

        fun navigate(context: Context, query: String) = start(context, query)

        fun showMaps(context: Context) = start(context, query = null)

        private fun start(context: Context, query: String?) {
            val intent = Intent(context, LaunchMapsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (query != null) intent.putExtra(EXTRA_QUERY, query)
            context.startActivity(intent)
        }
    }
}
