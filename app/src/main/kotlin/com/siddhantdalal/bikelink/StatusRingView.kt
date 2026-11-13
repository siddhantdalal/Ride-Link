package com.siddhantdalal.bikelink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * The status card's centrepiece: the app's artwork inside a ring in the status colour. While the bike is ready to
 * ride, a soft wave pulses outwards from the ring.
 *
 * It's drawn by hand, so the app needs no graphics or animation library. The pulse respects the system's "Remove
 * animations" setting, and it stops whenever the screen isn't visible.
 */
class StatusRingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val artwork: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private var wavePhase = 0f
    private val waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_200
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            wavePhase = it.animatedValue as Float
            invalidate()
        }
    }

    /** An ARGB colour int. */
    var color: Int = Color.GRAY
        set(value) {
            field = value
            invalidate()
        }

    var pulsing: Boolean = false
        set(value) {
            field = value
            updateAnimation()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - wavePaint.strokeWidth
        val radius = outer * 0.74f

        if (waveAnimator.isRunning) {
            wavePaint.color = color.withAlpha(0.55f * (1 - wavePhase))
            canvas.drawCircle(cx, cy, radius + (outer - radius) * wavePhase, wavePaint)
        }
        fillPaint.color = color.withAlpha(0.14f)
        canvas.drawCircle(cx, cy, radius, fillPaint)
        ringPaint.color = color
        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2, ringPaint)

        // The artwork's drawing covers about half of its canvas. Sizing the canvas at about 3× the ring's radius makes
        // the drawing fill roughly three quarters of the ring.
        val half = radius * 1.55f
        artwork.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        artwork.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        waveAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        val shouldRun = pulsing && isAttachedToWindow && windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()
        if (shouldRun && !waveAnimator.isStarted) {
            waveAnimator.start()
        } else if (!shouldRun && waveAnimator.isStarted) {
            waveAnimator.cancel()
            invalidate()
        }
    }

    private fun Int.withAlpha(alpha: Float): Int =
        (this and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
}
