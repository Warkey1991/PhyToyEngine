package com.phytoy.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** A portrait 3:4 viewport whose visible boundary is the saved still-photo boundary. */
internal class CaptureViewport(context: Context) : FrameLayout(context) {
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
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
        val desiredHeight = (availableWidth / CAPTURE_ASPECT).roundToInt()
        val measuredHeight = desiredHeight.coerceAtMost(availableHeight)
        val measuredWidth = if (measuredHeight == desiredHeight) {
            availableWidth
        } else {
            (measuredHeight * CAPTURE_ASPECT).roundToInt()
        }
        val childWidth = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(childWidth, childHeight)
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = borderPaint.strokeWidth / 2f
        val right = width - inset
        val bottom = height - inset
        canvas.drawRect(inset, inset, right, bottom, borderPaint)

        val corner = resources.displayMetrics.density * 18f
        canvas.drawLine(inset, inset, inset + corner, inset, cornerPaint)
        canvas.drawLine(inset, inset, inset, inset + corner, cornerPaint)
        canvas.drawLine(right, inset, right - corner, inset, cornerPaint)
        canvas.drawLine(right, inset, right, inset + corner, cornerPaint)
        canvas.drawLine(inset, bottom, inset + corner, bottom, cornerPaint)
        canvas.drawLine(inset, bottom, inset, bottom - corner, cornerPaint)
        canvas.drawLine(right, bottom, right - corner, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - corner, cornerPaint)
    }

    companion object {
        private const val CAPTURE_ASPECT = 3f / 4f
    }
}
