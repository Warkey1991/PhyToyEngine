package com.phytoy.sample

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Values shown to the customer; a price is supplied only by Google Play. */
internal data class CameraPurchaseUi(
    val price: String? = null,
    val owned: Boolean = false,
    val pending: Boolean = false,
    val loading: Boolean = false,
    val canBuy: Boolean = false,
    val status: String? = null,
    val busy: Boolean = false,
)

/** A scrollable, dismissible camera introduction. No purchase is launched on entry. */
internal class CameraPurchaseOverlay(
    private val activity: Activity,
    private val onDismiss: () -> Unit,
    private val onPreview: (CameraStyle) -> Unit,
    private val onPurchase: (CameraStyle) -> Unit,
    private val onRestore: () -> Unit,
    private val onRetry: () -> Unit,
) : FrameLayout(activity) {
    private val topBar = PageTopBar(activity)
    private val scroll = ScrollView(activity)
    private val body = LinearLayout(activity)
    private val cameraArt = CameraIllustration(activity)
    private val name = label(28f, PageTopBar.ON_SURFACE)
    private val description = label(16f, PageTopBar.ON_SURFACE_VARIANT)
    private val price = label(30f, PageTopBar.ON_SURFACE)
    private val status = label(14f, PageTopBar.ON_SURFACE_VARIANT)
    private val buy = action(R.id.purchase_buy, true)
    private val preview = action(R.id.purchase_preview, false)
    private val restore = action(R.id.purchase_restore, false)
    private val retry = action(R.id.purchase_retry, false)
    private val hiddenAccessibility = linkedMapOf<View, Int>()
    private var previousFocus: View? = null
    var currentStyle: CameraStyle? = null
        private set

    init {
        id = R.id.purchase_page
        status.id = R.id.purchase_status
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setBackgroundColor(PageTopBar.SURFACE)
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = activity.getString(R.string.purchase_title)
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        topBar.titleView.text = activity.getString(R.string.purchase_title)
        topBar.backButton.id = R.id.purchase_back
        topBar.backButton.contentDescription = activity.getString(R.string.purchase_back)
        topBar.setOnBackClickListener { dismiss() }
        page.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        page.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(cameraArt, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(168)).apply { bottomMargin = dp(20) })
        name.apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        add(name, 0)
        add(description, 8)
        add(label(14f, PageTopBar.PRIMARY).apply { setText(R.string.purchase_one_time) }, 20)
        add(label(14f, PageTopBar.ON_SURFACE_VARIANT).apply { setText(R.string.purchase_details) }, 8)
        add(price, 24)
        add(status, 8)
        add(buy, 16)
        add(preview, 8)
        add(label(13f, PageTopBar.ON_SURFACE_VARIANT).apply { setText(R.string.purchase_preview_hint) }, 8)
        add(restore, 16)
        add(retry, 12)
        add(label(13f, PageTopBar.ON_SURFACE_VARIANT).apply { setText(R.string.purchase_scope) }, 16)
        add(label(12f, PageTopBar.ON_SURFACE_VARIANT).apply { setText(R.string.purchase_payment_provider) }, 12)
        buy.setOnClickListener { currentStyle?.let(onPurchase) }
        preview.setOnClickListener {
            val style = currentStyle ?: return@setOnClickListener
            dismiss()
            onPreview(style)
        }
        restore.setText(R.string.purchase_restore)
        restore.setOnClickListener { onRestore() }
        retry.setText(R.string.purchase_retry)
        retry.setOnClickListener { onRetry() }
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
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

    fun isShowing() = visibility == VISIBLE

    fun show(style: CameraStyle, ui: CameraPurchaseUi) {
        currentStyle = style
        name.text = style.name(activity)
        description.setText(style.descriptionRes)
        cameraArt.style = style
        update(ui)
        if (isShowing()) return
        previousFocus = activity.currentFocus
        (parent as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                val sibling = group.getChildAt(index)
                if (sibling !== this) {
                    hiddenAccessibility[sibling] = sibling.importantForAccessibility
                    sibling.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
        }
        visibility = VISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        requestApplyInsets()
        scroll.post { if (isShowing()) scroll.scrollTo(0, 0) }
        topBar.backButton.requestFocus()
        announceForAccessibility(activity.getString(R.string.purchase_title))
    }

    fun update(ui: CameraPurchaseUi) {
        price.text = when {
            ui.owned -> activity.getString(R.string.purchase_owned)
            ui.price != null -> ui.price
            ui.loading -> activity.getString(R.string.purchase_price_loading)
            else -> activity.getString(R.string.purchase_price_unavailable)
        }
        status.text = ui.status.orEmpty()
        status.visibility = if (ui.status.isNullOrBlank()) GONE else VISIBLE
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        buy.text = if (ui.canBuy && ui.price != null) activity.getString(R.string.purchase_buy, ui.price)
            else activity.getString(R.string.purchase_buy_unavailable)
        buy.visibility = if (ui.owned) GONE else VISIBLE
        buy.isEnabled = ui.canBuy && !ui.price.isNullOrBlank() && !ui.busy && !ui.pending
        buy.alpha = if (buy.isEnabled) 1f else 0.5f
        preview.setText(if (ui.owned) R.string.purchase_use_camera else R.string.purchase_preview)
        restore.isEnabled = !ui.busy
        retry.visibility = if (!ui.owned && !ui.canBuy && !ui.pending) VISIBLE else GONE
        retry.isEnabled = !ui.busy
    }

    fun dismiss(): Boolean {
        if (!isShowing()) return false
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        hiddenAccessibility.forEach { (view, previous) -> view.importantForAccessibility = previous }
        hiddenAccessibility.clear()
        previousFocus?.takeIf { it.isShown }?.requestFocus()
        previousFocus = null
        currentStyle = null
        onDismiss()
        return true
    }

    private fun label(size: Float, color: Int) = TextView(activity).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun add(view: View, top: Int) = body.addView(view,
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })

    private fun action(id: Int, primary: Boolean) = Button(activity).apply {
        this.id = id
        isAllCaps = false
        textSize = 16f
        gravity = Gravity.CENTER
        minHeight = dp(52)
        minimumHeight = dp(52)
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(if (primary) PageTopBar.SURFACE else PageTopBar.ON_SURFACE)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (primary) PageTopBar.PRIMARY else 0xFF24272E.toInt())
        }
        background = RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), shape, null)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Original vector-like artwork, clearly an illustration rather than a sample photograph. */
    private class CameraIllustration(activity: Activity) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var style: CameraStyle = CameraStyle.DIGITAL_01
            set(value) { field = value; invalidate() }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val scale = minOf(width / 320f, height / 168f)
            canvas.save()
            canvas.translate((width - 320f * scale) / 2f, (height - 168f * scale) / 2f)
            canvas.scale(scale, scale)
            paint.color = 0xFF20232A.toInt()
            canvas.drawRoundRect(0f, 0f, 320f, 168f, 24f, 24f, paint)
            paint.color = 0xFF353840.toInt()
            canvas.drawOval(66f, 137f, 254f, 149f, paint)
            paint.color = when (style) {
                CameraStyle.DIGITAL_01 -> 0xFFBDC5CE.toInt()
                CameraStyle.PLASTIC_82 -> 0xFFE8B15E.toInt()
                CameraStyle.STREET_84 -> 0xFF89A497.toInt()
                CameraStyle.FISHEYE_05 -> 0xFFBDA9CF.toInt()
                else -> PageTopBar.PRIMARY
            }
            canvas.drawRoundRect(68f, 45f, 252f, 133f, 16f, 16f, paint)
            canvas.drawRoundRect(84f, 35f, 115f, 53f, 4f, 4f, paint)
            paint.color = 0xFF3B4148.toInt()
            canvas.drawRoundRect(82f, 68f, 115f, 122f, 5f, 5f, paint)
            canvas.drawRoundRect(211f, 55f, 238f, 69f, 3f, 3f, paint)
            paint.color = 0xFFDDE5E3.toInt()
            canvas.drawRoundRect(214f, 58f, 235f, 65f, 1f, 1f, paint)
            paint.color = 0xFF24272E.toInt()
            canvas.drawCircle(164f, 91f, if (style == CameraStyle.FISHEYE_05) 39f else 33f, paint)
            paint.color = 0xFF52616D.toInt()
            canvas.drawCircle(164f, 91f, 25f, paint)
            paint.color = 0xFF16212A.toInt()
            canvas.drawCircle(164f, 91f, 18f, paint)
            paint.color = 0xFF9EC0CA.toInt()
            canvas.drawCircle(157f, 84f, 5f, paint)
            paint.color = 0xFF24272E.toInt()
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = 9f
            canvas.drawText(style.shortCode, 204f, 117f, paint)
            canvas.restore()
        }
    }
}
