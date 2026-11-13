package com.siddhantdalal.bikelink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The ride screen: the next turn in a large card, the map, the trip, and a dock for music and calls.
 *
 * It renders from a [State], so live data plugs straight in: Google navigation will provide the turn and trip, and
 * the Bluetooth link the music and calls. Until then it opens as a clearly labelled preview with sample data, so the
 * design can be tried on the bike.
 */
class RideActivity : Activity() {

    enum class Turn(val icon: Int) {
        STRAIGHT(R.drawable.ic_straight),
        LEFT(R.drawable.ic_turn_left),
        RIGHT(R.drawable.ic_turn_right),
    }

    data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

    data class State(
        val turn: Turn,
        val distanceToTurn: String,
        val road: String,
        val timeLeft: String,
        val distanceLeft: String,
        val arrival: String,
        val nowPlaying: NowPlaying?,
        val caller: String?,
    )

    private lateinit var state: State

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state = previewState().restoredFrom(savedInstanceState)

        findViewById<View>(R.id.end_ride).setOnClickListener { finish() }
        findViewById<View>(R.id.music_play).setOnClickListener {
            state.nowPlaying?.let { update(state.copy(nowPlaying = it.copy(playing = !it.playing))) }
        }
        findViewById<View>(R.id.call_accept).setOnClickListener { update(state.copy(caller = null)) }
        findViewById<View>(R.id.call_decline).setOnClickListener { update(state.copy(caller = null)) }
        render()
    }

    // Turning the phone recreates the screen, so keep what the rider already did: a declined call stays gone, and
    // paused music stays paused.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CALL_HANDLED, state.caller == null)
        state.nowPlaying?.let { outState.putBoolean(KEY_PLAYING, it.playing) }
    }

    private fun State.restoredFrom(saved: Bundle?): State {
        if (saved == null) return this
        return copy(
            caller = caller.takeUnless { saved.getBoolean(KEY_CALL_HANDLED) },
            nowPlaying = nowPlaying?.let { it.copy(playing = saved.getBoolean(KEY_PLAYING, it.playing)) },
        )
    }

    private fun update(newState: State) {
        state = newState
        render()
    }

    private fun render() {
        findViewById<ImageView>(R.id.maneuver_icon).setImageResource(state.turn.icon)
        findViewById<TextView>(R.id.maneuver_distance).text = state.distanceToTurn
        findViewById<TextView>(R.id.maneuver_road).text = state.road
        findViewById<TextView>(R.id.trip_time).text = state.timeLeft
        findViewById<TextView>(R.id.trip_meta).text = getString(R.string.trip_meta, state.distanceLeft, state.arrival)

        // The dock shows one card at a time, like CarPlay: a ringing call takes the music card's place, so its buttons
        // can be large enough for gloves.
        val caller = state.caller
        val music = state.nowPlaying.takeIf { caller == null }
        findViewById<View>(R.id.call_card).visibility = if (caller == null) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.call_name).text = caller
        findViewById<View>(R.id.music_card).visibility = if (music == null) View.GONE else View.VISIBLE
        if (music != null) {
            findViewById<TextView>(R.id.music_title).text = music.title
            findViewById<TextView>(R.id.music_artist).text = music.artist
            findViewById<ImageButton>(R.id.music_play)
                .setImageResource(if (music.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        }
    }

    private fun previewState(): State {
        val arrival = Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(PREVIEW_MINUTES))
        return State(
            turn = Turn.RIGHT,
            distanceToTurn = getString(R.string.preview_distance),
            road = getString(R.string.preview_road),
            timeLeft = getString(R.string.preview_time),
            distanceLeft = getString(R.string.preview_remaining),
            arrival = DateFormat.getTimeFormat(this).format(arrival),
            nowPlaying = NowPlaying(getString(R.string.preview_song), getString(R.string.preview_artist), playing = true),
            caller = getString(R.string.preview_caller),
        )
    }

    companion object {
        private const val PREVIEW_MINUTES = 16L
        private const val KEY_CALL_HANDLED = "call_handled"
        private const val KEY_PLAYING = "playing"

        fun startPreview(context: Context) {
            context.startActivity(Intent(context, RideActivity::class.java))
        }
    }
}
