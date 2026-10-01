package com.phytoy.sample

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Native app bar with a stable navigation target and room for scaled, wrapping text. */
internal class PageTopBar(context: Context) : FrameLayout(context) {
    val backButton: View = NavigationButton(context)
    val titleView = TextView(context)
    val subtitleView = TextView(context)

    init {
        setBackgroundColor(SURFACE)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView.apply {
            textSize = 22f
            setTextColor(ON_SURFACE)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }
        subtitleView.apply {
            visibility = GONE
            textSize = 12f
            setTextColor(ON_SURFACE_VARIANT)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        heading.addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        heading.addView(subtitleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(72)
            marginEnd = dp(20)
            topMargin = dp(16)
            bottomMargin = dp(16)
        })
        backButton.apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            background = RippleDrawable(
                ColorStateList.valueOf(0x30FFFFFF),
                null,
                GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(24).toFloat() },
            )
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
        }
        addView(backButton, LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(12)
        })
    }

    fun setOnBackClickListener(listener: () -> Unit) {
        backButton.setOnClickListener { listener() }
    }

    override fun getSuggestedMinimumHeight(): Int = maxOf(
        super.getSuggestedMinimumHeight(),
        dp(if (subtitleView.visibility == GONE) 64 else 88),
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class NavigationButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ON_SURFACE
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val direction = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f
            canvas.drawLine(cx + 8f * unit * direction, cy, cx - 8f * unit * direction, cy, paint)
            canvas.drawLine(cx - 8f * unit * direction, cy, cx - 1f * unit * direction, cy - 7f * unit, paint)
            canvas.drawLine(cx - 8f * unit * direction, cy, cx - 1f * unit * direction, cy + 7f * unit, paint)
        }
    }

    companion object {
        const val SURFACE = 0xFF111318.toInt()
        const val ON_SURFACE = 0xFFF1F1F1.toInt()
        const val ON_SURFACE_VARIANT = 0xFFC4C7C5.toInt()
        const val PRIMARY = 0xFFF2B84B.toInt()
    }
}
