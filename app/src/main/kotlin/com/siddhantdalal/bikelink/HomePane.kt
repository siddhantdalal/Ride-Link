package com.siddhantdalal.bikelink

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * The Home screen, like CarPlay's: a speedometer-style gauge showing whether the bike is ready to ride, and big tiles
 * for Map, Music, Calls and Setup.
 */
class HomePane(private val activity: Activity, private val onOpen: (CarScreen) -> Unit) {

    private enum class Status(
        val color: Int,
        val progress: Float,
        val breathing: Boolean,
        val word: Int,
        val caption: Int,
    ) {
        STARTING(R.color.status_idle, 0.12f, false, R.string.status_word_starting, R.string.status_caption_starting),
        SETUP(R.color.status_warning, 0.35f, false, R.string.status_word_setup, R.string.status_caption_setup),
        HOTSPOT(R.color.status_warning, 0.62f, false, R.string.status_word_hotspot, R.string.status_caption_hotspot),
        READY(R.color.status_ready, 1f, true, R.string.status_word_ready, R.string.status_caption_ready),
    }

    private class Tile(root: View) {
        val title: TextView = root.findViewById(R.id.tile_title)
        val subtitle: TextView = root.findViewById(R.id.tile_subtitle)
        val icon: ImageView = root.findViewById(R.id.tile_icon)
        val iconChip: View = root.findViewById(R.id.tile_icon_chip)
    }

    private val gauge: RideGaugeView = activity.findViewById(R.id.gauge)
    private val gaugeGlow: View = activity.findViewById(R.id.gauge_glow)
    private val gaugeCenter: View = activity.findViewById(R.id.gauge_center)
    private val statusWord: TextView = activity.findViewById(R.id.status_word)
    private val statusCaption: TextView = activity.findViewById(R.id.status_caption)
    private val statusDetail: TextView = activity.findViewById(R.id.status_detail)
    private val mapTile = tile(R.id.tile_map, R.drawable.ic_map, R.color.tile_navigate, R.string.nav_map) { onOpen(CarScreen.MAP) }
    private val musicTile = tile(R.id.tile_music, R.drawable.ic_music_note, R.color.tile_music, R.string.nav_music) {
        onOpen(CarScreen.MUSIC)
    }
    private val callsTile = tile(R.id.tile_calls, R.drawable.ic_call, R.color.tile_calls, R.string.nav_calls) {
        onOpen(CarScreen.CALLS)
    }
    private val setupTile = tile(R.id.tile_setup, R.drawable.ic_settings, R.color.tile_setup, R.string.tile_setup) {
        openSetup()
    }

    private var shownStatus: Status? = null
    private var glowColor: Int? = null
    private var glowRadius = 0f

    init {
        activity.findViewById<View>(R.id.gauge_frame).apply {
            setOnClickListener { if (shownStatus == Status.SETUP) openSetup() }
            addOnLayoutChangeListener { frame, _, _, _, _, _, _, _, _ -> fitGauge(frame.width, frame.height) }
        }
    }

    /** The gauge: starting, setup needed, not on the iPhone's hotspot, or ready to ride. */
    fun renderStatus(serviceRunning: Boolean, missingSteps: Int, address: String?) {
        val onHotspot = address != null && HotspotLink.isIphoneHotspot(address)
        val status = when {
            !serviceRunning -> Status.STARTING
            missingSteps > 0 -> Status.SETUP
            !onHotspot -> Status.HOTSPOT
            else -> Status.READY
        }
        val color = activity.getColor(status.color)
        gauge.setState(color, status.progress, status.breathing)
        if (status != shownStatus) {
            shownStatus = status
            glowColor = color
            applyGlow()
        }
        statusWord.setText(status.word)
        statusCaption.setText(status.caption)
        statusDetail.text = when (status) {
            Status.STARTING -> activity.getString(R.string.status_detail_starting)
            Status.SETUP -> activity.getString(R.string.status_detail_setup)
            Status.HOTSPOT -> address?.let { activity.getString(R.string.status_detail_other_wifi, it) }
                ?: activity.getString(R.string.status_detail_no_wifi)
            Status.READY -> activity.getString(R.string.status_detail_ready, address)
        }
        if (missingSteps == 0) {
            setupTile.subtitle.setText(R.string.tile_setup_done)
            setupTile.subtitle.setTextColor(activity.getColor(R.color.text_secondary))
        } else {
            setupTile.subtitle.text = activity.resources.getQuantityString(R.plurals.tile_setup_left, missingSteps, missingSteps)
            setupTile.subtitle.setTextColor(activity.getColor(R.color.status_warning))
        }
    }

    fun renderRide(ride: RideController.Ride) {
        mapTile.subtitle.text = ride.destination?.let { activity.getString(R.string.tile_navigate_riding, it) }
            ?: activity.getString(R.string.free_ride)
    }

    /** The Music and Calls tiles follow the Bluetooth link to the iPhone. */
    fun renderIphone(state: IphoneLink.State) {
        val notReady = linkStatusText(activity, state.status)
        val music = state.music
        musicTile.subtitle.text = when {
            notReady != null -> notReady
            music == null -> activity.getString(R.string.music_nothing)
            music.artist.isBlank() -> music.title
            else -> activity.getString(R.string.music_now, music.title, music.artist)
        }
        val call = state.call
        callsTile.subtitle.text = when {
            notReady != null -> notReady
            call != null -> activity.getString(
                R.string.calls_ringing,
                call.caller.ifBlank { activity.getString(R.string.caller_unknown) },
            )
            else -> activity.getString(R.string.calls_ready)
        }
    }

    private fun openSetup() {
        activity.startActivity(Intent(activity, SetupActivity::class.java))
    }

    private fun tile(id: Int, icon: Int, color: Int, title: Int, onClick: () -> Unit): Tile {
        val root = activity.findViewById<View>(id)
        val tint = activity.getColor(color)
        return Tile(root).apply {
            this.title.setText(title)
            this.icon.setImageResource(icon)
            this.icon.setColorFilter(tint)
            iconChip.background.mutate().setTint(tint.withAlpha(0.16f))
            root.setOnClickListener { onClick() }
        }
    }

    /**
     * The gauge is as big as its space allows: large in portrait, smaller in landscape. The motorbike and status word
     * scale with it so they always sit inside the ring of tick marks, and the glow is sized to fade out before the
     * gauge's edges, so it's never cut off.
     */
    private fun fitGauge(width: Int, height: Int) {
        val dp = activity.resources.displayMetrics.density
        val size = minOf(width, height)
        val innerRadius = size / 2f - GAUGE_EDGE_DP * dp
        val scale = (innerRadius / (CENTER_HALF_WIDTH_DP * dp)).coerceIn(MIN_CENTER_SCALE, 1f)
        gaugeCenter.scaleX = scale
        gaugeCenter.scaleY = scale
        if (size / 2f != glowRadius) {
            glowRadius = size / 2f
            applyGlow()
        }
    }

    /** A soft radial glow in the status colour, behind the gauge. */
    private fun applyGlow() {
        val color = glowColor ?: return
        if (glowRadius <= 0f) return
        gaugeGlow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = glowRadius
            colors = intArrayOf(color.withAlpha(0.20f), Color.TRANSPARENT)
        }
    }

    private companion object {
        // From the gauge's edge to just inside its tick marks: glow room + tick inset + tick length + a margin.
        const val GAUGE_EDGE_DP = 60f

        // Half the width of the status word's box (170dp), the widest part of the centre group.
        const val CENTER_HALF_WIDTH_DP = 85f
        const val MIN_CENTER_SCALE = 0.5f
    }
}
