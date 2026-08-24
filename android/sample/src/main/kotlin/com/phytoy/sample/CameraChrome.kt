package com.phytoy.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private val shutterControl = ShutterView(context)
    private val liveBadge = TextView(context)
    private val message = TextView(context)
    private val flash = View(context)
    private val clearMessage = Runnable { message.visibility = INVISIBLE }

    init {
        isClickable = false
        fitsSystemWindows = false
        metrics.id = R.id.engine_metrics
        shutterControl.id = R.id.shutter
        thumbnail.id = R.id.last_photo
        addScrims()
        addTopBar()
        addBottomControls()
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
        shutterControl.setReady(ready)
        liveBadge.text = context.getString(
            if (ready) R.string.live_badge else R.string.warming_badge
        )
        liveBadge.setTextColor(if (ready) ACCENT else Color.WHITE)
        if (ready) message.visibility = INVISIBLE
    }

    fun setCapturing() {
        removeCallbacks(clearMessage)
        shutterControl.setCapturing(true)
        message.text = context.getString(R.string.processing_photo)
        message.visibility = VISIBLE
        message.animate().alpha(1f).setDuration(120L).start()
        shutterControl.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun finishCapture() {
        shutterControl.setCapturing(false)
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

        message.apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(0x8F000000.toInt(), dp(18).toFloat())
            visibility = INVISIBLE
        }
        addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(183)
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

        val format = TextView(context).apply {
            text = context.getString(R.string.capture_format)
            setTextColor(0xBFFFFFFF.toInt())
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        addView(format, LayoutParams(dp(64), dp(58), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(22)
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
