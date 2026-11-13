package com.siddhantdalal.bikelink

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The dashboard's centrepiece, styled like a motorbike speedometer: a 270° arc with tick marks. It fills to show
 * how ready the link is, glowing in the status colour.
 *
 * Changes between states sweep smoothly, and while the bike is ready to ride the glow slowly breathes. Both
 * animations respect the system's "Remove animations" setting, and the breathing stops whenever the screen isn't
 * visible. Everything is drawn by hand, so no graphics library is needed.
 */
class RideGaugeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val oval = RectF()
    private val trackPaint = arcPaint(TRACK_WIDTH_DP).apply { color = context.getColor(R.color.gauge_track) }
    private val arcPaint = arcPaint(TRACK_WIDTH_DP)
    private val glowPaint = arcPaint(0f)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimTickColor = context.getColor(R.color.text_tertiary)

    // What is drawn right now, which changes during a transition…
    private var color = context.getColor(R.color.status_idle)
    private var progress = 0f
    private var glow = 1f

    // …and what was last asked for.
    private var targetColor = color
    private var targetProgress = progress
    private var breathing = false

    private var transition: ValueAnimator? = null
    private val breathe = ValueAnimator.ofFloat(0.5f, 1f).apply {
        duration = 1_600
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            glow = it.animatedValue as Float
            invalidate()
        }
    }

    /** Sweeps to [progress] (0 to 1) in [color]. While [breathing], the glow slowly pulses. */
    fun setState(color: Int, progress: Float, breathing: Boolean) {
        this.breathing = breathing
        updateBreathing()
        if (color == targetColor && progress == targetProgress) return
        targetColor = color
        targetProgress = progress

        transition?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !isLaidOut) {
            this.color = color
            this.progress = progress
            invalidate()
            return
        }
        val fromColor = this.color
        val fromProgress = this.progress
        val colors = ArgbEvaluator()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val t = it.animatedFraction
                this@RideGaugeView.color = colors.evaluate(t, fromColor, color) as Int
                this@RideGaugeView.progress = fromProgress + (progress - fromProgress) * t
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - GLOW_ROOM_DP * dp
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)
        drawTicks(canvas, cx, cy, radius - TICK_INSET_DP * dp)

        val sweep = SWEEP_ANGLE * progress
        if (sweep < 0.5f) return
        for ((extraWidth, alpha) in GLOW_LAYERS) {
            glowPaint.strokeWidth = (TRACK_WIDTH_DP + extraWidth) * dp
            glowPaint.color = color.withAlpha(alpha * glow)
            canvas.drawArc(oval, START_ANGLE, sweep, false, glowPaint)
        }
        arcPaint.color = color
        canvas.drawArc(oval, START_ANGLE, sweep, false, arcPaint)

        // A bright tip at the end of the arc, like the tip of a needle.
        val angle = Math.toRadians((START_ANGLE + sweep).toDouble())
        val tipX = cx + radius * cos(angle).toFloat()
        val tipY = cy + radius * sin(angle).toFloat()
        tipPaint.color = color.withAlpha(0.35f * glow)
        canvas.drawCircle(tipX, tipY, 11 * dp, tipPaint)
        tipPaint.color = Color.WHITE
        canvas.drawCircle(tipX, tipY, 4 * dp, tipPaint)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, outerRadius: Float) {
        for (i in 0..TICKS) {
            val fraction = i / TICKS.toFloat()
            val major = i % MAJOR_EVERY == 0
            val length = (if (major) 10f else 5f) * dp
            tickPaint.strokeWidth = (if (major) 3f else 2f) * dp
            tickPaint.color = if (fraction <= progress + 0.001f) {
                color.withAlpha(if (major) 0.95f else 0.65f)
            } else {
                dimTickColor.withAlpha(if (major) 0.8f else 0.45f)
            }
            val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * fraction).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            canvas.drawLine(
                cx + (outerRadius - length) * dx, cy + (outerRadius - length) * dy,
                cx + outerRadius * dx, cy + outerRadius * dy,
                tickPaint,
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBreathing()
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        breathe.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateBreathing()
    }

    private fun updateBreathing() {
        val shouldRun = breathing && isAttachedToWindow && windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()
        if (shouldRun && !breathe.isStarted) {
            breathe.start()
        } else if (!shouldRun && breathe.isStarted) {
            breathe.cancel()
            glow = 1f
            invalidate()
        }
    }

    private fun arcPaint(widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = widthDp * dp
    }

    private companion object {
        const val START_ANGLE = 135f // the gap sits at the bottom, like a speedometer
        const val SWEEP_ANGLE = 270f
        const val TRACK_WIDTH_DP = 12f
        const val GLOW_ROOM_DP = 22f
        const val TICK_INSET_DP = 24f
        const val TICKS = 36 // one every 7.5°
        const val MAJOR_EVERY = 4 // a longer tick every 30°

        /** Extra stroke width (dp) and opacity of each glow layer under the arc, widest last. */
        val GLOW_LAYERS = listOf(10f to 0.16f, 20f to 0.08f, 32f to 0.04f)
    }
}
