package com.anubhav.app.ui.customer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.anubhav.app.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scale of the range bar. The normal band takes the middle half of the track, so a result
 * a little outside it still has room. A result further out stretches that side until it
 * sits a tenth in from the end — down to a floor where the band would get too thin to
 * read; past that the pill rests on the end of the track.
 */
class RangeBarScale private constructor(
    val low: Double,
    val high: Double,
    val spanLow: Double,
    val spanHigh: Double,
) {
    /** Fraction 0…1 across the track for [v], held inside the track. */
    fun fraction(v: Double): Double = ((v - spanLow) / (spanHigh - spanLow)).coerceIn(0.0, 1.0)

    companion object {
        private const val EDGE = 0.1
        private const val MIN_BAND = 0.15

        fun of(low: Double, high: Double, value: Double?): RangeBarScale {
            val lo = min(low, high)
            val hi = if (abs(high - low) < 1e-9) lo + 1 else max(low, high)
            val band = hi - lo
            var spanLow = lo - band / 2
            var spanHigh = hi + band / 2
            if (value != null) {
                val maxSpan = band / MIN_BAND
                val frac = (value - spanLow) / (spanHigh - spanLow)
                if (frac < EDGE) {
                    spanLow = max((value - EDGE * spanHigh) / (1 - EDGE), spanHigh - maxSpan)
                } else if (frac > 1 - EDGE) {
                    spanHigh = min((value - EDGE * spanLow) / (1 - EDGE), spanLow + maxSpan)
                }
            }
            return RangeBarScale(lo, hi, spanLow, spanHigh)
        }

        /** A number as a lab prints it: up to three decimals, no trailing zeros ("4", "3.7", "0.35"). */
        fun tick(v: Double): String =
            String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    }
}

/**
 * "Where does my result sit" slider for one lab parameter, drawn the way patient portals
 * show a result: a flat track that is green across the normal range and yellow either
 * side, the two reference ends printed under their boundaries, and the patient's value in
 * a pill that points down at its place on the track. The pill is green inside the range
 * and dark gold outside it, so position and colour say the same thing.
 */
class RangeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val metrics = resources.displayMetrics
    private fun dp(v: Float) = v * metrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    private var scale = RangeBarScale.of(0.0, 1.0, null)
    private var value: Double? = null
    private var valueLabel = ""

    // 0 → 1 while the pill slides from the start of the track to the value.
    private var settle = 1f
    private var settled = false
    private var animator: ValueAnimator? = null

    private val trackOutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EBD558") }
    private val trackEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8AE2E"); style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val trackInPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4BA34F") }
    private val pillInColor = Color.parseColor("#3D9A47")
    private val pillOutColor = Color.parseColor("#9C8522")
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = sp(15f); typeface = Typeface.DEFAULT_BOLD
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary); textSize = sp(13f)
    }
    private val pointer = Path()
    private val rect = RectF()

    private val pillH get() = pillTextPaint.descent() - pillTextPaint.ascent() + dp(8f)
    // With no numeric result there is no pill, so the track moves up into its place.
    private val trackTop get() = if (value == null) dp(1f) else pillH + dp(2f) + POINTER_H * metrics.density + dp(3f)
    private val trackH get() = dp(10f)

    /**
     * [valueLabel] is the result as the lab printed it ("5.52"); without it the value is
     * formatted here. A null [value] draws the track and its ends with no pill.
     */
    fun setRange(low: Double, high: Double, value: Double?, valueLabel: String? = null) {
        scale = RangeBarScale.of(low, high, value)
        this.value = value
        this.valueLabel = valueLabel ?: value?.let(RangeBarScale::tick).orEmpty()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ticks = dp(6f) + tickPaint.descent() - tickPaint.ascent()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            (trackTop + trackH + ticks + dp(1f)).toInt(),
        )
    }

    // Rows sit in accordions that start collapsed, so the slide plays when the bar is
    // first actually shown rather than when it is attached.
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible || settled || value == null) return
        settled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return
        settle = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { settle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        settle = 1f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = trackEdgePaint.strokeWidth / 2
        val left = inset
        val right = width - inset
        if (right <= left) return
        val top = trackTop
        val bottom = top + trackH
        fun x(v: Double) = left + scale.fraction(v).toFloat() * (right - left)
        val xLow = x(scale.low)
        val xHigh = x(scale.high)

        // Yellow end to end with its darker edge, then the green normal band laid over it.
        canvas.drawRect(left, top, right, bottom, trackOutPaint)
        canvas.drawRect(left, top, right, bottom, trackEdgePaint)
        canvas.drawRect(xLow, top - inset, xHigh, bottom + inset, trackInPaint)

        // Reference ends under their boundaries; on a stretched scale they step apart
        // rather than print over each other, and stay inside the view.
        val lo = RangeBarScale.tick(scale.low)
        val hi = RangeBarScale.tick(scale.high)
        val wl = tickPaint.measureText(lo)
        val wh = tickPaint.measureText(hi)
        val gap = dp(8f)
        var cl = xLow
        var ch = xHigh
        if (ch - cl < (wl + wh) / 2 + gap) {
            cl = (xLow + xHigh) / 2 - gap / 2 - wl / 2
            ch = (xLow + xHigh) / 2 + gap / 2 + wh / 2
        }
        cl = max(cl, wl / 2)
        ch = max(min(ch, width - wh / 2), cl + (wl + wh) / 2 + gap)
        if (ch > width - wh / 2) {
            ch = width - wh / 2
            cl = min(cl, ch - (wl + wh) / 2 - gap)
        }
        val tickY = bottom + dp(6f) - tickPaint.ascent()
        canvas.drawText(lo, cl - wl / 2, tickY, tickPaint)
        canvas.drawText(hi, ch - wh / 2, tickY, tickPaint)

        val v = value ?: return
        val cx = left + (x(v) - left) * settle
        val pillW = min(pillTextPaint.measureText(valueLabel) + dp(24f), width.toFloat())
        val pillL = (cx - pillW / 2).coerceIn(0f, width - pillW)
        pillPaint.color = if (v < scale.low || v > scale.high) pillOutColor else pillInColor
        rect.set(pillL, 0f, pillL + pillW, pillH)
        canvas.drawRoundRect(rect, pillH / 2, pillH / 2, pillPaint)
        canvas.drawText(
            valueLabel,
            pillL + (pillW - pillTextPaint.measureText(valueLabel)) / 2,
            pillH / 2 - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2,
            pillTextPaint,
        )

        // The pointer stays on the value even when the pill has been nudged off an edge.
        val half = POINTER_W * metrics.density / 2
        val flatL = pillL + pillH / 2
        val flatR = pillL + pillW - pillH / 2
        val px = if (flatL < flatR) cx.coerceIn(flatL, flatR) else pillL + pillW / 2
        val py = pillH + dp(2f)
        pointer.rewind()
        pointer.moveTo(px - half, py)
        pointer.lineTo(px + half, py)
        pointer.lineTo(px, py + POINTER_H * metrics.density)
        pointer.close()
        canvas.drawPath(pointer, pointerPaint)
    }

    private companion object {
        const val POINTER_W = 13f
        const val POINTER_H = 7f
    }
}
