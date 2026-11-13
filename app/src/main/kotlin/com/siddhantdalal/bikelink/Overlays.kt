package com.siddhantdalal.bikelink

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What Bike Link shows over Google Maps: the incoming-call capsule, and the optional now-playing bar.
 *
 * Both are overlay windows ("Display over other apps"), added from the background service, so they appear over
 * whichever app is in front. They never take keyboard focus. The service passes in every change of the iPhone link
 * ([render]), and calls [refresh] when the screen rotates or switches between light and dark, so the windows are
 * rebuilt with the right shape and colours. Call everything on the main thread.
 */
class Overlays(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private var state: IphoneLink.State? = null

    private var callWindow: View? = null
    private var shownCallUid: Int? = null
    /** A call whose capsule was taken down by us (answered, declined or timed out), so it isn't shown again. */
    private var dismissedCallUid: Int? = null
    private val dismissCall = Runnable { dismissedCallUid = shownCallUid; removeCall() }

    private var musicWindow: View? = null

    private val settingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_MUSIC_BAR) state?.let(::render)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(settingListener)
    }

    fun render(newState: IphoneLink.State) {
        state = newState
        if (!Settings.canDrawOverlays(context)) {
            removeCall()
            removeMusic()
            return
        }
        // The iPhone numbers notifications afresh after reconnecting, so forget a dismissed call once it's over.
        if (newState.call == null) dismissedCallUid = null
        val call = newState.call?.takeIf { it.uid != dismissedCallUid }
        if (call != null) showCall(call) else removeCall()
        val linked = newState.status == IphoneLink.Status.CONNECTED
        // The music bar steps aside while a call rings: iOS pauses the music anyway.
        val music = newState.music?.takeIf { linked && newState.call == null && isMusicBarOn(context) }
        if (music != null) showMusic(music) else removeMusic()
    }

    /** Rebuilds the windows, for a new screen shape or theme. */
    fun refresh() {
        removeCall()
        removeMusic()
        state?.let(::render)
    }

    fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(settingListener)
        removeCall()
        removeMusic()
    }

    // --- The incoming-call capsule ---

    private fun showCall(call: IphoneLink.IncomingCall) {
        if (callWindow != null && shownCallUid == call.uid) {
            callWindow?.findViewById<TextView>(R.id.call_name)?.text = callerName(call)
            return
        }
        removeCall()
        val view = inflate(R.layout.overlay_call)
        view.findViewById<TextView>(R.id.call_name).apply {
            text = callerName(call)
            maxWidth = (screenWidth() * CAPSULE_LENGTH).roundToInt()
        }
        view.findViewById<SwipeCallView>(R.id.call_swipe).apply {
            layoutParams = capsuleSize()
            onResult = { answer ->
                IphoneLink.respondToCall(answer)
                // The iPhone normally ends the ringing right away, which removes the capsule. If it doesn't
                // (the link dropped, say), don't leave the capsule over the map.
                handler.removeCallbacks(dismissCall)
                handler.postDelayed(dismissCall, AFTER_RESPONSE_MS)
            }
        }
        // Full screen, so the map behind dims. Touches on it don't reach Google Maps while the call rings.
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!add(view, params)) return
        callWindow = view
        shownCallUid = call.uid
        // iOS stops ringing after about half a minute. Never leave the map covered for long if the end is missed.
        handler.postDelayed(dismissCall, MAX_RING_MS)
    }

    private fun removeCall() {
        handler.removeCallbacks(dismissCall)
        remove(callWindow)
        callWindow = null
        shownCallUid = null
    }

    private fun callerName(call: IphoneLink.IncomingCall) =
        call.caller.ifBlank { context.getString(R.string.caller_unknown) }

    /** 70% of the screen's long side, laid along it: across the screen in landscape, up and down in portrait. */
    private fun capsuleSize(): LinearLayout.LayoutParams {
        val metrics = context.resources.displayMetrics
        val thickness = dp(CAPSULE_THICKNESS_DP)
        val length = (max(metrics.widthPixels, metrics.heightPixels) * CAPSULE_LENGTH).roundToInt()
        return if (isLandscape()) {
            LinearLayout.LayoutParams(min(length, metrics.widthPixels - dp(EDGE_MARGIN_DP * 2)), thickness)
        } else {
            // Leave room above it for the caller's name.
            LinearLayout.LayoutParams(thickness, min(length, metrics.heightPixels - dp(PORTRAIT_RESERVED_DP)))
        }.apply { topMargin = dp(CARD_GAP_DP) }
    }

    // --- The now-playing bar ---

    private fun showMusic(music: IphoneLink.NowPlaying) {
        val view = musicWindow ?: createMusicBar() ?: return
        view.findViewById<TextView>(R.id.music_title).text = music.title
        view.findViewById<TextView>(R.id.music_artist).apply {
            text = music.artist
            visibility = if (music.artist.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageButton>(R.id.music_play)
            .setImageResource(if (music.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    private fun createMusicBar(): View? {
        val view = inflate(R.layout.overlay_music)
        view.findViewById<View>(R.id.music_play).setOnClickListener { IphoneLink.media(Ams.Command.TOGGLE_PLAY_PAUSE) }
        view.findViewById<View>(R.id.music_next).setOnClickListener { IphoneLink.media(Ams.Command.NEXT_TRACK) }

        val width = min(dp(MUSIC_WIDTH_DP), screenWidth() - dp(EDGE_MARGIN_DP * 2))
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.TOP or Gravity.START
        // Measure first, so the bar can start in its corner, and a saved position can be kept on screen.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val (x, y) = savedMusicPosition() ?: defaultMusicPosition(width, view.measuredHeight)
        params.x = x
        params.y = y
        keepOnScreen(params, width, view.measuredHeight)
        view.setOnTouchListener(DragToMove(params))
        if (!add(view, params)) return null
        musicWindow = view
        return view
    }

    private fun removeMusic() {
        remove(musicWindow)
        musicWindow = null
    }

    /**
     * Where the bar starts, in the space Google Maps' navigation screen leaves free. In landscape that's along the top,
     * between the next-turn card on the left and Maps' column of buttons on the right. In portrait the turn banner
     * fills the top, so the bar sits low and centred, above Maps' trip panel.
     */
    private fun defaultMusicPosition(width: Int, height: Int): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return if (isLandscape()) {
            metrics.widthPixels - width - dp(MAPS_BUTTONS_DP) to dp(EDGE_MARGIN_DP)
        } else {
            (metrics.widthPixels - width) / 2 to metrics.heightPixels - height - dp(MAPS_TRIP_PANEL_DP)
        }
    }

    private fun keepOnScreen(params: WindowManager.LayoutParams, width: Int, height: Int) {
        val metrics = context.resources.displayMetrics
        params.x = params.x.coerceIn(0, max(0, metrics.widthPixels - width))
        params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - height))
    }

    /** Positions are kept per orientation, since the bar usually belongs somewhere different in each. */
    private fun positionKeys() = if (isLandscape()) KEY_MUSIC_LANDSCAPE else KEY_MUSIC_PORTRAIT

    private fun savedMusicPosition(): Pair<Int, Int>? {
        val (xKey, yKey) = positionKeys()
        if (!prefs.contains(xKey) || !prefs.contains(yKey)) return null
        return prefs.getInt(xKey, 0) to prefs.getInt(yKey, 0)
    }

    private fun saveMusicPosition(x: Int, y: Int) {
        val (xKey, yKey) = positionKeys()
        prefs.edit().putInt(xKey, x).putInt(yKey, y).apply()
    }

    /**
     * Drag the bar (anywhere but its buttons) to move it. A touch that doesn't move past the touch slop isn't a drag.
     * There's nothing to click on the bar itself, so it has no click action for accessibility to perform.
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DragToMove(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        keepOnScreen(params, view.width, view.height)
                        update(view, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) saveMusicPosition(params.x, params.y)
            }
            return true
        }
    }

    // --- Windows ---

    /** Inflates with Bike Link's theme, so the views pick up the current light or dark colours. */
    @SuppressLint("InflateParams") // an overlay window has no parent to inflate into
    private fun inflate(layout: Int): View =
        LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_BikeLink)).inflate(layout, null)

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        windowAnimations = android.R.style.Animation_Toast // a short fade in and out
    }

    /** Adds a window. It can still fail if the overlay permission was withdrawn a moment ago. */
    private fun add(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (e: WindowManager.BadTokenException) {
        Log.w(TAG, "Couldn't show an overlay", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Couldn't show an overlay", e)
        false
    }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
    }

    private fun remove(view: View?) {
        // The system already removes overlays when the permission is withdrawn.
        if (view?.isAttachedToWindow == true) windowManager.removeView(view)
    }

    private fun isLandscape() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun screenWidth() = context.resources.displayMetrics.widthPixels

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "Overlays"
        private const val PREFS = "overlays"
        private const val KEY_MUSIC_BAR = "music_bar"
        private val KEY_MUSIC_PORTRAIT = "music_x_portrait" to "music_y_portrait"
        private val KEY_MUSIC_LANDSCAPE = "music_x_landscape" to "music_y_landscape"

        private const val CAPSULE_LENGTH = 0.7f
        private const val CAPSULE_THICKNESS_DP = 104
        private const val CARD_GAP_DP = 16
        private const val PORTRAIT_RESERVED_DP = 200
        private const val EDGE_MARGIN_DP = 16
        private const val MUSIC_WIDTH_DP = 300
        private const val MAPS_BUTTONS_DP = 88
        private const val MAPS_TRIP_PANEL_DP = 112
        private const val MAX_RING_MS = 60_000L
        private const val AFTER_RESPONSE_MS = 2_500L

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Whether the now-playing bar shows over Google Maps. On unless the rider turns it off. */
        fun isMusicBarOn(context: Context): Boolean = prefs(context).getBoolean(KEY_MUSIC_BAR, true)

        fun setMusicBarOn(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean(KEY_MUSIC_BAR, on).apply()
        }
    }
}
