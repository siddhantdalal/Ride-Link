package com.siddhantdalal.bikelink

import android.app.Activity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView

/**
 * Shows the iPhone's incoming call and what's playing on the cards from ride_call.xml and ride_music.xml, and sends
 * their buttons back to the iPhone. The ride screen has both cards; the dashboard only has the call card.
 *
 * Like CarPlay, one card at a time: a ringing call takes the music card's place, so its buttons can be glove-sized.
 */
class IphoneDock(private val activity: Activity) {

    private val callCard: View = activity.findViewById(R.id.call_card)
    private val callName: TextView = activity.findViewById(R.id.call_name)
    private val musicCard: View? = activity.findViewById(R.id.music_card)
    private val musicTitle: TextView? = activity.findViewById(R.id.music_title)
    private val musicArtist: TextView? = activity.findViewById(R.id.music_artist)
    private val musicPlay: ImageButton? = activity.findViewById(R.id.music_play)

    private val listener: (IphoneLink.State) -> Unit = { render(it) }

    init {
        activity.findViewById<View>(R.id.call_accept).setOnClickListener { IphoneLink.respondToCall(answer = true) }
        activity.findViewById<View>(R.id.call_decline).setOnClickListener { IphoneLink.respondToCall(answer = false) }
        activity.findViewById<View>(R.id.music_previous)?.setOnClickListener { IphoneLink.media(Ams.Command.PREVIOUS_TRACK) }
        musicPlay?.setOnClickListener { IphoneLink.media(Ams.Command.TOGGLE_PLAY_PAUSE) }
        activity.findViewById<View>(R.id.music_next)?.setOnClickListener { IphoneLink.media(Ams.Command.NEXT_TRACK) }
    }

    /** Starts following the iPhone. Call from onResume. */
    fun attach() = IphoneLink.addListener(listener)

    /** Stops following the iPhone. Call from onPause. */
    fun detach() = IphoneLink.removeListener(listener)

    private fun render(state: IphoneLink.State) {
        val call = state.call
        callCard.visibility = if (call == null) View.GONE else View.VISIBLE
        callName.text = call?.caller?.takeIf(String::isNotBlank) ?: activity.getString(R.string.caller_unknown)

        val music = state.music.takeIf { call == null }
        musicCard?.visibility = if (music == null) View.GONE else View.VISIBLE
        if (music != null) {
            musicTitle?.text = music.title
            musicArtist?.text = music.artist
            musicArtist?.visibility = if (music.artist.isBlank()) View.GONE else View.VISIBLE
            musicPlay?.setImageResource(if (music.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        }
    }
}
