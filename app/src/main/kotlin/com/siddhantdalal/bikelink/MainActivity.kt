package com.siddhantdalal.bikelink

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * The dashboard, Bike Link's home screen: the clock, the iPhone link, a speedometer-style status gauge, and large
 * tiles for Navigate, Music, Calls and Setup.
 *
 * The real work happens in [BikeLinkService]. This screen reflects it live: it updates when Wi-Fi changes, when a
 * command arrives from the iPhone, and when setup steps are completed.
 */
class MainActivity : Activity() {

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

    private lateinit var gauge: RideGaugeView
    private lateinit var gaugeGlow: View
    private lateinit var gaugeCenter: View
    private lateinit var statusWord: TextView
    private lateinit var statusCaption: TextView
    private lateinit var statusDetail: TextView
    private lateinit var linkDot: View
    private lateinit var linkLabel: TextView
    private lateinit var navigateTile: Tile
    private lateinit var setupTile: Tile

    private var shownStatus: Status? = null
    private var glowColor: Int? = null
    private var glowRadius = 0f
    private val render = Runnable { render() }

    /** While plugged in (on the bike), keep the screen on so the dashboard stays visible. */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = keepScreenOnWhileCharging(intent)
    }

    private val rideLogListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = renderSoon()
        override fun onLost(network: Network) = renderSoon()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = renderSoon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gauge = findViewById(R.id.gauge)
        gaugeGlow = findViewById(R.id.gauge_glow)
        gaugeCenter = findViewById(R.id.gauge_center)
        statusWord = findViewById(R.id.status_word)
        statusCaption = findViewById(R.id.status_caption)
        statusDetail = findViewById(R.id.status_detail)
        linkDot = findViewById(R.id.link_dot)
        linkLabel = findViewById(R.id.link_label)

        navigateTile = tile(R.id.tile_navigate, R.drawable.ic_navigation, R.color.tile_navigate, R.string.tile_navigate) {
            RideActivity.startPreview(this)
        }
        tile(R.id.tile_music, R.drawable.ic_music_note, R.color.tile_music, R.string.tile_music, ::comingWithBluetooth)
            .subtitle.setText(R.string.tile_bluetooth_next)
        tile(R.id.tile_calls, R.drawable.ic_call, R.color.tile_calls, R.string.tile_calls, ::comingWithBluetooth)
            .subtitle.setText(R.string.tile_bluetooth_next)
        setupTile = tile(R.id.tile_setup, R.drawable.ic_settings, R.color.tile_setup, R.string.tile_setup, ::openSetup)

        findViewById<View>(R.id.gauge_frame).apply {
            setOnClickListener { if (shownStatus == Status.SETUP) openSetup() }
            addOnLayoutChangeListener { frame, _, _, _, _, _, _, _, _ -> fitGauge(frame.width, frame.height) }
        }
    }

    override fun onResume() {
        super.onResume()
        BikeLinkService.start(this)
        render()
        // The service starts asynchronously, so check again once it has had a moment to start.
        gauge.postDelayed(render, STATUS_RECHECK_MS)
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::keepScreenOnWhileCharging)
        RideLog.prefs(this).registerOnSharedPreferenceChangeListener(rideLogListener)
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(wifi, wifiCallback)
    }

    override fun onPause() {
        gauge.removeCallbacks(render)
        unregisterReceiver(powerReceiver)
        RideLog.prefs(this).unregisterOnSharedPreferenceChangeListener(rideLogListener)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback)
        super.onPause()
    }

    /** Network callbacks arrive on a background thread. */
    private fun renderSoon() {
        gauge.post(render)
    }

    private fun render() {
        val steps = SetupChecklist(this).steps()
        val missing = steps.count { it.required && !it.done }
        renderStatus(setupDone = missing == 0)
        renderNavigateTile()
        renderSetupTile(missing)
    }

    private fun renderStatus(setupDone: Boolean) {
        val address = HotspotLink.localAddress()
        val onHotspot = address != null && HotspotLink.isIphoneHotspot(address)
        val status = when {
            !BikeLinkService.isRunning -> Status.STARTING
            !setupDone -> Status.SETUP
            !onHotspot -> Status.HOTSPOT
            else -> Status.READY
        }
        val color = getColor(status.color)
        gauge.setState(color, status.progress, status.breathing)
        if (status != shownStatus) {
            shownStatus = status
            glowColor = color
            applyGlow()
        }
        statusWord.setText(status.word)
        statusCaption.setText(status.caption)
        statusDetail.text = when (status) {
            Status.STARTING -> getString(R.string.status_detail_starting)
            Status.SETUP -> getString(R.string.status_detail_setup)
            Status.HOTSPOT -> address?.let { getString(R.string.status_detail_other_wifi, it) }
                ?: getString(R.string.status_detail_no_wifi)
            Status.READY -> getString(R.string.status_detail_ready, address)
        }

        linkDot.background.mutate().setTint(getColor(if (onHotspot) R.color.status_ready else R.color.status_warning))
        linkLabel.setText(if (onHotspot) R.string.link_ready else R.string.link_missing)
    }

    private fun renderNavigateTile() {
        val last = RideLog.last(this)
        navigateTile.subtitle.text = when {
            last == null -> getString(R.string.tile_navigate_none)
            last.destination == null -> getString(R.string.tile_navigate_stopped, DateUtils.getRelativeTimeSpanString(last.at))
            else -> getString(R.string.tile_navigate_last, last.destination)
        }
    }

    private fun renderSetupTile(missing: Int) {
        if (missing == 0) {
            setupTile.subtitle.setText(R.string.tile_setup_done)
            setupTile.subtitle.setTextColor(getColor(R.color.text_secondary))
        } else {
            setupTile.subtitle.text = resources.getQuantityString(R.plurals.tile_setup_left, missing, missing)
            setupTile.subtitle.setTextColor(getColor(R.color.status_warning))
        }
    }

    private fun tile(id: Int, icon: Int, color: Int, title: Int, onClick: () -> Unit): Tile {
        val root = findViewById<View>(id)
        val tint = getColor(color)
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
        val dp = resources.displayMetrics.density
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

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun comingWithBluetooth() {
        Toast.makeText(this, R.string.coming_with_bluetooth, Toast.LENGTH_SHORT).show()
    }

    private fun keepScreenOnWhileCharging(batteryStatus: Intent) {
        val pluggedIn = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (pluggedIn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private companion object {
        const val STATUS_RECHECK_MS = 1_000L

        // From the gauge's edge to just inside its tick marks: glow room + tick inset + tick length + a margin.
        const val GAUGE_EDGE_DP = 60f

        // Half the width of the status word's box (170dp), the widest part of the centre group.
        const val CENTER_HALF_WIDTH_DP = 85f
        const val MIN_CENTER_SCALE = 0.5f
    }
}
