package com.phytoy.sample

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** An inline camera control. Values are always bounded by this camera's capabilities. */
internal class CameraAdjustmentPanel(context: Context) : FrameLayout(context) {
    private val content = FrameLayout(context).apply {
        setPadding(dp(4), dp(4), dp(4), 0)
        isClickable = true
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = if (resources.configuration.fontScale >= 1.5f) 16f else 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minimumHeight = dp(36)
        setPadding(dp(14), dp(4), dp(14), dp(4))
        background = GradientDrawable().apply { setColor(0xE60B0B0C.toInt()); cornerRadius = dp(24).toFloat() }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val slider = DirectionalSeekBar(context).apply {
        id = R.id.adjustment_slider
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(ToviTheme.TEXT)
        thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ToviTheme.TEXT); setSize(dp(12), dp(12)) }
        val track = GradientDrawable().apply { setColor(0x998A8A8A.toInt()); setSize(dp(2), dp(2)); cornerRadius = dp(1).toFloat() }
        val fill = ClipDrawable(GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(1).toFloat() }, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
            setLayerHeight(0, dp(2)); setLayerHeight(1, dp(2))
            setLayerGravity(0, Gravity.CENTER_VERTICAL); setLayerGravity(1, Gravity.CENTER_VERTICAL)
        }
    }
    private val exposureSun = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f
            val y = height / 2f
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, dp(5).toFloat(), paint)
            for (i in 0..7) {
                val angle = Math.PI * i / 4
                canvas.drawLine(x + (Math.cos(angle) * dp(8)).toFloat(), y + (Math.sin(angle) * dp(8)).toFloat(),
                    x + (Math.cos(angle) * dp(11)).toFloat(), y + (Math.sin(angle) * dp(11)).toFloat(), paint)
            }
        }
    }.apply { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
    }
    private val bodyScroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
    }
    private val actionButtons = mutableListOf<TextView>()
    private var actionColumns = 0
    private var arrangedActionCount = 0
    private var maximumPanelHeight = Int.MAX_VALUE
    private var onValue: ((Int) -> Unit)? = null
    val isVerticalExposure: Boolean get() = slider.vertical

    init {
        id = R.id.adjustment_panel
        visibility = GONE
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(exposureSun, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
            addView(slider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        }
        bodyScroll.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The title and slider can scroll in a very short window; Done stays visible.
        content.addView(bodyScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onValue?.invoke(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        slider.onVerticalProgress = { progress -> onValue?.invoke(progress) }
    }

    fun showExposure(index: Int, minimum: Int, maximum: Int, step: Float, onSelected: (Int) -> Unit) {
        if (minimum >= maximum || step <= 0f) return
        setSliderDirection(resources.configuration.fontScale < 1.5f && maximumPanelHeight >= dp(252))
        actions.removeAllViews()
        actionButtons.clear()
        actionColumns = 0
        val update = { value: Int ->
            title.text = String.format(Locale.getDefault(), "EV %+.1f", value * step)
            slider.contentDescription = title.text
        }
        onValue = null
        slider.max = maximum - minimum
        slider.progress = index.coerceIn(minimum, maximum) - minimum
        update(index)
        onValue = { progress ->
            val value = minimum + progress
            update(value)
            onSelected(value)
        }
        addAction(context.getString(R.string.camera_adjust_reset), R.id.adjustment_reset) {
            val reset = 0.coerceIn(minimum, maximum)
            slider.progress = reset - minimum
            update(reset)
            onSelected(reset)
        }
        addDoneAction()
        visibility = VISIBLE
    }

    fun showZoom(ratio: Float, minimum: Float, maximum: Float, onSelected: (Float) -> Unit) {
        if (minimum <= 0f || minimum >= maximum) return
        setSliderDirection(false)
        actions.removeAllViews()
        actionButtons.clear()
        actionColumns = 0
        val span = ln(maximum / minimum)
        fun fromProgress(progress: Int): Float = (minimum * exp(span * progress / 1000f)).coerceIn(minimum, maximum)
        fun toProgress(value: Float): Int = (ln(value.coerceIn(minimum, maximum) / minimum) / span * 1000f).roundToInt()
        val update = { value: Float ->
            title.text = String.format(Locale.getDefault(), "%.1f×", value)
            slider.contentDescription = title.text
        }
        onValue = null
        slider.max = 1000
        slider.progress = toProgress(ratio)
        update(ratio)
        onValue = { progress ->
            val value = fromProgress(progress)
            update(value)
            onSelected(value)
        }
        addAction(context.getString(R.string.camera_adjust_reset), R.id.adjustment_reset) {
            val reset = 1f.coerceIn(minimum, maximum)
            slider.progress = toProgress(reset)
            update(reset)
            onSelected(reset)
        }
        addDoneAction()
        visibility = VISIBLE
    }

    fun dismiss(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = GONE
        onValue = null
        return true
    }

    private fun addDoneAction() = addAction(context.getString(R.string.camera_adjust_done), R.id.adjustment_done) { dismiss() }

    private fun setSliderDirection(vertical: Boolean) {
        slider.vertical = vertical
        exposureSun.visibility = if (vertical) VISIBLE else GONE
        slider.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(if (vertical) 128 else 48))
        title.textSize = if (vertical) 13f else if (resources.configuration.fontScale >= 1.5f) 16f else 22f
        if (!vertical) slider.background = GradientDrawable().apply { setColor(0xB80B0B0C.toInt()); cornerRadius = dp(24).toFloat() }
        else slider.background = null
        requestLayout()
    }

    private fun addAction(label: String, viewId: Int = View.NO_ID, action: () -> Unit) {
        val button = TextView(context).apply {
            id = viewId
            text = label
            setTextColor(ToviTheme.TEXT)
            background = GradientDrawable().apply { setColor(0x990B0B0C.toInt()); cornerRadius = dp(20).toFloat() }
            textSize = 10f
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        actionButtons.add(button)
        requestLayout()
    }

    fun setMaximumPanelHeight(height: Int) {
        if (maximumPanelHeight == height) return
        maximumPanelHeight = height.coerceAtLeast(0)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - dp(8)).coerceAtLeast(0)
        val buttonWidth = actionButtons.maxOfOrNull { button ->
            (button.paint.measureText(button.text.toString()) + button.paddingLeft + button.paddingRight).roundToInt()
                .coerceAtLeast(dp(48))
        } ?: dp(48)
        val columns = minOf(actionButtons.size.coerceAtLeast(1), (availableWidth / buttonWidth.coerceAtLeast(1)).coerceAtLeast(1))
        if (columns != actionColumns || actionButtons.size != arrangedActionCount) {
            actions.removeAllViews()
            actionButtons.forEach { button -> (button.parent as? LinearLayout)?.removeView(button) }
            actionButtons.chunked(columns).forEach { buttons ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(48)
                }
                buttons.forEach { button ->
                    row.addView(button, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
                actions.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            actionColumns = columns
            arrangedActionCount = actionButtons.size
        }
        actions.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        (bodyScroll.layoutParams as LayoutParams).bottomMargin = actions.measuredHeight
        val parentLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(heightMeasureSpec)
        val limit = minOf(parentLimit, maximumPanelHeight)
        val limitedHeight = if (limit == Int.MAX_VALUE) heightMeasureSpec
            else MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, limitedHeight)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    /** Keep the native SeekBar range/keyboard accessibility while drawing exposure vertically. */
    private class DirectionalSeekBar(context: Context) : SeekBar(context) {
        var vertical = false
        var onVerticalProgress: ((Int) -> Unit)? = null
        private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeWidth = resources.displayMetrics.density }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!vertical) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            else {
                super.onMeasure(heightMeasureSpec, widthMeasureSpec)
                setMeasuredDimension(measuredHeight, measuredWidth)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (vertical) super.onSizeChanged(h, w, oldh, oldw)
            else super.onSizeChanged(w, h, oldw, oldh)
        }

        override fun onDraw(canvas: Canvas) {
            if (!vertical) {
                super.onDraw(canvas)
                val unit = resources.displayMetrics.density
                val left = paddingLeft + unit * 8
                val right = width - paddingRight - unit * 8
                for (i in 0..14) {
                    val x = left + (right - left) * i / 14f
                    canvas.drawLine(x, height / 2f + unit * 8, x, height / 2f + unit * if (i % 2 == 0) 12 else 10, ticks)
                }
            } else {
                val save = canvas.save()
                canvas.rotate(-90f)
                canvas.translate(-height.toFloat(), 0f)
                super.onDraw(canvas)
                canvas.restoreToCount(save)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!vertical) return super.onTouchEvent(event)
            if (!isEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP)
                    isPressed = event.actionMasked != MotionEvent.ACTION_UP
                    progress = (((height - event.y) / height.coerceAtLeast(1)) * max).roundToInt().coerceIn(0, max)
                    onSizeChanged(width, height, width, height)
                    onVerticalProgress?.invoke(progress)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    companion object { private const val ACCENT = ToviTheme.PRIMARY }
}
