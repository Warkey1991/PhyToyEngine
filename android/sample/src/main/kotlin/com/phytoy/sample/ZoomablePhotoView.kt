package com.phytoy.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView

/** A bounded photo transform. Opening another photo always restores the full composition. */
internal class ZoomablePhotoView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var fitScale = 1f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val zoom get() = if (fitScale > 0) scale / fitScale else 1f
    var onZoomChanged: ((Boolean) -> Unit)? = null

    private val scaling = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })
    private val gestures = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean = performClick()
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (zoom > 1.05f) resetZoom() else zoomTo(2.5f, event.x, event.y)
                return true
            }
            override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaling.isInProgress || current.pointerCount > 1 || zoom <= 1f) return false
                offsetX -= dx
                offsetY -= dy
                applyTransform()
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (bitmapWidth <= 0) return
                if (zoom < MAX_ZOOM) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    R.id.review_zoom_in, context.getString(R.string.photo_zoom_in)))
                if (zoom > 1f) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        R.id.review_zoom_out, context.getString(R.string.photo_zoom_out)))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        R.id.review_zoom_reset, context.getString(R.string.photo_zoom_reset)))
                }
            }
            override fun performAccessibilityAction(host: android.view.View, action: Int, arguments: Bundle?): Boolean {
                if (bitmapWidth <= 0) return super.performAccessibilityAction(host, action, arguments)
                when (action) {
                    R.id.review_zoom_in -> zoomTo(zoom * 1.5f)
                    R.id.review_zoom_out -> zoomTo(zoom / 1.5f)
                    R.id.review_zoom_reset -> resetZoom()
                    else -> return super.performAccessibilityAction(host, action, arguments)
                }
                announceForAccessibility(context.getString(R.string.photo_zoom_state, zoom))
                return true
            }
        }
    }

    override fun setImageBitmap(bitmap: Bitmap?) {
        bitmapWidth = bitmap?.width ?: 0
        bitmapHeight = bitmap?.height ?: 0
        super.setImageBitmap(bitmap)
        resetZoom()
    }

    fun resetZoom() {
        if (bitmapWidth > 0 && bitmapHeight > 0 && width > 0 && height > 0) {
            fitScale = minOf(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight)
            scale = fitScale
            offsetX = (width - bitmapWidth * scale) / 2f
            offsetY = (height - bitmapHeight * scale) / 2f
        } else {
            fitScale = 1f
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
        applyTransform()
    }

    private fun zoomTo(relative: Float, focalX: Float = width / 2f, focalY: Float = height / 2f) {
        if (bitmapWidth <= 0 || width <= 0 || height <= 0) return
        val target = fitScale * relative.coerceIn(1f, MAX_ZOOM)
        val factor = target / scale
        offsetX = focalX - (focalX - offsetX) * factor
        offsetY = focalY - (focalY - offsetY) * factor
        scale = target
        applyTransform()
    }

    private fun applyTransform() {
        val imageWidth = bitmapWidth * scale
        val imageHeight = bitmapHeight * scale
        offsetX = if (imageWidth <= width) (width - imageWidth) / 2f else offsetX.coerceIn(width - imageWidth, 0f)
        offsetY = if (imageHeight <= height) (height - imageHeight) / 2f else offsetY.coerceIn(height - imageHeight, 0f)
        transform.setScale(scale, scale)
        transform.postTranslate(offsetX, offsetY)
        imageMatrix = transform
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = context.getString(R.string.photo_zoom_state, zoom)
        }
        onZoomChanged?.invoke(zoom > 1.05f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmapWidth <= 0) return false
        scaling.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object { private const val MAX_ZOOM = 4f }
}
