package com.siddhantdalal.bikelink

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * CarPlay's sidebar: in landscape a column on the left, in portrait a bar along the bottom (the layouts differ, the
 * ids are the same). It holds the status (clock, iPhone link, battery), buttons for Map, Music and Calls, and the
 * Dashboard/Home toggle.
 */
class NavBar(private val activity: Activity, private val onSelect: (CarScreen) -> Unit) {

    private class NavButton(val root: View) {
        val icon: ImageView = root.findViewById(R.id.nav_button_icon)
        val label: TextView = root.findViewById(R.id.nav_button_label)
    }

    private val map = button(R.id.nav_map, R.drawable.ic_map, R.string.nav_map, CarScreen.MAP)
    private val music = button(R.id.nav_music, R.drawable.ic_music_note, R.string.nav_music, CarScreen.MUSIC)
    private val calls = button(R.id.nav_calls, R.drawable.ic_call, R.string.nav_calls, CarScreen.CALLS)
    private val toggle = NavButton(activity.findViewById(R.id.nav_toggle))
    private val hotspot: ImageView = activity.findViewById(R.id.nav_hotspot)
    private val bluetooth: ImageView = activity.findViewById(R.id.nav_bluetooth)
    private val battery: TextView = activity.findViewById(R.id.nav_battery)
    private var current = CarScreen.DASHBOARD

    init {
        // Like CarPlay's lower-left button: from Home it goes to the Dashboard, from anywhere else back Home.
        toggle.root.setOnClickListener {
            onSelect(if (current == CarScreen.HOME) CarScreen.DASHBOARD else CarScreen.HOME)
        }
    }

    fun select(screen: CarScreen) {
        current = screen
        highlight(map, screen == CarScreen.MAP)
        highlight(music, screen == CarScreen.MUSIC)
        highlight(calls, screen == CarScreen.CALLS)
        val toDashboard = screen == CarScreen.HOME
        val icon = if (toDashboard) R.drawable.ic_dashboard else R.drawable.ic_apps
        val label = if (toDashboard) R.string.nav_dashboard else R.string.nav_home
        toggle.icon.setImageResource(icon)
        toggle.label.setText(label)
        toggle.root.contentDescription = activity.getString(label)
        highlight(toggle, selected = false)
    }

    /** Green when connected, amber when not: the hotspot carries internet, Bluetooth carries calls and music. */
    fun renderLinks(onHotspot: Boolean, bluetoothStatus: IphoneLink.Status) {
        val linked = activity.getColor(R.color.status_ready)
        val waiting = activity.getColor(R.color.status_warning)
        hotspot.setColorFilter(if (onHotspot) linked else waiting)
        hotspot.contentDescription = activity.getString(
            if (onHotspot) R.string.status_hotspot_on else R.string.status_hotspot_off,
        )
        val bluetoothColor = when (bluetoothStatus) {
            IphoneLink.Status.CONNECTED -> linked
            IphoneLink.Status.UNSUPPORTED -> activity.getColor(R.color.status_idle)
            else -> waiting
        }
        bluetooth.setColorFilter(bluetoothColor)
        bluetooth.contentDescription = activity.getString(
            if (bluetoothStatus == IphoneLink.Status.CONNECTED) R.string.status_bluetooth_on else R.string.status_bluetooth_off,
        )
    }

    /** The bike phone's battery: green while charging, amber under 50%, red under 20% when not charging. */
    fun renderBattery(percent: Int, charging: Boolean) {
        val color = activity.getColor(
            when {
                charging -> R.color.status_ready
                percent < LOW_BATTERY -> R.color.danger
                percent < HALF_BATTERY -> R.color.status_warning
                else -> R.color.text_primary
            },
        )
        battery.text = activity.getString(R.string.battery_level, percent)
        battery.setTextColor(color)
        battery.setCompoundDrawablesRelativeWithIntrinsicBounds(if (charging) boltIcon() else null, null, null, null)
        battery.compoundDrawableTintList = ColorStateList.valueOf(color)
        battery.contentDescription = activity.getString(
            if (charging) R.string.battery_charging else R.string.battery_not_charging,
            percent,
        )
    }

    /** A small charging bolt, sized to sit beside the battery text. */
    private fun boltIcon(): Drawable? = activity.getDrawable(R.drawable.ic_bolt)?.mutate()?.apply {
        val size = (BOLT_SIZE_DP * activity.resources.displayMetrics.density).toInt()
        setBounds(0, 0, size, size)
    }

    private fun button(id: Int, icon: Int, label: Int, screen: CarScreen): NavButton =
        NavButton(activity.findViewById(id)).apply {
            this.icon.setImageResource(icon)
            this.label.setText(label)
            root.contentDescription = activity.getString(label)
            root.setOnClickListener { onSelect(screen) }
        }

    private fun highlight(button: NavButton, selected: Boolean) {
        button.root.setBackgroundResource(if (selected) R.drawable.bg_nav_selected else R.drawable.bg_nav_button)
        val color = activity.getColor(if (selected) R.color.accent else R.color.text_secondary)
        button.icon.setColorFilter(color)
        button.label.setTextColor(color)
    }

    private companion object {
        const val LOW_BATTERY = 20
        const val HALF_BATTERY = 50
        const val BOLT_SIZE_DP = 14
    }
}

/** The car screen's screens, switched from the sidebar, like CarPlay's apps. */
enum class CarScreen { DASHBOARD, MAP, MUSIC, CALLS, HOME }
