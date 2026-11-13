package com.siddhantdalal.bikelink

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The optional "Maps helper": taps Google Maps buttons that the rider would otherwise have to tap.
 *
 * Google Maps has no API for confirming a route change or ending navigation, so this accessibility service presses
 * the buttons instead. It only does so for a few seconds after a command from the iPhone asks for it, and ignores
 * everything else. It only receives events from Google Maps (see res/xml/maps_helper.xml).
 *
 * It finds buttons by their English labels, as seen in testing. If Maps renames them or the phone uses another
 * language, nothing gets tapped and the rider taps once instead.
 */
class MapsHelperService : AccessibilityService() {

    enum class Task { CONFIRM_NEW_ROUTE, STOP_NAVIGATION }

    private class Request(val task: Task, val expiresAt: Long) {
        var lastTapAt = 0L
    }

    override fun onServiceConnected() {
        instance = this
        // Android itself starts accessibility services at boot and after app updates, even on phones whose makers
        // block apps from starting themselves (OnePlus refused our boot/update broadcast on device). So when the
        // helper is on, it also starts the command listener.
        BikeLinkService.start(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = actOnPendingRequest()

    private fun actOnPendingRequest() {
        val request = pending ?: return
        if (SystemClock.elapsedRealtime() > request.expiresAt) {
            pending = null
            return
        }
        val root = rootInActiveWindow ?: return

        // Maps asks this when a new destination arrives mid-ride, and may ask it when navigation is closed.
        if (root.findNode { it.hasLabel(EXIT_NAVIGATION_PROMPT) } != null) {
            val tapped = root.findNode { it.hasLabel(YES) }?.click() == true
            Log.d(TAG, "Exit navigation prompt: tapped Yes = $tapped")
            if (tapped) pending = null
            return
        }
        if (request.task == Task.STOP_NAVIGATION) {
            val close = root.findNode { it.hasLabel(CLOSE_NAVIGATION) }
            if (close == null) {
                // Its button is gone, so navigation has ended. (Before the first tap, Maps may still be coming
                // to the front, so keep waiting until the request expires.)
                if (request.lastTapAt != 0L) pending = null
                return
            }
            // Tap again if Maps is still navigating a moment later: a tap can land while Maps is mid-transition
            // and be ignored (seen on device).
            val now = SystemClock.elapsedRealtime()
            if (now - request.lastTapAt >= RETAP_INTERVAL_MS) {
                request.lastTapAt = now
                Log.d(TAG, "Stop ride: tapped Close navigation = ${close.click()}")
            }
        }
    }

    companion object {
        // The labels Google Maps 26.40 shows in English.
        private const val EXIT_NAVIGATION_PROMPT = "Exit navigation?"
        private const val YES = "Yes"
        private const val CLOSE_NAVIGATION = "Close navigation"
        private const val REQUEST_LIFETIME_MS = 10_000L
        private const val RETAP_INTERVAL_MS = 1_000L
        private const val TAG = "MapsHelper"

        @Volatile private var instance: MapsHelperService? = null
        @Volatile private var pending: Request? = null
        private val mainThread = Handler(Looper.getMainLooper())

        /** True while the user has the helper turned on in Accessibility settings. */
        val isEnabled: Boolean get() = instance != null

        fun request(task: Task) {
            if (!isEnabled) return
            pending = Request(task, SystemClock.elapsedRealtime() + REQUEST_LIFETIME_MS)
            // Check right away too: the button may already be on screen, and then no new event would come for it.
            mainThread.post { instance?.actOnPendingRequest() }
        }
    }
}

private fun AccessibilityNodeInfo.hasLabel(label: String): Boolean =
    text?.toString().equals(label, ignoreCase = true) ||
        contentDescription?.toString().equals(label, ignoreCase = true)

private fun AccessibilityNodeInfo.findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (predicate(this)) return this
    for (i in 0 until childCount) {
        getChild(i)?.findNode(predicate)?.let { return it }
    }
    return null
}

/** Clicks this node or its nearest clickable ancestor. A label often sits inside the view that's actually clickable. */
private fun AccessibilityNodeInfo.click(): Boolean {
    var node: AccessibilityNodeInfo? = this
    while (node != null) {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node = node.parent
    }
    return false
}
