package com.anubhav.app.ui.customer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A minimalist "where does my result sit" slider for one lab parameter.
 *
 * One hairline track carries a soft colorimetric wash — amber (low) → green (in range) →
 * amber/red (high) — so the eye reads health by hue before it reads a number. The normal
 * band is marked by two faint gridlines, and the patient's value sits under a floating
 * pill coloured by the zone it lands in (green = normal, amber = borderline, red = out).
 *
 * The scale extends half a normal band past each reference end, so a far-out result still
 * lands inside the view instead of being clamped flat onto the edge.
 */
class RangeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var low = 0f
    private var high = 1f
    private var value: Float? = null

    // Soft colorimetric stops. Muted on purpose: the slider should whisper, not alarm.
    private val cAmber = Color.parseColor("#E8A13A")
    private val cGreen = Color.parseColor("#2FA96A")
    private val cRed = Color.parseColor("#DE5B50")
    private val cInk = Color.parseColor("#1D2939")
    private val cMuted = Color.parseColor("#98A2B3")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF"); strokeWidth = dp(1.5f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(11f); typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val endLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cMuted; textSize = dp(10f)
    }

    private val spanLow get() = low - (high - low) * 0.5f
    private val spanHigh get() = high + (high - low) * 0.5f

    fun setRange(low: Float, high: Float, value: Float?) {
        this.low = min(low, high)
        this.high = if (abs(high - low) < 1e-6f) low + 1f else max(low, high)
        this.value = value
        invalidate()
    }

    /** The zone a value lands in, which colours both the thumb and its pill. */
    private fun zoneColor(v: Float): Int = when {
        v < low -> cAmber
        v > high -> cRed
        else -> cGreen
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Room for the floating pill (top), the track, and the end labels (bottom).
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(54f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val left = dp(3f)
        val right = width - dp(3f)
        if (right <= left) return
        val trackTop = dp(26f)
        val trackH = dp(7f)
        val trackBottom = trackTop + trackH
        val radius = trackH / 2f

        fun x(v: Float) = left + (v - spanLow) / (spanHigh - spanLow) * (right - left)

        // One continuous colorimetric wash, keyed to the real zone boundaries so the
        // green only covers the in-range span whatever the scale padding is.
        val lowFrac = ((x(low) - left) / (right - left)).coerceIn(0f, 1f)
        val highFrac = ((x(high) - left) / (right - left)).coerceIn(0f, 1f)
        trackPaint.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(cAmber, cGreen, cGreen, cRed),
            floatArrayOf(0f, lowFrac, highFrac, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(left, trackTop, right, trackBottom), radius, radius, trackPaint)

        // Faint gridlines at the normal-band edges — the "safe" window, shown without words.
        canvas.drawLine(x(low), trackTop, x(low), trackBottom, bandLinePaint)
        canvas.drawLine(x(high), trackTop, x(high), trackBottom, bandLinePaint)

        // Reference endpoints, small and quiet, under the band edges.
        val lo = trim(low); val hi = trim(high)
        canvas.drawText(lo, x(low) - endLabelPaint.measureText(lo) / 2, trackBottom + dp(15f), endLabelPaint)
        canvas.drawText(hi, x(high) - endLabelPaint.measureText(hi) / 2, trackBottom + dp(15f), endLabelPaint)

        val v = value ?: return
        val zone = zoneColor(v)
        val cx = x(v).coerceIn(left + radius, right - radius)
        val cy = (trackTop + trackBottom) / 2f

        // Thumb: a crisp capsule in the zone colour, ringed white so it reads on any zone.
        val tw = dp(5f); val th = trackH + dp(8f)
        thumbPaint.color = zone
        canvas.drawRoundRect(
            RectF(cx - tw / 2 - dp(1.5f), cy - th / 2 - dp(1.5f), cx + tw / 2 + dp(1.5f), cy + th / 2 + dp(1.5f)),
            tw, tw, thumbRingPaint,
        )
        canvas.drawRoundRect(RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2), tw / 2, tw / 2, thumbPaint)

        // Floating value pill above the thumb, same hue, kept inside the view bounds.
        val label = trim(v)
        val padH = dp(8f); val padV = dp(4f)
        val tw2 = pillTextPaint.measureText(label)
        val pillW = tw2 + padH * 2
        val pillH = pillTextPaint.textSize + padV * 2
        var pillL = cx - pillW / 2
        pillL = pillL.coerceIn(left, right - pillW)
        val pillTop = dp(1f)
        pillPaint.color = zone
        canvas.drawRoundRect(RectF(pillL, pillTop, pillL + pillW, pillTop + pillH), pillH / 2, pillH / 2, pillPaint)
        val ty = pillTop + pillH / 2 - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2
        canvas.drawText(label, pillL + padH, ty, pillTextPaint)
    }

    private fun trim(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)
}
