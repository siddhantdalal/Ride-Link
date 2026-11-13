package com.siddhantdalal.bikelink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The incoming-call capsule: slide the knob to the green end to answer, or to the red end to decline.
 *
 * It lies along its longer side. In landscape it runs across the screen and right answers. In portrait it runs up
 * and down and up answers. The knob has to travel most of the way ([COMMIT_FRACTION]) before anything happens, so a
 * glove brushing it or a bump in the road can't answer or decline by accident. Let go early and it springs back.
 *
 * Screen readers get Answer and Decline as actions, since they can't slide.
 */
class SwipeCallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called once, when the knob reaches an end: true to answer, false to decline. */
    var onResult: (answer: Boolean) -> Unit = {}

    private val answerColor = context.getColor(R.color.accent)
    private val declineColor = context.getColor(R.color.danger)
    private val trackPaint = fill(context.getColor(R.color.glass_top))
    private val trackEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = context.getColor(R.color.glass_stroke)
    }
    private val tintPaint = fill(Color.TRANSPARENT)
    private val endPaint = fill(Color.TRANSPARENT)
    private val knobPaint = fill(context.getColor(R.color.calls))
    private val ringPaint = fill(context.getColor(R.color.calls))
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val answerIcon = icon(R.drawable.ic_call, context.getColor(R.color.on_accent))
    private val declineIcon = icon(R.drawable.ic_call_end, Color.WHITE)
    private val knobIcon = icon(R.drawable.ic_call, context.getColor(R.color.surface))
    private val answerLabel = context.getString(R.string.accept_call)
    private val declineLabel = context.getString(R.string.decline_call)

    /** -1 is declined, 0 is resting in the middle, +1 is answered. */
    private var progress = 0f
    private var dragging = false
    private var downAlong = 0f
    private var progressAtDown = 0f
    /** Answered or declined: the capsule stays put until the call goes away. */
    private var settled = false
    private var settle: ValueAnimator? = null
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PULSE_MS
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { if (!dragging && progress == 0f) invalidate() }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.call_swipe_description)
    }

    private val horizontal get() = width >= height
    private val thickness get() = min(width, height).toFloat()
    private val knobRadius get() = thickness / 2 - dp(KNOB_INSET_DP)
    private val endRadius get() = knobRadius * END_SIZE

    /** How far the knob's centre can move from the middle toward either end. */
    private val travel get() = max(width, height) / 2f - thickness / 2

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulse.start()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        settle?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = thickness / 2
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, trackPaint)
        // The track takes on the colour of the end the knob is heading for.
        if (progress != 0f) {
            tintPaint.color = (if (progress > 0) answerColor else declineColor).withAlpha(abs(progress) * TINT_ALPHA)
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, tintPaint)
        }
        val inset = trackEdgePaint.strokeWidth / 2
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, trackEdgePaint)

        drawEnd(canvas, end = 1f, answerColor, answerIcon, answerLabel)
        drawEnd(canvas, end = -1f, declineColor, declineIcon, declineLabel)

        val knob = along(progress)
        if (!dragging && progress == 0f) {
            // A slow pulse around the resting knob says "this moves".
            val t = pulse.animatedValue as Float
            ringPaint.alpha = ((1 - t) * RING_ALPHA * 255).roundToInt()
            canvas.drawCircle(knob.x, knob.y, knobRadius * (1 + RING_GROWTH * t), ringPaint)
        }
        canvas.drawCircle(knob.x, knob.y, knobRadius, knobPaint)
        drawIcon(canvas, knobIcon, knob, knobRadius * ICON_SIZE)
    }

    /** An end of the track ([end] is +1 or -1): a coloured circle with its icon, and its label on the side facing the middle. */
    private fun drawEnd(canvas: Canvas, end: Float, color: Int, icon: Drawable, label: String) {
        val centre = along(end)
        endPaint.color = color
        canvas.drawCircle(centre.x, centre.y, endRadius, endPaint)
        drawIcon(canvas, icon, centre, endRadius * ICON_SIZE)
        // The label's centre, measured along the track from the end toward the middle.
        val labelHalf = if (horizontal) labelPaint.measureText(label) / 2 else (labelPaint.descent() - labelPaint.ascent()) / 2
        val labelAt = along(end * (1 - (endRadius + dp(LABEL_GAP_DP) + labelHalf) / travel))
        val textMiddle = (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, labelAt.x, labelAt.y - textMiddle, labelPaint)
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, centre: PointF, halfSize: Float) {
        val half = halfSize.roundToInt()
        val x = centre.x.roundToInt()
        val y = centre.y.roundToInt()
        icon.setBounds(x - half, y - half, x + half, y + half)
        icon.draw(canvas)
    }

    /** The point along the track for a [position] from -1 (decline end) to +1 (answer end). */
    private fun along(position: Float): PointF {
        val offset = position * travel
        return if (horizontal) PointF(width / 2f + offset, height / 2f) else PointF(width / 2f, height / 2f - offset)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (settled) return false
        val position = if (horizontal) event.x else event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the knob can be grabbed (generously, for gloves). A tap elsewhere on the track does nothing.
                val knob = along(progress)
                if (hypot(event.x - knob.x, event.y - knob.y) > knobRadius * GRAB_SLOP) return false
                settle?.cancel()
                dragging = true
                downAlong = position
                progressAtDown = progress
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val moved = (position - downAlong) / travel
                // Right is answer in landscape. Up is answer in portrait, and up is a smaller y.
                progress = (progressAtDown + if (horizontal) moved else -moved).coerceIn(-1f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                performClick()
                when {
                    progress >= COMMIT_FRACTION -> commit(answer = true)
                    progress <= -COMMIT_FRACTION -> commit(answer = false)
                    else -> animateTo(0f)
                }
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                animateTo(0f)
            }
        }
        return true
    }

    // The slide is the control. A plain click does nothing, but accessibility services expect performClick.
    override fun performClick(): Boolean = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (settled) return
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.call_action_answer, answerLabel))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.call_action_decline, declineLabel))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when {
        settled -> super.performAccessibilityAction(action, arguments)
        action == R.id.call_action_answer -> true.also { commit(answer = true) }
        action == R.id.call_action_decline -> true.also { commit(answer = false) }
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun commit(answer: Boolean) {
        settled = true
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
        )
        animateTo(if (answer) 1f else -1f)
        onResult(answer)
    }

    private fun animateTo(target: Float) {
        settle?.cancel()
        settle = ValueAnimator.ofFloat(progress, target).apply {
            duration = SETTLE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun icon(id: Int, tint: Int): Drawable =
        requireNotNull(context.getDrawable(id)).mutate().apply { setTint(tint) }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val COMMIT_FRACTION = 0.75f
        const val KNOB_INSET_DP = 8f
        const val END_SIZE = 0.62f
        const val ICON_SIZE = 0.5f
        const val GRAB_SLOP = 1.4f
        const val LABEL_SP = 14f
        const val LABEL_GAP_DP = 10f
        const val TINT_ALPHA = 0.35f
        const val RING_ALPHA = 0.45f
        const val RING_GROWTH = 0.35f
        const val PULSE_MS = 1_400L
        const val SETTLE_MS = 220L

        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        /** This ARGB colour with its alpha replaced by [alpha] (0 to 1). */
        fun Int.withAlpha(alpha: Float): Int = (this and 0x00FFFFFF) or ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24)
    }
}
