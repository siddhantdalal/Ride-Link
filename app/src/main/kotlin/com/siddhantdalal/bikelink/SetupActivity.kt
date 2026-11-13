package com.siddhantdalal.bikelink

import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        summary = findViewById(R.id.setup_summary)
        stepList = findViewById(R.id.setup_steps)
        findViewById<TextView>(R.id.bike_code).text = BikeCode.formatted(BikeCode.get(this))
        findViewById<View>(R.id.close).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun render() {
        val steps = SetupChecklist(this).steps()
        summary.text = getString(R.string.setup_summary, steps.count { it.done }, steps.size)
        stepList.removeAllViews()
        steps.forEachIndexed { index, step -> stepList.addView(stepRow(index + 1, step)) }
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
            setText(if (step.required) R.string.step_allow else R.string.step_turn_on)
            setOnClickListener { step.fix() }
        }
        return row
    }
}
