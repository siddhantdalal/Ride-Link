package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** The one-time permissions Bike Link needs, as shown on the setup screen and summarised on the dashboard. */
class SetupChecklist(private val activity: Activity) {

    class Step(
        val title: Int,
        val detail: Int,
        val done: Boolean,
        /** Without it, rides can't start. Optional steps add extras. */
        val required: Boolean,
        /** The button's label. */
        val action: Int = if (required) R.string.step_allow else R.string.step_turn_on,
        val fix: () -> Unit,
    )

    fun steps(): List<Step> = buildList {
        // Navigation needs precise location. Asking for both lets Android 12+ offer its precise/approximate choice.
        add(permissionStep(
            check = Manifest.permission.ACCESS_FINE_LOCATION,
            request = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            title = R.string.step_location,
            detail = R.string.step_location_detail,
            required = true,
        ))
        add(Step(R.string.step_overlay, R.string.step_overlay_detail, Settings.canDrawOverlays(activity), required = true) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        })
        val power = activity.getSystemService(PowerManager::class.java)
        val unrestricted = power.isIgnoringBatteryOptimizations(activity.packageName)
        add(Step(R.string.step_battery, R.string.step_battery_detail, unrestricted, required = true) {
            requestBatteryExemption()
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(permissionStep(
                check = Manifest.permission.POST_NOTIFICATIONS,
                title = R.string.step_notifications,
                detail = R.string.step_notifications_detail,
                required = false,
            ))
        }
        // Calls and music from the iPhone, over Bluetooth. Android 12+ only: older versions can't advertise the
        // way the iPhone needs to find the phone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetooth = IphoneLink.requiredPermissions()
            add(permissionStep(
                check = bluetooth.first(),
                request = bluetooth,
                title = R.string.step_bluetooth,
                detail = R.string.step_bluetooth_detail,
                required = false,
                alsoGranted = bluetooth.toList(),
            ))
            val paired = IphoneLink.state.status == IphoneLink.Status.CONNECTED
            add(Step(R.string.step_pair, R.string.step_pair_detail, paired, required = false, action = R.string.step_show_me) {
                showPairingSteps()
            })
        }
        if (Build.VERSION.SDK_INT >= ANDROID_17) {
            add(permissionStep(
                check = ACCESS_LOCAL_NETWORK,
                title = R.string.step_local_network,
                detail = R.string.step_local_network_detail,
                required = true,
            ))
        }
    }

    private fun permissionStep(
        check: String,
        title: Int,
        detail: Int,
        required: Boolean,
        request: Array<String> = arrayOf(check),
        alsoGranted: List<String> = listOf(check),
    ): Step {
        val granted = alsoGranted.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return Step(title, detail, granted, required) { activity.requestPermissions(request, 0) }
    }

    /** Pairing happens on the iPhone, so this shows the rider what to tap there, including this phone's name. */
    private fun showPairingSteps() {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.pair_title)
            .setMessage(activity.getString(R.string.pair_steps, bluetoothName()))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** The name the iPhone lists this phone under. Reading it needs the Bluetooth permission. */
    @SuppressLint("MissingPermission") // checked just above the call
    private fun bluetoothName(): String {
        val canRead = IphoneLink.hasPermissions(activity)
        val name = if (canRead) activity.getSystemService(BluetoothManager::class.java)?.adapter?.name else null
        return name ?: Build.MODEL
    }

    // Deliberate: the app must keep listening during rides, which is the use case this exemption exists for. The app
    // is installed directly rather than from the Play Store, so the Play policy this lint check enforces doesn't apply.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))
    }

    private fun packageUri() = Uri.parse("package:${activity.packageName}")

    private companion object {
        const val ANDROID_17 = 37
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
