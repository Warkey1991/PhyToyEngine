package com.phytoy.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** A style-controlled viewport whose visible boundary is the saved still-photo boundary. */
internal class CaptureViewport(context: Context) : FrameLayout(context) {
    private var captureAspect = 3f / 4f
    private var availableFrameWidth = 0
    private var availableFrameHeight = 0
    private var gridEnabled = false
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x70FFFFFF
        strokeWidth = resources.displayMetrics.density * 0.75f
    }

    fun setGridEnabled(enabled: Boolean) {
        gridEnabled = enabled
        invalidate()
    }

    fun fittedWidth(aspect: Float): Int {
        val w = availableFrameWidth.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = availableFrameHeight.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        return minOf(w, (h * aspect).roundToInt())
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x24FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        strokeCap = Paint.Cap.SQUARE
    }

    init {
        setWillNotDraw(false)
        clipChildren = true
        setBackgroundColor(Color.BLACK)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        availableFrameWidth = availableWidth
        availableFrameHeight = availableHeight
        val desiredHeight = (availableWidth / captureAspect).roundToInt()
        val measuredHeight = desiredHeight.coerceAtMost(availableHeight)
        val measuredWidth = if (measuredHeight == desiredHeight) {
            availableWidth
        } else {
            (measuredHeight * captureAspect).roundToInt()
        }
        val childWidth = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(childWidth, childHeight)
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    fun setCaptureAspect(aspect: Float) {
        require(aspect > 0f)
        if (captureAspect == aspect) return
        captureAspect = aspect
        requestLayout()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (gridEnabled) {
            for (division in 1..2) {
                val x = width * division / 3f
                val y = height * division / 3f
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            }
        }
        val inset = borderPaint.strokeWidth / 2f
        val right = width - inset
        val bottom = height - inset
        canvas.drawRect(inset, inset, right, bottom, borderPaint)

        val corner = resources.displayMetrics.density * 10f
        canvas.drawLine(inset, inset, inset + corner, inset, cornerPaint)
        canvas.drawLine(inset, inset, inset, inset + corner, cornerPaint)
        canvas.drawLine(right, inset, right - corner, inset, cornerPaint)
        canvas.drawLine(right, inset, right, inset + corner, cornerPaint)
        canvas.drawLine(inset, bottom, inset + corner, bottom, cornerPaint)
        canvas.drawLine(inset, bottom, inset, bottom - corner, cornerPaint)
        canvas.drawLine(right, bottom, right - corner, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - corner, cornerPaint)
    }
}
