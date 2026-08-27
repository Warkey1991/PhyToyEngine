package com.phytoy.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class CameraChrome(context: Context) : FrameLayout(context) {
    val metrics = TextView(context)
    val shutter: View
        get() = shutterControl
    val thumbnail = ImageView(context)
    val switchCamera: View
        get() = lensSwitchControl
    val flashMode: View
        get() = flashModeControl

    private val shutterControl = ShutterView(context)
    private val lensSwitchControl = LensSwitchView(context)
    private val focusIndicator = FocusIndicatorView(context)
    private val captureFormat = TextView(context)
    private val liveBadge = TextView(context)
    private val flashModeControl = TextView(context)
    private val exposureControl = TextView(context)
    private val zoomControl = TextView(context)
    private val message = TextView(context)
    private val flash = View(context)
    private val clearMessage = Runnable { message.visibility = INVISIBLE }
    private var cameraReady = false
    private var lensSwitchAvailable = false
    private var flashAvailable = false

    init {
        isClickable = false
        fitsSystemWindows = false
        metrics.id = R.id.engine_metrics
        shutterControl.id = R.id.shutter
        thumbnail.id = R.id.last_photo
        lensSwitchControl.id = R.id.switch_camera
        focusIndicator.id = R.id.focus_indicator
        flashModeControl.id = R.id.flash_mode
        exposureControl.id = R.id.exposure_value
        zoomControl.id = R.id.zoom_ratio
        addScrims()
        addTopBar()
        addBottomControls()
        addView(
            focusIndicator,
            LayoutParams(dp(76), dp(76)).apply { focusIndicator.visibility = INVISIBLE },
        )
        addMetricsPanel()

        flash.setBackgroundColor(Color.WHITE)
        flash.alpha = 0f
        addView(flash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
            insets
        }
    }

    fun setReady(ready: Boolean) {
        cameraReady = ready
        shutterControl.setReady(ready)
        lensSwitchControl.isEnabled = ready && lensSwitchAvailable
        lensSwitchControl.alpha = if (lensSwitchControl.isEnabled) 1f else 0.35f
        flashModeControl.isEnabled = ready && flashAvailable
        flashModeControl.alpha = if (flashModeControl.isEnabled) 1f else 0.35f
        liveBadge.text = context.getString(
            if (ready) R.string.live_badge else R.string.warming_badge
        )
        liveBadge.setTextColor(if (ready) ACCENT else Color.WHITE)
        if (ready) message.visibility = INVISIBLE
    }

    fun setCapturing() {
        removeCallbacks(clearMessage)
        shutterControl.setCapturing(true)
        lensSwitchControl.isEnabled = false
        lensSwitchControl.alpha = 0.35f
        flashModeControl.isEnabled = false
        flashModeControl.alpha = 0.35f
        message.text = context.getString(R.string.preparing_capture)
        message.visibility = VISIBLE
        message.animate().alpha(1f).setDuration(120L).start()
        shutterControl.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun updateCaptureMessage(text: CharSequence) {
        removeCallbacks(clearMessage)
        message.text = text
        message.visibility = VISIBLE
        message.animate().cancel()
        message.alpha = 1f
    }

    fun finishCapture() {
        shutterControl.setCapturing(false)
        lensSwitchControl.isEnabled = cameraReady && lensSwitchAvailable
        lensSwitchControl.alpha = if (lensSwitchControl.isEnabled) 1f else 0.35f
        flashModeControl.isEnabled = cameraReady && flashAvailable
        flashModeControl.alpha = if (flashModeControl.isEnabled) 1f else 0.35f
    }

    fun setLensSwitchAvailable(available: Boolean) {
        lensSwitchAvailable = available
        lensSwitchControl.isEnabled = available && cameraReady
        lensSwitchControl.alpha = if (lensSwitchControl.isEnabled) 1f else 0.35f
    }

    fun setLensFacing(front: Boolean) {
        lensSwitchControl.setFrontFacing(front)
        lensSwitchControl.contentDescription = context.getString(
            if (front) R.string.switch_to_back_camera else R.string.switch_to_front_camera
        )
    }

    fun setFlashAvailable(available: Boolean) {
        flashAvailable = available
        flashModeControl.isEnabled = available && cameraReady
        flashModeControl.alpha = if (flashModeControl.isEnabled) 1f else 0.35f
        if (!available) flashModeControl.text = context.getString(R.string.flash_unavailable)
    }

    fun setFlashMode(mode: CameraFlashMode) {
        if (!flashAvailable) {
            flashModeControl.text = context.getString(R.string.flash_unavailable)
            return
        }
        flashModeControl.text = context.getString(
            when (mode) {
                CameraFlashMode.OFF -> R.string.flash_off
                CameraFlashMode.AUTO -> R.string.flash_auto
                CameraFlashMode.ON -> R.string.flash_on
            }
        )
    }

    fun setExposureCompensation(ev: Float, supported: Boolean) {
        exposureControl.text = context.getString(R.string.exposure_value, ev)
        exposureControl.alpha = if (supported) 1f else 0.35f
    }

    fun setZoomRatio(ratio: Float) {
        zoomControl.text = context.getString(R.string.zoom_ratio, ratio)
    }

    fun setCaptureSize(width: Int, height: Int) {
        val megapixels = width.toLong() * height / 1_000_000.0
        captureFormat.text = context.getString(R.string.capture_format, megapixels)
    }

    fun showFocusIndicator(x: Float, y: Float) {
        focusIndicator.animate().cancel()
        focusIndicator.setSuccess(null)
        focusIndicator.x = x - focusIndicator.layoutParams.width / 2f
        focusIndicator.y = y - focusIndicator.layoutParams.height / 2f
        focusIndicator.alpha = 1f
        focusIndicator.scaleX = 1.35f
        focusIndicator.scaleY = 1.35f
        focusIndicator.visibility = VISIBLE
        focusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        focusIndicator.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun completeFocus(success: Boolean) {
        focusIndicator.setSuccess(success)
        focusIndicator.animate().alpha(0.72f).setDuration(180L).start()
    }

    fun clearFocusIndicator() {
        focusIndicator.animate().cancel()
        focusIndicator.animate().alpha(0f).setDuration(180L).withEndAction {
            focusIndicator.visibility = INVISIBLE
        }.start()
    }

    fun showMessage(text: CharSequence, temporary: Boolean = false) {
        removeCallbacks(clearMessage)
        message.alpha = 1f
        message.text = text
        message.visibility = VISIBLE
        if (temporary) {
            postDelayed(clearMessage, MESSAGE_DURATION_MILLIS)
        }
    }

    fun showThumbnail(bitmap: Bitmap) {
        thumbnail.setImageBitmap(bitmap)
        thumbnail.imageAlpha = 255
    }

    fun flashCapture() {
        flash.animate().cancel()
        flash.alpha = 0.82f
        flash.animate().alpha(0f).setDuration(240L).start()
    }

    private fun addScrims() {
        addView(
            View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xB8000000.toInt(), Color.TRANSPARENT),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(190), Gravity.TOP),
        )
        addView(
            View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xF2000000.toInt(), 0x8A000000.toInt(), Color.TRANSPARENT),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(310), Gravity.BOTTOM),
        )
    }

    private fun addTopBar() {
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.debug_toggle_description)
        }
        title.addView(TextView(context).apply {
            text = context.getString(R.string.product_wordmark)
            setTextColor(0xBFFFFFFF.toInt())
            textSize = 10f
            letterSpacing = 0.22f
            typeface = Typeface.create("sans", Typeface.BOLD)
        })
        title.addView(TextView(context).apply {
            text = context.getString(R.string.style_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            letterSpacing = 0.03f
            typeface = Typeface.create("sans", Typeface.BOLD)
        })
        title.setOnLongClickListener {
            metrics.visibility = if (metrics.visibility == VISIBLE) GONE else VISIBLE
            true
        }
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, dp(62), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(22)
            topMargin = dp(18)
        })

        liveBadge.apply {
            text = context.getString(R.string.warming_badge)
            setTextColor(Color.WHITE)
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans", Typeface.BOLD)
            background = rounded(0x75000000, dp(18).toFloat(), 0x42FFFFFF, dp(1))
        }
        addView(liveBadge, LayoutParams(dp(78), dp(34), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(20)
            topMargin = dp(20)
        })

        flashModeControl.apply {
            text = context.getString(R.string.flash_unavailable)
            contentDescription = context.getString(R.string.flash_control_description)
            setTextColor(Color.WHITE)
            textSize = 9f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans", Typeface.BOLD)
            background = rounded(0x75000000, dp(16).toFloat(), 0x42FFFFFF, dp(1))
            isClickable = true
            isFocusable = true
        }
        addView(
            flashModeControl,
            LayoutParams(dp(88), dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(72)
            },
        )
    }

    private fun addBottomControls() {
        val mode = TextView(context).apply {
            text = context.getString(R.string.photo_mode)
            setTextColor(ACCENT)
            textSize = 12f
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        addView(mode, LayoutParams(dp(120), dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(143)
        })

        zoomControl.apply {
            text = context.getString(R.string.zoom_ratio, 1.0)
            contentDescription = context.getString(R.string.zoom_control_description)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = rounded(0x75000000, dp(16).toFloat(), 0x42FFFFFF, dp(1))
        }
        addView(
            zoomControl,
            LayoutParams(dp(64), dp(32), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(176)
            },
        )

        exposureControl.apply {
            text = context.getString(R.string.exposure_value, 0.0)
            contentDescription = context.getString(R.string.exposure_control_description)
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = rounded(0x75000000, dp(14).toFloat(), 0x42FFFFFF, dp(1))
        }
        addView(
            exposureControl,
            LayoutParams(dp(68), dp(30), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(18)
                bottomMargin = dp(126)
            },
        )

        message.apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(0x8F000000.toInt(), dp(18).toFloat())
            visibility = INVISIBLE
        }
        addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(216)
        })

        shutterControl.contentDescription = context.getString(R.string.shutter_description)
        addView(shutterControl, LayoutParams(dp(84), dp(84), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(43)
        })

        thumbnail.apply {
            contentDescription = context.getString(R.string.gallery_description)
            scaleType = ImageView.ScaleType.CENTER_CROP
            imageAlpha = 110
            setImageResource(R.drawable.ic_gallery_placeholder)
            background = rounded(0x5CFFFFFF, dp(12).toFloat(), 0x6EFFFFFF, dp(1))
            clipToOutline = true
            isClickable = true
            isFocusable = true
        }
        addView(thumbnail, LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(24)
            bottomMargin = dp(56)
        })

        captureFormat.apply {
            text = context.getString(R.string.capture_format, 0.0)
            setTextColor(0xBFFFFFFF.toInt())
            textSize = 10f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        addView(captureFormat, LayoutParams(dp(78), dp(42), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(14)
            bottomMargin = dp(124)
        })

        lensSwitchControl.apply {
            contentDescription = context.getString(R.string.switch_to_front_camera)
            isClickable = true
            isFocusable = true
        }
        addView(lensSwitchControl, LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(24)
            bottomMargin = dp(56)
        })
    }

    private fun addMetricsPanel() {
        metrics.apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            text = context.getString(R.string.metrics_waiting)
            background = rounded(0xD9000000.toInt(), dp(14).toFloat(), 0x40FFFFFF, dp(1))
            visibility = GONE
        }
        addView(metrics, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270), Gravity.BOTTOM).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(235)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 0,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

    private class FocusIndicatorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2f
            strokeCap = Paint.Cap.SQUARE
        }
        private var success: Boolean? = null

        fun setSuccess(value: Boolean?) {
            success = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = when (success) {
                true -> 0xFF8BE28B.toInt()
                false -> 0xFFFF786E.toInt()
                null -> ACCENT
            }
            val inset = width * 0.14f
            val edge = width * 0.24f
            val right = width - inset
            val bottom = height - inset
            canvas.drawLine(inset, inset, inset + edge, inset, paint)
            canvas.drawLine(inset, inset, inset, inset + edge, paint)
            canvas.drawLine(right, inset, right - edge, inset, paint)
            canvas.drawLine(right, inset, right, inset + edge, paint)
            canvas.drawLine(inset, bottom, inset + edge, bottom, paint)
            canvas.drawLine(inset, bottom, inset, bottom - edge, paint)
            canvas.drawLine(right, bottom, right - edge, bottom, paint)
            canvas.drawLine(right, bottom, right, bottom - edge, paint)
        }
    }

    private class LensSwitchView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = RectF()
        private var frontFacing = false

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun setFrontFacing(value: Boolean) {
            frontFacing = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) * 0.37f
            paint.color = 0xA8000000.toInt()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(centerX, centerY, minOf(width, height) * 0.48f, paint)
            paint.color = 0xD9FFFFFF.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * density
            paint.strokeCap = Paint.Cap.ROUND
            arc.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            canvas.drawArc(arc, 205f, 205f, false, paint)
            canvas.drawArc(arc, 25f, 205f, false, paint)
            paint.style = Paint.Style.FILL
            val arrow = 4.2f * density
            canvas.drawCircle(centerX - radius * 0.84f, centerY - radius * 0.54f, arrow, paint)
            canvas.drawCircle(centerX + radius * 0.84f, centerY + radius * 0.54f, arrow, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans", Typeface.BOLD)
            paint.textSize = 11f * density
            canvas.drawText(if (frontFacing) "F" else "R", centerX, centerY + 4f * density, paint)
        }
    }

    private class ShutterView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var ready = false
        private var capturing = false

        init {
            isClickable = true
            isFocusable = true
        }

        fun setReady(value: Boolean) {
            ready = value
            isEnabled = value
            alpha = if (value) 1f else 0.48f
            invalidate()
        }

        fun setCapturing(value: Boolean) {
            capturing = value
            if (value) {
                isEnabled = false
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(100L).start()
            } else {
                animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) / 2f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.075f
            paint.color = Color.WHITE
            canvas.drawCircle(centerX, centerY, radius * 0.91f, paint)

            paint.style = Paint.Style.FILL
            paint.color = if (capturing) ACCENT else Color.WHITE
            canvas.drawCircle(centerX, centerY, radius * if (capturing) 0.58f else 0.72f, paint)

            if (ready && !capturing) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.025f
                paint.color = 0x55000000
                canvas.drawCircle(centerX, centerY, radius * 0.70f, paint)
            }
        }
    }

    companion object {
        private const val ACCENT = 0xFFF2B84B.toInt()
        private const val MESSAGE_DURATION_MILLIS = 2_400L
    }
}
