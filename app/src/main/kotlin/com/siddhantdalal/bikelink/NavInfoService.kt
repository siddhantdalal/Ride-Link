package com.siddhantdalal.bikelink

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo

/**
 * Receives Google's turn-by-turn feed for the Dashboard's next-turn card: the next manoeuvre, its distance and road,
 * and Google's own arrow image for it. The Navigation SDK binds to this service while guidance runs, after
 * [RideController] registers it.
 */
class NavInfoService : Service() {

    private val turnByTurn = TurnByTurnManager.createInstance()

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == TurnByTurnManager.MSG_NAV_INFO) {
            NavInfoFeed.publish(turnByTurn.readNavInfoFromBundle(message.data))
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

/** The latest turn-by-turn update, for whichever screen shows it. Main thread only. */
object NavInfoFeed {

    var latest: NavInfo? = null
        private set

    private val listeners = mutableSetOf<(NavInfo?) -> Unit>()

    /** Adds a listener, and calls it right away with the latest update. */
    fun addListener(listener: (NavInfo?) -> Unit) {
        listeners += listener
        listener(latest)
    }

    fun removeListener(listener: (NavInfo?) -> Unit) {
        listeners -= listener
    }

    fun publish(info: NavInfo?) {
        latest = info
        listeners.toList().forEach { it(info) }
    }
}
