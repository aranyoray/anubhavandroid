package com.anubhav.app.ui.customer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A small line chart for one blood parameter over time. Original Canvas drawing:
 * a shaded normal band (ref low..high), a value line with dots, and the latest
 * reading highlighted. Not a third-party chart library.
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Point(val label: String, val value: Float)

    private var points: List<Point> = emptyList()
    private var refLow: Float? = null
    private var refHigh: Float? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#0B6FB4")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B6FB4") }
    private val lastDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C2410C") }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E2B9348") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f); color = Color.parseColor("#E2E6EC") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#667085"); textSize = dp(10f) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D2939"); textSize = dp(10f); isFakeBoldText = true }

    fun setData(points: List<Point>, refLow: Float?, refHigh: Float?) {
        this.points = points
        this.refLow = refLow
        this.refHigh = refHigh
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(180f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val left = dp(36f); val right = width - dp(20f)
        val top = dp(14f); val bottom = height - dp(26f)

        val values = points.map { it.value }
        var lo = min(values.min(), refLow ?: values.min())
        var hi = max(values.max(), refHigh ?: values.max())
        if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
        val pad = (hi - lo) * 0.12f
        lo -= pad; hi += pad

        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        fun x(i: Int) = left + i.toFloat() / (points.size - 1) * (right - left)

        // Normal band
        val rl = refLow; val rh = refHigh
        if (rl != null && rh != null) {
            canvas.drawRect(left, y(rh), right, y(rl), bandPaint)
        }
        // Y gridlines + labels (lo, mid, hi)
        for (frac in listOf(0f, 0.5f, 1f)) {
            val v = lo + (hi - lo) * frac
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, gridPaint)
            canvas.drawText(trim(v), dp(2f), yy + dp(3f), textPaint)
        }
        // Line
        val path = Path()
        points.forEachIndexed { i, p ->
            val px = x(i); val py = y(p.value)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, linePaint)
        // Dots + x labels
        points.forEachIndexed { i, p ->
            val px = x(i); val py = y(p.value)
            val last = i == points.size - 1
            canvas.drawCircle(px, py, dp(if (last) 4.5f else 3.5f), if (last) lastDotPaint else dotPaint)
            val lbl = p.label
            val lw = textPaint.measureText(lbl)
            val lx = min(max(px - lw / 2, left), right - lw)
            canvas.drawText(lbl, lx, bottom + dp(16f), textPaint)
        }
        // Latest value label, centred on its dot but kept inside the plot area so the
        // rightmost reading is never clipped by the view edge.
        val last = points.last()
        val lastLabel = trim(last.value)
        val lastWidth = valuePaint.measureText(lastLabel)
        val lastX = (x(points.size - 1) - lastWidth / 2).coerceIn(left, right - lastWidth)
        canvas.drawText(lastLabel, lastX, y(last.value) - dp(9f), valuePaint)
    }

    private fun trim(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)
}
