package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The status and one-time setup screen. The real work happens in [BikeLinkService]. This screen only shows whether
 * everything is ready, and opens the right settings page for anything that isn't.
 */
class MainActivity : Activity() {

    private class SetupStep(
        val title: Int,
        val detail: Int,
        val done: Boolean,
        val optional: Boolean = false,
        val fix: () -> Unit,
    )

    private lateinit var status: TextView
    private lateinit var steps: LinearLayout
    private val render = Runnable { render() }

    /** While plugged in (on the bike), keep the screen on so the status stays visible. */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = keepScreenOnWhileCharging(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        steps = findViewById(R.id.setup_steps)
        findViewById<TextView>(R.id.bike_code).text = BikeCode.formatted(BikeCode.get(this))
    }

    override fun onResume() {
        super.onResume()
        BikeLinkService.start(this)
        render()
        // The service starts asynchronously, so check again once it has had a moment to start.
        status.postDelayed(render, STATUS_RECHECK_MS)
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::keepScreenOnWhileCharging)
    }

    override fun onPause() {
        status.removeCallbacks(render)
        unregisterReceiver(powerReceiver)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun render() {
        status.text = statusText()
        steps.removeAllViews()
        setupSteps().forEach { steps.addView(stepRow(it)) }
    }

    private fun statusText(): String {
        if (!BikeLinkService.isRunning) return getString(R.string.status_starting)
        val address = wifiAddress() ?: return getString(R.string.status_no_wifi)
        return if (address.startsWith(IPHONE_HOTSPOT_PREFIX)) {
            getString(R.string.status_ready_hotspot, address, BikeLinkService.PORT)
        } else {
            getString(R.string.status_other_network, address)
        }
    }

    private fun setupSteps(): List<SetupStep> = buildList {
        add(SetupStep(R.string.step_overlay, R.string.step_overlay_detail, Settings.canDrawOverlays(this@MainActivity)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        })
        val power = getSystemService(PowerManager::class.java)
        add(SetupStep(R.string.step_battery, R.string.step_battery_detail, power.isIgnoringBatteryOptimizations(packageName)) {
            requestBatteryExemption()
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(permissionStep(Manifest.permission.POST_NOTIFICATIONS, R.string.step_notifications, R.string.step_notifications_detail))
        }
        if (Build.VERSION.SDK_INT >= ANDROID_17) {
            add(permissionStep(ACCESS_LOCAL_NETWORK, R.string.step_local_network, R.string.step_local_network_detail))
        }
        val helperDetail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            R.string.step_helper_detail_restricted
        } else {
            R.string.step_helper_detail
        }
        add(SetupStep(R.string.step_helper, helperDetail, MapsHelperService.isEnabled, optional = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
    }

    // Deliberate: the app must keep listening during rides, which is the use case this exemption exists for. The app
    // is installed directly rather than from the Play Store, so the Play policy this lint check enforces doesn't apply.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))
    }

    private fun permissionStep(permission: String, title: Int, detail: Int) =
        SetupStep(title, detail, checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 0)
        }

    private fun stepRow(step: SetupStep): View {
        val row = layoutInflater.inflate(R.layout.setup_step, steps, false)
        row.findViewById<TextView>(R.id.step_title).setText(step.title)
        row.findViewById<TextView>(R.id.step_detail).setText(step.detail)
        row.findViewById<Button>(R.id.step_action).apply {
            if (step.done) {
                setText(R.string.step_done)
                isEnabled = false
            } else {
                setText(if (step.optional) R.string.step_turn_on else R.string.step_allow)
                setOnClickListener { step.fix() }
            }
        }
        return row
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
