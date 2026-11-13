package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
    ): Step {
        val granted = activity.checkSelfPermission(check) == PackageManager.PERMISSION_GRANTED
        return Step(title, detail, granted, required) { activity.requestPermissions(request, 0) }
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
