package com.siddhantdalal.bikelink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * A hand-drawn stand-in for the live map on the ride screen: streets, a route, the rider and the destination pin.
 * Google's navigation map takes its place once navigation is connected. Positions are fractions of the view, so it
 * fits any screen, and the route sits in the band left visible between the turn card and the bottom dock.
 */
class RoutePlaceholderView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val accent = context.getColor(R.color.accent)
    private val majorStreet = streetPaint(12f, "#141D19")
    private val minorStreet = streetPaint(6f, "#0F1613")
    private val routeGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 22 * dp
        color = accent.withAlpha(0.18f)
    }
    private val routeLine = Paint(routeGlow).apply {
        strokeWidth = 9 * dp
        color = accent
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent.withAlpha(0.25f) }
    private val route = Path()
    private val pin = requireNotNull(context.getDrawable(R.drawable.ic_location_on)).mutate().apply {
        setTint(context.getColor(R.color.status_warning))
    }
    private val rider = requireNotNull(context.getDrawable(R.drawable.ic_navigation)).mutate().apply {
        setTint(Color.WHITE)
    }

    /** Where the rider, the turn and the destination sit, as fractions of the view. */
    private class Scene(
        val riderX: Float, val riderY: Float,
        val turnY: Float, val bendX: Float,
        val pinX: Float, val pinY: Float,
    )

    private var scene = PORTRAIT

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // In landscape the trip and dock take the right-hand side, so the route stays on the left.
        scene = if (w > h) LANDSCAPE else PORTRAIT
        val s = scene
        route.reset()
        route.moveTo(w * s.riderX, h * s.riderY)
        route.lineTo(w * s.riderX, h * (s.turnY + 0.07f))
        route.cubicTo(w * s.riderX, h * s.turnY, w * s.riderX, h * s.turnY, w * (s.riderX + 0.08f), h * s.turnY)
        route.lineTo(w * s.bendX, h * s.turnY)
        route.cubicTo(w * s.pinX, h * s.turnY, w * s.pinX, h * s.turnY, w * s.pinX, h * (s.turnY - 0.07f))
        route.lineTo(w * s.pinX, h * s.pinY)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = scene
        canvas.drawColor(Color.parseColor("#090D0C"))
        for (y in floatArrayOf(0.15f, 0.36f, 0.86f)) canvas.drawLine(0f, h * y, w, h * y, minorStreet)
        for (x in floatArrayOf(0.12f, 0.62f, 0.95f)) canvas.drawLine(w * x, 0f, w * x, h, minorStreet)
        for (y in floatArrayOf(s.pinY, s.turnY, 0.74f)) canvas.drawLine(0f, h * y, w, h * y, majorStreet)
        for (x in floatArrayOf(0.30f, s.riderX, s.pinX)) canvas.drawLine(w * x, 0f, w * x, h, majorStreet)

        canvas.drawPath(route, routeGlow)
        canvas.drawPath(route, routeLine)

        val pinSize = 44 * dp
        val pinX = w * s.pinX
        val pinY = h * s.pinY
        pin.setBounds((pinX - pinSize / 2).toInt(), (pinY - pinSize).toInt(), (pinX + pinSize / 2).toInt(), pinY.toInt())
        pin.draw(canvas)

        val riderX = w * s.riderX
        val riderY = h * s.riderY
        canvas.drawCircle(riderX, riderY, 24 * dp, halo)
        val riderHalf = 16 * dp
        rider.setBounds((riderX - riderHalf).toInt(), (riderY - riderHalf).toInt(), (riderX + riderHalf).toInt(), (riderY + riderHalf).toInt())
        rider.draw(canvas)
    }

    private fun streetPaint(widthDp: Float, color: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = widthDp * dp
        this.color = Color.parseColor(color)
    }

    private companion object {
        // Portrait: the visible band is between the turn card (top) and the trip and dock (bottom).
        val PORTRAIT = Scene(riderX = 0.40f, riderY = 0.56f, turnY = 0.45f, bendX = 0.70f, pinX = 0.80f, pinY = 0.33f)

        // Landscape: the visible area is on the left, below the turn card.
        val LANDSCAPE = Scene(riderX = 0.10f, riderY = 0.88f, turnY = 0.74f, bendX = 0.30f, pinX = 0.40f, pinY = 0.62f)
    }
}
