package com.siddhantdalal.bikelink

import android.content.Context
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import java.util.Date

/*
 * The car screen's cards. Each class fills in one card from the ride or from the iPhone link. Each looks up its views
 * inside its own root, so the same card layout can appear more than once (the trip card is on the Dashboard and on
 * the Map, and the music layout is the Dashboard card and the full Music screen).
 */

/** What to say while the iPhone link isn't ready, or null once it's connected. */
fun linkStatusText(context: Context, status: IphoneLink.Status): String? = when (status) {
    IphoneLink.Status.UNSUPPORTED -> context.getString(R.string.link_unsupported)
    IphoneLink.Status.BLUETOOTH_OFF -> context.getString(R.string.link_bluetooth_off)
    IphoneLink.Status.PERMISSION_NEEDED -> context.getString(R.string.link_permission)
    IphoneLink.Status.WAITING, IphoneLink.Status.CONNECTING -> context.getString(R.string.link_waiting)
    IphoneLink.Status.CONNECTED -> null
}

/** The next turn during a ride, with Google's arrow, or in a free ride, an offer to ride to the last place again. */
class TurnCard(private val root: View, onRideAgain: () -> Unit, onOpenMap: () -> Unit) {

    private val context = root.context
    private val icon: ImageView = root.findViewById(R.id.turn_icon)
    private val distance: TextView = root.findViewById(R.id.turn_distance)
    private val road: TextView = root.findViewById(R.id.turn_road)
    private val rideAgain: Button = root.findViewById(R.id.ride_again)

    init {
        root.setOnClickListener { onOpenMap() }
        rideAgain.setOnClickListener { onRideAgain() }
    }

    fun render(ride: RideController.Ride, last: RideLog.Entry?) {
        if (ride.guiding) renderTurn(ride) else renderFreeRide(ride, last)
    }

    private fun renderTurn(ride: RideController.Ride) {
        val onAccent = context.getColor(R.color.on_accent)
        root.setBackgroundResource(R.drawable.bg_turn_active)
        distance.setTextColor(onAccent)
        road.setTextColor(onAccent)
        val step = ride.turn?.currentStep
        val arrow = step?.maneuverBitmap
        if (arrow != null) icon.setImageBitmap(arrow) else icon.setImageResource(R.drawable.ic_navigation)
        icon.setColorFilter(onAccent)
        distance.text = ride.turn?.distanceToCurrentStepMeters?.let(RideFormat::distance)
            ?: context.getString(R.string.turn_starting)
        road.text = when {
            ride.turn?.navState == NavState.REROUTING -> context.getString(R.string.turn_rerouting)
            step != null -> step.simpleRoadName?.takeIf(String::isNotBlank) ?: step.fullInstructionText
            else -> ride.destination.orEmpty()
        }
        rideAgain.visibility = View.GONE
    }

    private fun renderFreeRide(ride: RideController.Ride, last: RideLog.Entry?) {
        root.setBackgroundResource(R.drawable.bg_card_glass)
        icon.setImageResource(R.drawable.ic_navigation)
        icon.setColorFilter(context.getColor(R.color.accent))
        distance.setTextColor(context.getColor(R.color.text_primary))
        road.setTextColor(context.getColor(R.color.text_secondary))
        distance.text = context.getString(if (ride.routing) R.string.turn_finding else R.string.free_ride)
        road.text = context.getString(R.string.free_ride_hint)
        val offer = last != null && !ride.routing
        rideAgain.visibility = if (offer) View.VISIBLE else View.GONE
        if (last != null) rideAgain.text = context.getString(R.string.ride_again_button, last.destination)
    }
}

/** Time and distance left, the arrival time, and End ride. Only shown during a ride, and only where [visibleHere]. */
class TripCard(private val root: View, onEnd: () -> Unit) {

    private val context = root.context
    private val time: TextView = root.findViewById(R.id.trip_time)
    private val meta: TextView = root.findViewById(R.id.trip_meta)

    init {
        root.findViewById<View>(R.id.end_ride).setOnClickListener { onEnd() }
    }

    fun render(ride: RideController.Ride, visibleHere: Boolean) {
        val trip = ride.trip
        root.visibility = if (visibleHere && trip != null) View.VISIBLE else View.GONE
        if (trip == null) return
        val arrival = Date(System.currentTimeMillis() + trip.seconds * 1_000L)
        time.text = RideFormat.duration(trip.seconds)
        meta.text = context.getString(
            R.string.trip_meta,
            RideFormat.distance(trip.meters),
            DateFormat.getTimeFormat(context).format(arrival),
        )
    }
}

/**
 * What's playing on the iPhone, with previous / play-pause / next. It drives both the compact Dashboard card and the
 * full Music screen, which share view ids. The controls only work while the iPhone is linked.
 */
class MusicCard(private val root: View, onOpen: (() -> Unit)? = null) {

    private val context = root.context
    private val title: TextView = root.findViewById(R.id.music_title)
    private val artist: TextView = root.findViewById(R.id.music_artist)
    private val previous: ImageButton = root.findViewById(R.id.music_previous)
    private val play: ImageButton = root.findViewById(R.id.music_play)
    private val next: ImageButton = root.findViewById(R.id.music_next)

    init {
        previous.setOnClickListener { IphoneLink.media(Ams.Command.PREVIOUS_TRACK) }
        play.setOnClickListener { IphoneLink.media(Ams.Command.TOGGLE_PLAY_PAUSE) }
        next.setOnClickListener { IphoneLink.media(Ams.Command.NEXT_TRACK) }
        onOpen?.let { open -> root.setOnClickListener { open() } }
    }

    fun render(state: IphoneLink.State) {
        val music = state.music
        val linked = state.status == IphoneLink.Status.CONNECTED
        title.text = music?.title
            ?: linkStatusText(context, state.status)
            ?: context.getString(R.string.music_nothing)
        artist.text = music?.artist
        artist.visibility = if (music?.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        play.setImageResource(if (music?.playing == true) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        for (button in listOf(previous, play, next)) {
            button.isEnabled = linked
            button.alpha = if (linked) 1f else DISABLED_ALPHA
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }
}

/** An incoming call, as a compact bar over whichever screen is open, iOS 26 style. */
class CallBar(private val root: View) {

    private val name: TextView = root.findViewById(R.id.call_name)

    init {
        root.findViewById<View>(R.id.call_accept).setOnClickListener { IphoneLink.respondToCall(answer = true) }
        root.findViewById<View>(R.id.call_decline).setOnClickListener { IphoneLink.respondToCall(answer = false) }
    }

    fun render(call: IphoneLink.IncomingCall?) {
        root.visibility = if (call == null) View.GONE else View.VISIBLE
        name.text = call?.caller?.takeIf(String::isNotBlank) ?: root.context.getString(R.string.caller_unknown)
    }
}

/** The Calls screen: whether calls are linked, how calling works from the bike, and a way into setup if needed. */
class CallsPane(private val root: View, onSetup: () -> Unit) {

    private val context = root.context
    private val status: TextView = root.findViewById(R.id.calls_status)
    private val setup: View = root.findViewById(R.id.calls_setup)

    init {
        setup.setOnClickListener { onSetup() }
    }

    fun render(state: IphoneLink.State) {
        val call = state.call
        status.text = when {
            call != null -> context.getString(
                R.string.calls_ringing,
                call.caller.ifBlank { context.getString(R.string.caller_unknown) },
            )
            else -> linkStatusText(context, state.status) ?: context.getString(R.string.calls_ready)
        }
        val canFix = state.status != IphoneLink.Status.CONNECTED && state.status != IphoneLink.Status.UNSUPPORTED
        setup.visibility = if (canFix) View.VISIBLE else View.GONE
    }
}
