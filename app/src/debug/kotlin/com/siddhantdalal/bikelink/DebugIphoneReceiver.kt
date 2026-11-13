package com.siddhantdalal.bikelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: plays the iPhone's part, so the call capsule and the music bar can be tested without one.
 *
 *     R=com.siddhantdalal.bikelink/.DebugIphoneReceiver
 *     adb shell am broadcast -n $R --es call "Mom"                       # a call rings
 *     adb shell am broadcast -n $R --ez end_call true                    # it stops ringing
 *     adb shell am broadcast -n $R --es song "Title" --es artist "Artist" [--ez playing false]
 *     adb shell am broadcast -n $R --ez stop_music true
 *     adb shell am broadcast -n $R --ez reset true                       # back to "waiting for your iPhone"
 *
 * Each broadcast changes only what it names, and marks the iPhone as linked.
 */
class DebugIphoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("reset", false)) {
            return IphoneLink.injectForTesting(IphoneLink.State(IphoneLink.Status.WAITING))
        }
        var state = IphoneLink.state.copy(status = IphoneLink.Status.CONNECTED)
        intent.getStringExtra("call")?.let { caller ->
            // A new uid for each call, as the iPhone gives each notification its own.
            state = state.copy(call = IphoneLink.IncomingCall(uid = nextUid++, caller = caller))
        }
        if (intent.getBooleanExtra("end_call", false)) state = state.copy(call = null)
        intent.getStringExtra("song")?.let { title ->
            val artist = intent.getStringExtra("artist").orEmpty()
            state = state.copy(music = IphoneLink.NowPlaying(title, artist, intent.getBooleanExtra("playing", true)))
        }
        if (intent.getBooleanExtra("stop_music", false)) state = state.copy(music = null)
        IphoneLink.injectForTesting(state)
    }

    private companion object {
        var nextUid = 1_000
    }
}
