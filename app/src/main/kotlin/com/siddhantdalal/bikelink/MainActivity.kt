package com.siddhantdalal.bikelink

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.NumberFormat

/**
 * Bike Link's only screen: whether everything is ready (Google Maps, the iPhone hotspot, the iPhone's calls), the
 * music bar switch, the one-time setup, and the bike code for the iPhone Shortcuts.
 *
 * Riding happens in Google Maps. This screen is for setting up and checking, and it updates live as things connect.
 */
class MainActivity : Activity() {

    /** One line of the status card. */
    private class StatusRow(root: View, icon: Int, title: Int) {
        private val iconView: ImageView = root.findViewById(R.id.status_icon)
        private val value: TextView = root.findViewById(R.id.status_value)

        init {
            iconView.setImageResource(icon)
            root.findViewById<TextView>(R.id.status_title).setText(title)
        }

        fun show(text: String, color: Int) {
            value.text = text
            iconView.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private lateinit var summary: TextView
    private lateinit var openMaps: Button
    private lateinit var mapsRow: StatusRow
    private lateinit var hotspotRow: StatusRow
    private lateinit var callsRow: StatusRow
    private lateinit var musicDetail: TextView
    private lateinit var setupSummary: TextView
    private lateinit var stepList: LinearLayout
    private lateinit var bikeAddress: TextView

    private val renderLater = Runnable { render() }
    private val iphoneListener: (IphoneLink.State) -> Unit = { render() }

    /** Network callbacks arrive on a background thread, so they hop to the main thread to redraw. */
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = renderSoon()
        override fun onLost(network: Network) = renderSoon()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = renderSoon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        summary = findViewById(R.id.summary)
        openMaps = findViewById(R.id.open_maps)
        mapsRow = StatusRow(findViewById(R.id.row_maps), R.drawable.ic_navigation, R.string.status_maps)
        hotspotRow = StatusRow(findViewById(R.id.row_hotspot), R.drawable.ic_wifi_tethering, R.string.status_hotspot)
        callsRow = StatusRow(findViewById(R.id.row_calls), R.drawable.ic_call, R.string.status_calls)
        musicDetail = findViewById(R.id.music_detail)
        setupSummary = findViewById(R.id.setup_summary)
        stepList = findViewById(R.id.setup_steps)
        bikeAddress = findViewById(R.id.bike_address)

        findViewById<TextView>(R.id.bike_code).text = BikeCode.formatted(BikeCode.get(this))
        findViewById<View>(R.id.about).setOnClickListener { showAbout() }
        openMaps.setOnClickListener { GoogleMaps.bringToFront(this)?.let(::startActivity) }
        findViewById<Switch>(R.id.music_switch).apply {
            isChecked = Overlays.isMusicBarOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Overlays.setMusicBarOn(this@MainActivity, on) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Starts the background service if it isn't running (and retries its listener if that failed).
        BikeLinkService.start(this)
        IphoneLink.addListener(iphoneListener) // also renders
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(wifi, wifiCallback)
        // The service starts asynchronously, so check again once it has had a moment.
        summary.postDelayed(renderLater, SERVICE_START_MS)
    }

    override fun onPause() {
        summary.removeCallbacks(renderLater)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback)
        IphoneLink.removeListener(iphoneListener)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // A newly granted Bluetooth permission lets the link start advertising to the iPhone straight away.
        IphoneLink.start(this)
        render()
    }

    private fun renderSoon() {
        summary.post(renderLater)
    }

    private fun render() {
        val steps = SetupChecklist(this).steps()
        val address = HotspotLink.localAddress()
        val onHotspot = address != null && HotspotLink.isIphoneHotspot(address)
        renderSummary(missingSteps = steps.count { it.required && !it.done }, onHotspot)
        renderStatus(address, onHotspot, IphoneLink.state)
        renderSteps(steps)
        bikeAddress.text = if (onHotspot) getString(R.string.bike_address, address) else getString(R.string.bike_address_unknown)
    }

    private fun renderSummary(missingSteps: Int, onHotspot: Boolean) {
        val (text, color) = when {
            !BikeLinkService.isListening -> getString(R.string.summary_starting) to R.color.status_idle
            missingSteps > 0 ->
                resources.getQuantityString(R.plurals.summary_setup_left, missingSteps, missingSteps) to R.color.status_warning
            !onHotspot -> getString(R.string.summary_hotspot) to R.color.status_warning
            else -> getString(R.string.summary_ready) to R.color.accent
        }
        summary.text = text
        summary.setTextColor(getColor(color))
    }

    private fun renderStatus(address: String?, onHotspot: Boolean, link: IphoneLink.State) {
        val ready = getColor(R.color.accent)
        val attention = getColor(R.color.status_warning)

        val hasMaps = GoogleMaps.isInstalled(this)
        mapsRow.show(getString(if (hasMaps) R.string.status_maps_ready else R.string.status_maps_missing), if (hasMaps) ready else attention)
        openMaps.isEnabled = hasMaps
        openMaps.alpha = if (hasMaps) 1f else DISABLED_ALPHA

        when {
            onHotspot -> hotspotRow.show(getString(R.string.status_hotspot_on, address), ready)
            address != null -> hotspotRow.show(getString(R.string.status_hotspot_other_wifi), attention)
            else -> hotspotRow.show(getString(R.string.status_hotspot_off), attention)
        }

        val (callsText, callsColor) = when (link.status) {
            IphoneLink.Status.CONNECTED -> R.string.link_connected to ready
            IphoneLink.Status.CONNECTING -> R.string.link_connecting to attention
            IphoneLink.Status.WAITING -> R.string.link_waiting to attention
            IphoneLink.Status.BLUETOOTH_OFF -> R.string.link_bluetooth_off to attention
            IphoneLink.Status.PERMISSION_NEEDED -> R.string.link_permission to attention
            IphoneLink.Status.UNSUPPORTED -> R.string.link_unsupported to getColor(R.color.status_idle)
        }
        callsRow.show(getString(callsText), callsColor)

        val song = link.music?.takeIf { link.status == IphoneLink.Status.CONNECTED }
        musicDetail.text = if (song != null) getString(R.string.music_bar_now, song.title) else getString(R.string.music_bar_detail)
    }

    private fun renderSteps(steps: List<SetupChecklist.Step>) {
        setupSummary.text = getString(R.string.setup_summary, steps.count { it.done }, steps.size)
        stepList.removeAllViews()
        steps.forEachIndexed { index, step -> stepList.addView(stepRow(index + 1, step)) }
    }

    private fun stepRow(number: Int, step: SetupChecklist.Step): View {
        val row = layoutInflater.inflate(R.layout.setup_step, stepList, false)
        val localizedNumber = NumberFormat.getIntegerInstance().format(number)
        row.findViewById<TextView>(R.id.step_badge).apply {
            when {
                step.done -> {
                    text = getString(R.string.step_done_mark)
                    contentDescription = getString(R.string.step_done_description)
                    setTextColor(getColor(R.color.on_accent))
                    setBackgroundResource(R.drawable.bg_badge_done)
                }
                step.required -> {
                    text = localizedNumber
                    setTextColor(getColor(R.color.status_warning))
                    setBackgroundResource(R.drawable.bg_badge_required)
                }
                else -> {
                    text = localizedNumber
                    setTextColor(getColor(R.color.text_secondary))
                    setBackgroundResource(R.drawable.bg_badge_optional)
                }
            }
        }
        row.findViewById<TextView>(R.id.step_title).setText(step.title)
        // A finished step shrinks to one line, so the list stays short.
        row.findViewById<TextView>(R.id.step_detail).apply {
            setText(step.detail)
            visibility = if (step.done) View.GONE else View.VISIBLE
        }
        row.findViewById<Button>(R.id.step_action).apply {
            visibility = if (step.done) View.GONE else View.VISIBLE
            setText(step.action)
            setOnClickListener { step.fix() }
        }
        return row
    }

    /** Version, and credits and licences for the typeface and the icons. */
    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.about_font_licence) { _, _ -> showLicence("licenses/space_grotesk_OFL.txt") }
            .setNegativeButton(R.string.about_icon_licence) { _, _ -> showLicence("licenses/material_symbols_LICENSE.txt") }
            .show()
    }

    private fun showLicence(asset: String) {
        val licence = assets.open(asset).bufferedReader().use { it.readText() }
        AlertDialog.Builder(this)
            .setMessage(licence)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private companion object {
        const val SERVICE_START_MS = 1_000L
        const val DISABLED_ALPHA = 0.4f
    }
}
