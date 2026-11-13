package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.NumberFormat

/**
 * The only screen: whether the bike is ready to ride, the last ride, the bike code, and one-time setup.
 *
 * The real work happens in [BikeLinkService]. This screen reflects it live: it updates when Wi-Fi changes, when a
 * command arrives from the iPhone, and when setup steps are completed. Once the required steps are done, setup
 * folds away behind the button in the top bar.
 */
class MainActivity : Activity() {

    private class SetupStep(
        val title: Int,
        val detail: Int,
        val done: Boolean,
        val required: Boolean,
        val fix: () -> Unit,
    )

    private enum class Status(val colorRes: Int, val pulsing: Boolean) {
        IDLE(R.color.status_idle, false),
        WARNING(R.color.status_warning, false),
        READY(R.color.status_ready, true),
    }

    private lateinit var statusRing: StatusRingView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var lastRideTitle: TextView
    private lateinit var lastRideDetail: TextView
    private lateinit var setupToggle: Button
    private lateinit var setupSection: View
    private lateinit var setupSummary: TextView
    private lateinit var setupSteps: LinearLayout

    /** Whether the rider opened the setup list after it folded away. */
    private var setupExpanded = false

    private val render = Runnable { render() }

    /** While plugged in (on the bike), keep the screen on so the status stays visible. */
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
        statusRing = findViewById(R.id.status_ring)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        lastRideTitle = findViewById(R.id.last_ride_title)
        lastRideDetail = findViewById(R.id.last_ride_detail)
        setupToggle = findViewById(R.id.setup_toggle)
        setupSection = findViewById(R.id.setup_section)
        setupSummary = findViewById(R.id.setup_summary)
        setupSteps = findViewById(R.id.setup_steps)
        findViewById<TextView>(R.id.bike_code).text = BikeCode.formatted(BikeCode.get(this))
        setupToggle.setOnClickListener {
            setupExpanded = !setupExpanded
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        BikeLinkService.start(this)
        render()
        // The service starts asynchronously, so check again once it has had a moment to start.
        statusTitle.postDelayed(render, STATUS_RECHECK_MS)
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::keepScreenOnWhileCharging)
        RideLog.prefs(this).registerOnSharedPreferenceChangeListener(rideLogListener)
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(wifi, wifiCallback)
    }

    override fun onPause() {
        statusTitle.removeCallbacks(render)
        unregisterReceiver(powerReceiver)
        RideLog.prefs(this).unregisterOnSharedPreferenceChangeListener(rideLogListener)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    /** Network callbacks arrive on a background thread. */
    private fun renderSoon() {
        statusTitle.post(render)
    }

    private fun render() {
        val steps = setupSteps()
        val requiredDone = steps.filter { it.required }.all { it.done }
        renderStatus(requiredDone)
        renderLastRide()
        renderSetup(steps, requiredDone)
    }

    private fun renderStatus(requiredDone: Boolean) {
        val address = wifiAddress()
        when {
            !BikeLinkService.isRunning ->
                showStatus(Status.IDLE, getString(R.string.status_starting_title), getString(R.string.status_starting_detail))
            !requiredDone ->
                showStatus(Status.WARNING, getString(R.string.status_setup_title), getString(R.string.status_setup_detail))
            address == null ->
                showStatus(Status.WARNING, getString(R.string.status_no_wifi_title), getString(R.string.status_no_wifi_detail))
            !address.startsWith(IPHONE_HOTSPOT_PREFIX) ->
                showStatus(
                    Status.WARNING,
                    getString(R.string.status_other_network_title),
                    getString(R.string.status_other_network_detail, address),
                )
            else ->
                showStatus(Status.READY, getString(R.string.status_ready_title), getString(R.string.status_ready_detail, address))
        }
    }

    private fun showStatus(status: Status, title: String, detail: String) {
        statusRing.color = getColor(status.colorRes)
        statusRing.pulsing = status.pulsing
        statusTitle.text = title
        statusDetail.text = detail
    }

    private fun renderLastRide() {
        val last = RideLog.last(this)
        when {
            last == null -> {
                lastRideTitle.setText(R.string.last_ride_none_title)
                lastRideDetail.setText(R.string.last_ride_none_detail)
            }
            last.destination == null -> {
                lastRideTitle.setText(R.string.last_ride_stopped_title)
                lastRideDetail.text = formatTime(last.at)
            }
            else -> {
                lastRideTitle.text = last.destination
                lastRideDetail.text = getString(R.string.last_ride_navigating_detail, formatTime(last.at))
            }
        }
    }

    private fun renderSetup(steps: List<SetupStep>, requiredDone: Boolean) {
        // Setup stays open until everything required is done. After that it folds away behind the top-bar button.
        val shown = !requiredDone || setupExpanded
        setupSection.visibility = if (shown) View.VISIBLE else View.GONE
        setupToggle.visibility = if (requiredDone) View.VISIBLE else View.GONE
        setupToggle.setText(if (shown) R.string.setup_hide else R.string.setup_show)
        setupSummary.text = getString(R.string.setup_summary, steps.count { it.done }, steps.size)
        setupSteps.removeAllViews()
        steps.forEachIndexed { index, step -> setupSteps.addView(stepRow(index + 1, step)) }
    }

    private fun setupSteps(): List<SetupStep> = buildList {
        add(SetupStep(R.string.step_overlay, R.string.step_overlay_detail, Settings.canDrawOverlays(this@MainActivity), required = true) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        })
        val power = getSystemService(PowerManager::class.java)
        add(SetupStep(R.string.step_battery, R.string.step_battery_detail, power.isIgnoringBatteryOptimizations(packageName), required = true) {
            requestBatteryExemption()
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(permissionStep(Manifest.permission.POST_NOTIFICATIONS, R.string.step_notifications, R.string.step_notifications_detail, required = false))
        }
        if (Build.VERSION.SDK_INT >= ANDROID_17) {
            add(permissionStep(ACCESS_LOCAL_NETWORK, R.string.step_local_network, R.string.step_local_network_detail, required = true))
        }
        val helperDetail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            R.string.step_helper_detail_restricted
        } else {
            R.string.step_helper_detail
        }
        add(SetupStep(R.string.step_helper, helperDetail, MapsHelperService.isEnabled, required = false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
    }

    // Deliberate: the app must keep listening during rides, which is the use case this exemption exists for. The app
    // is installed directly rather than from the Play Store, so the Play policy this lint check enforces doesn't apply.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))
    }

    private fun permissionStep(permission: String, title: Int, detail: Int, required: Boolean) =
        SetupStep(title, detail, checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, required) {
            requestPermissions(arrayOf(permission), 0)
        }

    private fun stepRow(number: Int, step: SetupStep): View {
        val row = layoutInflater.inflate(R.layout.setup_step, setupSteps, false)
        val localizedNumber = NumberFormat.getIntegerInstance().format(number)
        row.findViewById<TextView>(R.id.step_badge).apply {
            when {
                step.done -> {
                    text = "✓"
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
        row.findViewById<TextView>(R.id.step_detail).setText(step.detail)
        row.findViewById<Button>(R.id.step_action).apply {
            visibility = if (step.done) View.GONE else View.VISIBLE
            setText(if (step.required) R.string.step_allow else R.string.step_turn_on)
            setOnClickListener { step.fix() }
        }
        return row
    }

    private fun formatTime(millis: Long): String {
        val flags = DateUtils.FORMAT_SHOW_TIME or
            if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        return DateUtils.formatDateTime(this, millis, flags)
    }

    private fun keepScreenOnWhileCharging(batteryStatus: Intent) {
        val pluggedIn = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (pluggedIn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun packageUri() = Uri.parse("package:$packageName")

    /** The phone's IPv4 address on Wi-Fi (or Bluetooth tethering), e.g. 172.20.10.3. */
    private fun wifiAddress(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && (it.name.startsWith("wlan") || it.name == "bt-pan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress

    private companion object {
        // An iPhone's Personal Hotspot always gives out addresses in 172.20.10.0/28.
        const val IPHONE_HOTSPOT_PREFIX = "172.20.10."
        const val STATUS_RECHECK_MS = 1_000L
        const val ANDROID_17 = 37
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
