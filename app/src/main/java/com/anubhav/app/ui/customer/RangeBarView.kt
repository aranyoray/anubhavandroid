package com.anubhav.app.ui.customer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A single-row "where does my result sit" bar for one lab parameter.
 *
 * The track runs low → amber, normal → green, high → amber/red, and a marker is dropped
 * at the patient's value. Everything is derived from the reference range, so the scale
 * extends half a normal-band past each end: a result that is far out still lands inside
 * the view instead of being clamped onto the edge.
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

    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0B429") }
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E9E5B") }
    private val highPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D64545") }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D2939") }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#667085"); textSize = dp(10f)
    }

    /** Scale ends, half a normal band outside the reference range. */
    private val spanLow get() = low - (high - low) * 0.5f
    private val spanHigh get() = high + (high - low) * 0.5f

    fun setRange(low: Float, high: Float, value: Float?) {
        // A degenerate range (low == high, or inverted) has no band to colour.
        this.low = min(low, high)
        this.high = if (abs(high - low) < 1e-6f) low + 1f else max(low, high)
        this.value = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(34f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val left = dp(2f)
        val right = width - dp(2f)
        if (right <= left) return
        val trackTop = dp(4f)
        val trackBottom = trackTop + dp(8f)
        val radius = dp(4f)

        fun x(v: Float) = left + (v - spanLow) / (spanHigh - spanLow) * (right - left)

        // Three zones: below range, in range, above range.
        canvas.drawRoundRect(RectF(left, trackTop, x(low), trackBottom), radius, radius, lowPaint)
        canvas.drawRect(RectF(x(low), trackTop, x(high), trackBottom), normalPaint)
        canvas.drawRoundRect(RectF(x(high), trackTop, right, trackBottom), radius, radius, highPaint)

        canvas.drawText(trim(low), x(low) - labelPaint.measureText(trim(low)) / 2, trackBottom + dp(14f), labelPaint)
        canvas.drawText(trim(high), x(high) - labelPaint.measureText(trim(high)) / 2, trackBottom + dp(14f), labelPaint)

        val v = value ?: return
        val cx = x(v).coerceIn(left + radius, right - radius)
        val cy = (trackTop + trackBottom) / 2f
        canvas.drawCircle(cx, cy, dp(7f), markerRingPaint)
        canvas.drawCircle(cx, cy, dp(5f), markerPaint)
    }

    private fun trim(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)
}
