package com.phytoy.sample

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            setColor(0xF51B1E22.toInt())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x35FFFFFF)
        }
        isClickable = true
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minimumHeight = dp(32)
        setPadding(0, dp(4), 0, dp(4))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val slider = SeekBar(context).apply {
        id = R.id.adjustment_slider
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(ACCENT)
    }
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

    init {
        id = R.id.adjustment_panel
        visibility = GONE
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
    }

    fun showExposure(index: Int, minimum: Int, maximum: Int, step: Float, onSelected: (Int) -> Unit) {
        if (minimum >= maximum || step <= 0f) return
        actions.removeAllViews()
        actionButtons.clear()
        actionColumns = 0
        val update = { value: Int ->
            title.text = context.getString(R.string.camera_adjust_exposure_value, value * step)
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
        actions.removeAllViews()
        actionButtons.clear()
        actionColumns = 0
        val span = ln(maximum / minimum)
        fun fromProgress(progress: Int): Float = (minimum * exp(span * progress / 1000f)).coerceIn(minimum, maximum)
        fun toProgress(value: Float): Int = (ln(value.coerceIn(minimum, maximum) / minimum) / span * 1000f).roundToInt()
        val update = { value: Float ->
            title.text = context.getString(R.string.camera_adjust_zoom_value, value)
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
        // Show only focal multipliers the hardware actually reports as supported.
        listOf(minimum, 1f, 2f, maximum).filter { it in minimum..maximum }
            .distinctBy { (it * 100).roundToInt() }.take(3).forEach { value ->
                addAction(String.format(Locale.getDefault(), "%.1f×", value)) {
                    slider.progress = toProgress(value)
                    update(value)
                    onSelected(value)
                }
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

    private fun addAction(label: String, viewId: Int = View.NO_ID, action: () -> Unit) {
        val button = TextView(context).apply {
            id = viewId
            text = label
            setTextColor(ACCENT)
            textSize = 12f
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
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - dp(32)).coerceAtLeast(0)
        val buttonWidth = actionButtons.maxOfOrNull { button ->
            (button.paint.measureText(button.text.toString()) + button.paddingLeft + button.paddingRight).roundToInt()
                .coerceAtLeast(dp(48))
        } ?: dp(48)
        val columns = if (actionButtons.size > 2 && availableWidth < buttonWidth * actionButtons.size) 2
            else actionButtons.size.coerceAtLeast(1)
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
    companion object { private const val ACCENT = 0xFFF2B84B.toInt() }
}
