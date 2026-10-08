package com.phytoy.sample

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Values shown to the customer; a price is supplied only by the active store. */
internal data class CameraPurchaseUi(
    val price: String? = null,
    val owned: Boolean = false,
    val pending: Boolean = false,
    val loading: Boolean = false,
    val canBuy: Boolean = false,
    val status: String? = null,
    val busy: Boolean = false,
)

/** One style-detail template for free cameras, live trials and store unlocks. */
internal class CameraPurchaseOverlay(
    private val activity: Activity,
    private val onDismiss: () -> Unit,
    private val onPreview: (CameraStyle) -> Unit,
    private val onPurchase: (CameraStyle) -> Unit,
    private val onRestore: () -> Unit,
    private val onRetry: () -> Unit,
) : FrameLayout(activity) {
    private val topBar = PageTopBar(activity)
    private val content = LinearLayout(activity)
    private val scroll = ScrollView(activity)
    private val body = LinearLayout(activity)
    private val hero = LinearLayout(activity)
    private val heroCopy = LinearLayout(activity)
    private val cameraArt = CameraArtworkView(activity)
    private val name = label(32f, ToviTheme.TEXT)
    private val description = label(15f, ToviTheme.MUTED)
    private val samples = LinearLayout(activity)
    private val sourceSample = ImageView(activity)
    private val styleSample = ImageView(activity)
    private val styleSampleLabel = label(12f, ToviTheme.PRIMARY)
    private val sourceCard = sampleCard(sourceSample,
        label(12f, ToviTheme.MUTED).apply { setText(R.string.purchase_sample_original_label) })
    private val styleCard = sampleCard(styleSample, styleSampleLabel)
    private val sampleTitle = label(12f, ToviTheme.MUTED)
    private val price = label(20f, ToviTheme.TEXT)
    private val status = label(14f, ToviTheme.MUTED)
    private val loading = ProgressBar(activity)
    private val previewHint = label(13f, ToviTheme.MUTED)
    private val details = label(13f, ToviTheme.MUTED)
    private val buy = action(R.id.purchase_buy, true)
    private val preview = action(R.id.purchase_preview, false)
    private val restore = action(R.id.purchase_restore, false)
    private val retry = action(R.id.purchase_retry, false)
    private val dockScroll = DecisionScrollView(activity)
    private val purchaseActions = LinearLayout(activity)
    private val oneTime = label(12f, ToviTheme.MUTED)
    private val hiddenAccessibility = linkedMapOf<View, Int>()
    private var previousFocus: View? = null
    var currentStyle: CameraStyle? = null
        private set

    init {
        id = R.id.purchase_page
        status.id = R.id.purchase_status
        price.id = R.id.purchase_price
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setBackgroundColor(ToviTheme.SURFACE)
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = activity.getString(R.string.purchase_title)
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        topBar.titleView.setText(R.string.purchase_title)
        topBar.backButton.id = R.id.purchase_back
        topBar.backButton.contentDescription = activity.getString(R.string.purchase_back)
        topBar.setOnBackClickListener { dismiss() }
        page.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.orientation = LinearLayout.VERTICAL
        page.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        configureScroll(scroll)
        scroll.setPadding(dp(16), dp(8), dp(16), dp(16))
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        hero.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = ToviTheme.card(activity, 24)
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        cameraArt.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        hero.addView(cameraArt, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(192)))
        heroCopy.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        name.apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        heroCopy.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        heroCopy.addView(description, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
        hero.addView(heroCopy, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        add(hero, 0)
        add(label(16f, ToviTheme.TEXT).apply {
            setText(R.string.purchase_effect_heading)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, 24)
        samples.orientation = LinearLayout.HORIZONTAL
        samples.addView(sourceCard, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        samples.addView(styleCard, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        add(samples, 12)
        add(sampleTitle, 8)
        sourceSample.contentDescription = activity.getString(R.string.purchase_sample_source)
        val information = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ToviTheme.card(activity)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val priceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        price.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        priceRow.addView(price, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        loading.apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ToviTheme.PRIMARY)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        priceRow.addView(loading, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        information.addView(priceRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        information.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
        previewHint.setText(R.string.purchase_preview_hint)
        information.addView(previewHint, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        details.setText(R.string.purchase_details)
        information.addView(details, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
        add(information, 20)
        restore.setText(R.string.purchase_restore)
        restore.textSize = 14f
        restore.setOnClickListener { onRestore() }
        add(restore, 16)
        retry.setText(R.string.purchase_retry)
        retry.textSize = 14f
        retry.setOnClickListener { onRetry() }
        add(retry, 8)
        add(label(12f, ToviTheme.MUTED).apply { setText(R.string.purchase_scope) }, 16)
        add(label(12f, ToviTheme.MUTED).apply { setText(R.string.purchase_payment_provider) }, 8)
        // The decision stays outside the examples' scroll. A short landscape
        // window gives it a separate, scrollable side rail with full-width buttons.
        configureScroll(dockScroll)
        dockScroll.isFillViewport = true
        dockScroll.setBackgroundColor(ToviTheme.SURFACE)
        purchaseActions.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        purchaseActions.addView(buy, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        purchaseActions.addView(preview, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
        oneTime.apply {
            setText(R.string.purchase_one_time)
            gravity = Gravity.CENTER
        }
        purchaseActions.addView(oneTime, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })
        dockScroll.addView(purchaseActions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(dockScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buy.setOnClickListener { currentStyle?.let(onPurchase) }
        preview.setOnClickListener {
            val style = currentStyle ?: return@setOnClickListener
            dismiss()
            onPreview(style)
        }
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
        updateLayout(dp(resources.configuration.screenWidthDp), dp(resources.configuration.screenHeightDp))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayout(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom)
    }

    private fun updateLayout(width: Int, height: Int) {
        val largeFont = resources.configuration.fontScale >= 1.5f
        val sideActions = width > height && width >= dp(600)
        val dockWidth = dp(if (largeFont) 280 else 240)
        content.orientation = if (sideActions) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        scroll.layoutParams = if (sideActions) LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        dockScroll.layoutParams = if (sideActions) LinearLayout.LayoutParams(dockWidth, LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        dockScroll.reserveBodyHeight = if (sideActions) 0 else dp(96)
        val bodyWidth = width - (if (sideActions) dockWidth else 0) - scroll.paddingLeft - scroll.paddingRight
        val wideHero = bodyWidth >= dp(600) && !largeFont
        hero.orientation = if (wideHero) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        cameraArt.layoutParams = if (wideHero) LinearLayout.LayoutParams(0, dp(220), 1f)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(when {
                sideActions -> 144
                largeFont -> 160
                else -> 192
            }))
        heroCopy.layoutParams = if (wideHero) LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(20) }
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        val stackedSamples = bodyWidth < dp(300) || (largeFont && bodyWidth < dp(500))
        samples.orientation = if (stackedSamples) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        sourceCard.layoutParams = if (stackedSamples) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
        styleCard.layoutParams = if (stackedSamples) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            else LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
        val sampleHeight = dp(if (stackedSamples) 208 else 176)
        sourceSample.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, sampleHeight)
        styleSample.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, sampleHeight)
    }

    fun isShowing() = visibility == VISIBLE

    fun show(style: CameraStyle, ui: CameraPurchaseUi) {
        val changedStyle = currentStyle != style
        currentStyle = style
        val styledName = SpannableString(style.uiName(activity))
        Regex("\\d+[+]*").find(styledName)?.let {
            styledName.setSpan(ForegroundColorSpan(ToviTheme.PRIMARY), it.range.first, it.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        name.text = styledName
        description.setText(style.descriptionRes)
        cameraArt.setStyle(style)
        sourceSample.setImageResource(when (style) {
            CameraStyle.HARINEZUMI_2PP -> R.drawable.style_source_dh_color
            CameraStyle.HARINEZUMI_2PP_MONO -> R.drawable.style_source_dh_mono
            CameraStyle.DIGITAL_01 -> R.drawable.style_source_digital
            CameraStyle.PLASTIC_82 -> R.drawable.style_source_plastic
            CameraStyle.STREET_84 -> R.drawable.style_source_street
            CameraStyle.FISHEYE_05 -> R.drawable.style_source_fisheye
        })
        styleSample.setImageResource(when (style) {
            CameraStyle.HARINEZUMI_2PP -> R.drawable.style_sample_dh_color
            CameraStyle.HARINEZUMI_2PP_MONO -> R.drawable.style_sample_dh_mono
            CameraStyle.DIGITAL_01 -> R.drawable.style_sample_digital
            CameraStyle.PLASTIC_82 -> R.drawable.style_sample_plastic
            CameraStyle.STREET_84 -> R.drawable.style_sample_street
            CameraStyle.FISHEYE_05 -> R.drawable.style_sample_fisheye
        })
        styleSample.contentDescription = activity.getString(R.string.purchase_sample_style, style.name(activity))
        styleSampleLabel.text = style.uiName(activity)
        sampleTitle.text = activity.getString(R.string.purchase_sample_caption, style.name(activity))
        update(ui)
        if (isShowing()) {
            if (changedStyle) scroll.post { if (isShowing()) scroll.scrollTo(0, 0) }
            return
        }
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
        dockScroll.post { if (isShowing()) dockScroll.scrollTo(0, 0) }
        topBar.backButton.requestFocus()
        if (Build.VERSION.SDK_INT < 28) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.text.add(activity.getString(R.string.purchase_title))
            sendAccessibilityEventUnchecked(event)
        }
    }

    fun update(ui: CameraPurchaseUi) {
        price.text = when {
            ui.owned -> activity.getString(R.string.purchase_owned)
            !ui.price.isNullOrBlank() -> ui.price
            ui.loading -> activity.getString(R.string.purchase_price_loading)
            else -> activity.getString(R.string.purchase_price_unavailable)
        }
        val message = ui.status?.takeIf { it.isNotBlank() } ?: when {
            ui.pending -> activity.getString(R.string.billing_pending)
            ui.busy -> activity.getString(R.string.billing_processing)
            ui.loading -> activity.getString(R.string.purchase_price_loading)
            else -> null
        }
        status.text = message.orEmpty()
        status.visibility = if (message.isNullOrBlank()) GONE else VISIBLE
        loading.visibility = if ((ui.loading || ui.busy) && !ui.owned && !ui.pending) VISIBLE else GONE
        buy.text = when {
            ui.pending -> activity.getString(R.string.purchase_pending_action)
            ui.busy -> activity.getString(R.string.purchase_busy_action)
            ui.loading && !ui.canBuy -> activity.getString(R.string.purchase_price_loading)
            ui.canBuy && !ui.price.isNullOrBlank() -> activity.getString(R.string.purchase_buy, ui.price)
            else -> activity.getString(R.string.purchase_buy_unavailable)
        }
        buy.visibility = if (ui.owned) GONE else VISIBLE
        buy.isEnabled = ui.canBuy && !ui.price.isNullOrBlank() && !ui.busy && !ui.pending
        buy.alpha = if (buy.isEnabled) 1f else 0.5f
        preview.setText(if (ui.owned) R.string.purchase_use_camera else R.string.purchase_preview)
        preview.background = ToviTheme.actionBackground(activity, primary = ui.owned)
        preview.setTextColor(if (ui.owned) ToviTheme.SURFACE else ToviTheme.TEXT)
        oneTime.visibility = if (ui.owned) GONE else VISIBLE
        previewHint.visibility = if (ui.owned) GONE else VISIBLE
        details.visibility = if (ui.owned) GONE else VISIBLE
        restore.isEnabled = !ui.busy
        restore.alpha = if (restore.isEnabled) 1f else 0.5f
        retry.visibility = if (!ui.owned && !ui.canBuy && !ui.pending) VISIBLE else GONE
        retry.isEnabled = !ui.busy
        retry.alpha = if (retry.isEnabled) 1f else 0.5f
    }

    fun dismiss(): Boolean {
        if (!isShowing()) return false
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        hiddenAccessibility.forEach { (view, previous) -> view.importantForAccessibility = previous }
        hiddenAccessibility.clear()
        previousFocus?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }?.requestFocus()
        previousFocus = null
        currentStyle = null
        onDismiss()
        return true
    }

    private fun configureScroll(view: ScrollView) {
        view.isFillViewport = false
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = OVER_SCROLL_NEVER
        view.clipToPadding = false
    }

    private fun sampleCard(image: ImageView, caption: TextView) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = ToviTheme.card(activity, 16)
        clipToOutline = true
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setBackgroundColor(ToviTheme.SURFACE)
        addView(image, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(176)))
        caption.setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(caption, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(52)
        minimumHeight = dp(52)
        minWidth = 0
        minimumWidth = 0
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(if (primary) ToviTheme.SURFACE else ToviTheme.TEXT)
        background = ToviTheme.actionBackground(activity, primary)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** The decision must never consume the examples/status/restore viewport. */
    private class DecisionScrollView(context: Context) : ScrollView(context) {
        var reserveBodyHeight = 0
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val available = MeasureSpec.getSize(heightMeasureSpec)
            val limitedHeightSpec = if (reserveBodyHeight > 0 && mode != MeasureSpec.UNSPECIFIED) {
                // Keep 96dp for details whenever the remaining page is at least
                // 192dp tall. In still smaller windows both scroll panes get room.
                val reserved = minOf(reserveBodyHeight, available / 2)
                MeasureSpec.makeMeasureSpec(available - reserved, MeasureSpec.AT_MOST)
            } else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, limitedHeightSpec)
        }
    }
}
