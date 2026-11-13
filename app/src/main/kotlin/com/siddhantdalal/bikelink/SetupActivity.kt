package com.siddhantdalal.bikelink

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat

/** One-time setup: each permission as a numbered card, plus the bike code to enter in the iPhone Shortcuts. */
class SetupActivity : Activity() {

    private lateinit var summary: TextView
    private lateinit var stepList: LinearLayout
    /** The pairing step completes on the iPhone, so the list updates live as the link connects. */
    private val iphoneListener: (IphoneLink.State) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        summary = findViewById(R.id.setup_summary)
        stepList = findViewById(R.id.setup_steps)
        findViewById<TextView>(R.id.bike_code).text = BikeCode.formatted(BikeCode.get(this))
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.about).setOnClickListener { showAbout() }
    }

    override fun onResume() {
        super.onResume()
        IphoneLink.addListener(iphoneListener)
    }

    override fun onPause() {
        IphoneLink.removeListener(iphoneListener)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // A newly granted Bluetooth permission lets the link start advertising to the iPhone straight away.
        IphoneLink.start(this)
        render()
    }

    private fun render() {
        // The iPhone Shortcuts ask for this address when they're imported.
        findViewById<TextView>(R.id.bike_address).text = HotspotLink.localAddress()
            ?.takeIf(HotspotLink::isIphoneHotspot)
            ?.let { getString(R.string.bike_address, it) }
            ?: getString(R.string.bike_address_unknown)
        val steps = SetupChecklist(this).steps()
        summary.text = getString(R.string.setup_summary, steps.count { it.done }, steps.size)
        stepList.removeAllViews()
        steps.forEachIndexed { index, step -> stepList.addView(stepRow(index + 1, step)) }
    }

    /** Credits and licences: Google's navigation (its terms ask for attribution), the typeface and the icons. */
    private fun showAbout() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.about_font_licence) { _, _ -> showLicence("licenses/space_grotesk_OFL.txt") }
            .setNegativeButton(R.string.about_icon_licence) { _, _ -> showLicence("licenses/material_symbols_LICENSE.txt") }
            .show()
    }

    private fun showLicence(asset: String) {
        val licence = assets.open(asset).bufferedReader().use { it.readText() }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(licence)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun stepRow(number: Int, step: SetupChecklist.Step): View {
        val row = layoutInflater.inflate(R.layout.setup_step, stepList, false)
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
        // A finished step collapses to one line, so the whole checklist fits the screen, even in landscape.
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
}
