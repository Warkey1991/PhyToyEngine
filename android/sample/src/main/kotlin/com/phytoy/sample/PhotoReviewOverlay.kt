package com.phytoy.sample

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen, in-camera review. It deliberately has no route to an external gallery app. */
internal class PhotoReviewOverlay(context: Context) : FrameLayout(context) {
    private val photo = ImageView(context)
    private val topBar = PageTopBar(context)
    private val title = topBar.titleView
    private val details = topBar.subtitleView
    private val back = topBar.backButton
    private val continueButton = TextView(context)
    private var reviewBitmap: Bitmap? = null
    private var visibilityListener: ((Boolean) -> Unit)? = null
    private var continueListener: (() -> Unit)? = null

    fun setOnContinueShootingListener(listener: () -> Unit) {
        continueListener = listener
    }

    fun setOnReviewVisibilityChangedListener(listener: (Boolean) -> Unit) {
        visibilityListener = listener
    }
    private val backgroundAccessibility = linkedMapOf<View, Int>()
    private var previousInputFocus: View? = null
    private var previousAccessibilityFocus: View? = null

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            accessibilityPaneTitle = context.getString(R.string.review_pane_title)
        }
        setBackgroundColor(PageTopBar.SURFACE)

        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        photo.id = R.id.review_photo
        back.id = R.id.review_back
        continueButton.id = R.id.review_continue
        photo.scaleType = ImageView.ScaleType.FIT_CENTER
        photo.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        photo.setBackgroundColor(Color.BLACK)
        back.apply {
            contentDescription = context.getString(R.string.review_navigate_back)
        }
        topBar.setOnBackClickListener { dismiss() }
        details.visibility = VISIBLE
        page.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        page.addView(photo, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = FrameLayout(context).apply {
            setBackgroundColor(PageTopBar.SURFACE)
            minimumHeight = dp(88)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        continueButton.apply {
            text = context.getString(R.string.review_action_continue)
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.review_continue_description)
            includeFontPadding = false
            minimumHeight = dp(48)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            maxLines = 2
            background = touchBackground(ACCENT, dp(24).toFloat())
            isClickable = true
            isFocusable = true
            accessibilityDelegate = buttonAccessibilityDelegate()
            setOnClickListener {
                dismiss()
                continueListener?.invoke()
            }
        }
        actions.addView(
            continueButton,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        page.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val system = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(system.left, system.top, system.right, system.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    fun show(bitmap: Bitmap, saved: PhotoStore.SavedPhoto) {
        // Another modal may have hidden this sibling while it was inactive.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val wasShowing = isShowing()
        if (!isShowing()) {
            previousInputFocus = rootView.findFocus()
            previousAccessibilityFocus = findAccessibilityFocus(rootView)
        }
        isolateAccessibility()
        reviewBitmap?.takeIf { it !== bitmap }?.recycle()
        reviewBitmap = bitmap
        photo.setImageBitmap(bitmap)
        photo.contentDescription = context.getString(
            R.string.review_photo_description, saved.styleName, saved.width, saved.height,
        )
        title.text = saved.styleName
        details.text = context.getString(
            R.string.review_image_details,
            saved.width,
            saved.height,
        )
        visibility = VISIBLE
        requestApplyInsets()
        if (!wasShowing) visibilityListener?.invoke(true)
        animate().cancel()
        alpha = 0f
        animate().alpha(1f).setDuration(160L).start()
        continueButton.requestFocus()
        if (isTouchExplorationEnabled()) {
            back.post {
                if (isShowing() && back.isAttachedToWindow) {
                    back.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
        }
    }

    fun dismiss(): Boolean {
        if (visibility != VISIBLE) return false
        animate().cancel()
        visibility = GONE
        visibilityListener?.invoke(false)
        photo.setImageDrawable(null)
        reviewBitmap?.recycle()
        reviewBitmap = null
        restoreBackgroundAccessibility()
        previousInputFocus?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled }?.requestFocus()
        if (isTouchExplorationEnabled()) {
            previousAccessibilityFocus?.let { previous ->
                previous.post {
                    if (previous.isAttachedToWindow && previous.isShown) {
                        previous.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                    }
                }
            }
        }
        previousInputFocus = null
        previousAccessibilityFocus = null
        return true
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    private fun isolateAccessibility() {
        val container = parent as? ViewGroup ?: return
        for (index in 0 until container.childCount) {
            val sibling = container.getChildAt(index)
            if (sibling === this) continue
            if (!backgroundAccessibility.containsKey(sibling)) {
                backgroundAccessibility[sibling] = sibling.importantForAccessibility
            }
            sibling.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun restoreBackgroundAccessibility() {
        backgroundAccessibility.forEach { (view, previous) -> view.importantForAccessibility = previous }
        backgroundAccessibility.clear()
    }

    private fun findAccessibilityFocus(view: View): View? {
        if (view.isAccessibilityFocused) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findAccessibilityFocus(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun isTouchExplorationEnabled(): Boolean =
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.isTouchExplorationEnabled == true

    private fun buttonAccessibilityDelegate() = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    override fun onDetachedFromWindow() {
        dismiss()
        restoreBackgroundAccessibility()
        super.onDetachedFromWindow()
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

    private fun touchBackground(color: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf(0x30FFFFFF),
        rounded(color, radius),
        rounded(Color.WHITE, radius),
    )

    companion object {
        private const val ACCENT = PageTopBar.PRIMARY
    }
}
