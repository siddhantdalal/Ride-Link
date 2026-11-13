package com.siddhantdalal.bikelink

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

/** The one-time permissions Bike Link needs, as listed on its screen. */
class SetupChecklist(private val activity: Activity) {

    class Step(
        val title: Int,
        val detail: Int,
        val done: Boolean,
        /** Without it, Bike Link's main features don't work. Optional steps add extras. */
        val required: Boolean,
        /** The button's label. */
        val action: Int = if (required) R.string.step_allow else R.string.step_turn_on,
        val fix: () -> Unit,
    )

    fun steps(): List<Step> = buildList {
        add(Step(R.string.step_overlay, R.string.step_overlay_detail, Settings.canDrawOverlays(activity), required = true) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        })
        val power = activity.getSystemService(PowerManager::class.java)
        val unrestricted = power.isIgnoringBatteryOptimizations(activity.packageName)
        add(Step(R.string.step_battery, R.string.step_battery_detail, unrestricted, required = true) {
            requestBatteryExemption()
        })
        // The iPhone's calls and music, over Bluetooth. Android 12+ only: older versions can't advertise the way the
        // iPhone needs to find the phone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetooth = IphoneLink.requiredPermissions()
            add(permissionStep(bluetooth, R.string.step_bluetooth, R.string.step_bluetooth_detail, required = true))
            val paired = IphoneLink.state.status == IphoneLink.Status.CONNECTED
            add(Step(R.string.step_pair, R.string.step_pair_detail, paired, required = false, action = R.string.step_show_me) {
                showPairingSteps()
            })
        }
        // Android 13+ hides accessibility settings from sideloaded apps until the user allows them, so say how.
        val helperDetail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            R.string.step_helper_detail_restricted
        } else {
            R.string.step_helper_detail
        }
        add(Step(R.string.step_helper, helperDetail, MapsHelperService.isEnabled, required = false) {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(permissionStep(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                R.string.step_notifications,
                R.string.step_notifications_detail,
                required = false,
            ))
        }
        if (Build.VERSION.SDK_INT >= ANDROID_17) {
            add(permissionStep(
                arrayOf(ACCESS_LOCAL_NETWORK),
                R.string.step_local_network,
                R.string.step_local_network_detail,
                required = true,
            ))
        }
    }

    /** A step that's done once every one of [permissions] is granted, and asks for all of them at once. */
    private fun permissionStep(permissions: Array<String>, title: Int, detail: Int, required: Boolean): Step {
        val granted = permissions.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return Step(title, detail, granted, required) { activity.requestPermissions(permissions, 0) }
    }

    /** Pairing happens on the iPhone, so this shows the rider what to tap there, including this phone's name. */
    private fun showPairingSteps() {
        AlertDialog.Builder(activity)
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
