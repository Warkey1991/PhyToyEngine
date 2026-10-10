package com.phytoy.sample

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.HorizontalScrollView
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

/** Native controls over decorative design artwork; billing remains with the host. */
internal class CameraPurchaseOverlay(
    private val activity: Activity,
    private val onDismiss: () -> Unit,
    private val onPreview: (CameraStyle) -> Unit,
    private val onPurchase: (CameraStyle) -> Unit,
    private val onRestore: () -> Unit,
    private val onRetry: () -> Unit,
    private val isStyleUnlocked: (CameraStyle) -> Boolean = {
        it == CameraStyle.HARINEZUMI_2PP || it == CameraStyle.HARINEZUMI_2PP_MONO
    },
    private val onSelectStyle: ((CameraStyle) -> Unit)? = null,
) : FrameLayout(activity) {
    private val page = FrameLayout(activity)
    private val topBar = PageTopBar(activity)
    private val content = LinearLayout(activity)
    private val scroll = ScrollView(activity)
    private val body = LinearLayout(activity)
    private val hero = FrameLayout(activity)
    private val cameraArt = CameraArtworkView(activity)
    private val heroShade = View(activity)
    private val heroCopy = LinearLayout(activity)
    private val name = label(30f, ToviTheme.TEXT)
    private val series = label(10f, ToviTheme.MUTED)
    private val features = label(12f, ToviTheme.TEXT)
    private val description = label(13f, ToviTheme.MUTED)
    private val film = FilmFrame(activity)
    private val designSample = CameraArtworkView(activity)
    private val renderedSample = ImageView(activity)
    private val streetSamples = LinearLayout(activity)
    private val streetPhotos = List(3) { CameraArtworkView(activity) }
    private val styleRail = HorizontalScrollView(activity)
    private val styleCards = linkedMapOf<CameraStyle, StyleCard>()
    private val sampleCaption = label(11f, ToviTheme.MUTED)
    private val sourceSample = ImageView(activity)
    private val styleSample = ImageView(activity)
    private val styleSampleLabel = label(12f, ToviTheme.PRIMARY)
    private val sourceCard = sampleCard(sourceSample, label(12f, ToviTheme.MUTED).apply {
        setText(R.string.purchase_sample_original_label)
    })
    private val styleCard = sampleCard(styleSample, styleSampleLabel)
    private val comparisonCaption = label(11f, ToviTheme.MUTED)
    private val comparison = LinearLayout(activity)
    private val samples = LinearLayout(activity)
    private val price = label(15f, ToviTheme.TEXT)
    private val status = label(13f, ToviTheme.MUTED)
    private val loading = ProgressBar(activity)
    private val previewHint = label(12f, ToviTheme.MUTED)
    private val details = label(12f, ToviTheme.MUTED)
    private val buy = action(R.id.purchase_buy, true)
    private val preview = action(R.id.purchase_preview, false)
    private val restore = action(R.id.purchase_restore, false)
    private val retry = action(R.id.purchase_retry, false)
    private val compare = action(R.id.purchase_compare, false)
    private val auxiliaryActions = LinearLayout(activity)
    private val dockScroll = DecisionScrollView(activity)
    private val purchaseActions = LinearLayout(activity)
    private val oneTime = label(11f, ToviTheme.MUTED)
    private val hiddenAccessibility = linkedMapOf<View, Int>()
    private var previousFocus: View? = null
    private var lastUi = CameraPurchaseUi()
    private var comparisonExpanded = false
    private var bodyWidth = 0
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
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        topBar.titleView.apply { setText(R.string.purchase_title); textSize = 16f }
        topBar.backButton.id = R.id.purchase_back
        topBar.backButton.contentDescription = activity.getString(R.string.purchase_back)
        topBar.setOnBackClickListener { dismiss() }
        content.orientation = LinearLayout.VERTICAL
        page.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(56)
        })
        page.addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateContentInset() }
        configureScroll(scroll)
        scroll.setPadding(dp(12), dp(4), dp(12), dp(20))
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        hero.apply { background = ToviTheme.card(activity, 20, 0xFF09090A.toInt()); clipToOutline = true }
        cameraArt.id = R.id.purchase_hero_artwork
        cameraArt.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        hero.addView(cameraArt)
        heroShade.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        hero.addView(heroShade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        heroCopy.orientation = LinearLayout.VERTICAL
        heroCopy.id = R.id.purchase_hero_copy
        name.apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        series.apply { setText(R.string.design_purchase_series); letterSpacing = .16f }
        heroCopy.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        heroCopy.addView(series, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        heroCopy.addView(features, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        heroCopy.addView(description, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        hero.addView(heroCopy)
        add(hero, 0)
        designSample.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        renderedSample.apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(ToviTheme.SURFACE) }
        listOf(designSample, renderedSample).forEach {
            film.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(dp(16), dp(5), dp(16), dp(5)) })
        }
        add(film, 8)
        streetSamples.orientation = LinearLayout.HORIZONTAL
        streetPhotos.forEachIndexed { index, image ->
            image.showStreetSample(index)
            image.contentDescription = activity.getString(when (index) {
                0 -> R.string.design_purchase_street_city
                1 -> R.string.design_purchase_street_portrait
                else -> R.string.design_purchase_street_cafe
            })
            image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            image.background = ToviTheme.card(activity, 12)
            image.clipToOutline = true
            streetSamples.addView(image, LinearLayout.LayoutParams(0, dp(140), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        add(streetSamples, 10)
        styleRail.apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; clipToPadding = false }
        val cards = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        CameraStyle.entries.forEach { style ->
            val card = StyleCard(activity, style)
            card.setOnClickListener {
                if (currentStyle == style || lastUi.busy) return@setOnClickListener
                if (onSelectStyle != null) onSelectStyle.invoke(style)
                else { dismiss(); onPreview(style) }
            }
            styleCards[style] = card
            cards.addView(card, LinearLayout.LayoutParams(dp(70), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        styleRail.addView(cards, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        add(styleRail, 16)
        sampleCaption.setText(R.string.design_purchase_illustration)
        add(sampleCaption, 10)
        val priceRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        price.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        priceRow.addView(price, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        loading.apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ToviTheme.PRIMARY)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        priceRow.addView(loading, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        add(priceRow, 18)
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        add(status, 8)
        previewHint.setText(R.string.purchase_preview_hint)
        add(previewHint, 12)
        auxiliaryActions.orientation = LinearLayout.HORIZONTAL
        auxiliaryActions.addView(preview)
        restore.apply {
            setText(R.string.design_purchase_restore_short)
            contentDescription = activity.getString(R.string.purchase_restore)
            setOnClickListener { onRestore() }
        }
        auxiliaryActions.addView(restore)
        add(auxiliaryActions, 12)
        retry.apply { setText(R.string.purchase_retry); setOnClickListener { onRetry() } }
        add(retry, 8)
        compare.setOnClickListener { setComparisonExpanded(!comparisonExpanded) }
        add(compare, 16)
        comparison.orientation = LinearLayout.VERTICAL
        samples.orientation = LinearLayout.HORIZONTAL
        samples.addView(sourceCard)
        samples.addView(styleCard)
        comparison.addView(samples, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        comparison.addView(comparisonCaption, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        add(comparison, 10)
        sourceSample.contentDescription = activity.getString(R.string.purchase_sample_source)
        setComparisonExpanded(false)
        details.setText(R.string.purchase_details)
        add(details, 16)
        add(label(11f, ToviTheme.MUTED).apply { setText(R.string.purchase_scope) }, 12)
        add(label(11f, ToviTheme.MUTED).apply { setText(R.string.purchase_payment_provider) }, 8)
        // Only one primary decision occupies the fixed dock. Native auxiliary
        // actions and complete store state remain reachable in the body scroll.
        configureScroll(dockScroll)
        dockScroll.isFillViewport = true
        dockScroll.setBackgroundColor(ToviTheme.SURFACE)
        purchaseActions.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        purchaseActions.addView(buy, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buy.compoundDrawablePadding = dp(8)
        buy.setCompoundDrawablesRelative(
            PurchaseGlyph(false).apply { setBounds(0, 0, dp(18), dp(22)) }, null,
            PurchaseGlyph(true).apply { setBounds(0, 0, dp(10), dp(22)) }, null,
        )
        oneTime.apply { setText(R.string.design_purchase_lifetime); gravity = Gravity.CENTER }
        purchaseActions.addView(oneTime, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
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
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
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
        if (width <= 0 || height <= 0) return
        val largeFont = resources.configuration.fontScale >= 1.5f
        val sideActions = width > height && width >= dp(600)
        val dockWidth = dp(if (largeFont) 280 else 232)
        content.orientation = if (sideActions) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        scroll.layoutParams = if (sideActions) LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        dockScroll.layoutParams = if (sideActions) LinearLayout.LayoutParams(dockWidth, LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        dockScroll.reserveBodyHeight = if (sideActions) 0 else dp(96)
        bodyWidth = maxOf(dp(96), width - (if (sideActions) dockWidth else 0) - scroll.paddingLeft - scroll.paddingRight)
        val street = currentStyle == CameraStyle.STREET_84
        topBar.titleView.visibility = if (street) GONE else VISIBLE
        topBar.setBackgroundColor(if (street) android.graphics.Color.TRANSPARENT else ToviTheme.SURFACE)
        updateContentInset()
        scroll.setPadding(dp(if (street) 0 else 12), dp(if (street) 0 else 4), dp(if (street) 0 else 12), dp(20))
        bodyWidth = maxOf(dp(96), width - (if (sideActions) dockWidth else 0) - scroll.paddingLeft - scroll.paddingRight)
        for (index in 0 until body.childCount) {
            val child = body.getChildAt(index)
            (child.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(if (street && child !== hero) 12 else 0)
                marginEnd = marginStart
            }.also { child.layoutParams = it }
        }
        hero.background = if (street) GradientDrawable().apply { setColor(ToviTheme.SURFACE) }
            else ToviTheme.card(activity, 20, 0xFF09090A.toInt())
        val stackedHero = largeFont || bodyWidth < dp(300)
        val artHeight = if (street) (bodyWidth * .76f).toInt().coerceIn(dp(180), dp(320)) else dp(182)
        val contentWidth = (bodyWidth - dp(40)).coerceAtLeast(0)
        val copyWidth = (contentWidth * .54f).toInt()
        val artWidth = contentWidth - copyWidth
        hero.minimumHeight = if (stackedHero || street) 0 else dp(172)
        cameraArt.layoutParams = when {
            stackedHero || street -> LayoutParams(LayoutParams.MATCH_PARENT, artHeight, Gravity.TOP)
            // An explicit height avoids FrameLayout's single MATCH_PARENT
            // child measuring to zero inside the scrolling WRAP_CONTENT hero.
            else -> LayoutParams(artWidth, artHeight, Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { marginEnd = dp(12) }
        }
        heroCopy.layoutParams = when {
            stackedHero -> LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { setMargins(dp(16), artHeight + dp(12), dp(16), dp(16)) }
            street -> LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { setMargins(dp(16), artHeight + dp(4), dp(16), dp(16)) }
            else -> LayoutParams(copyWidth, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
                .apply { setMargins(dp(16), dp(16), dp(8), dp(16)) }
        }
        heroCopy.gravity = if (street) Gravity.CENTER_HORIZONTAL else Gravity.START
        name.gravity = if (street) Gravity.CENTER else Gravity.START
        features.gravity = if (street) Gravity.CENTER else Gravity.START
        name.textSize = if (street && !largeFont) 36f else 30f
        heroShade.visibility = if (street) VISIBLE else GONE
        heroShade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x0009090A, 0x0009090A, 0xEF09090A.toInt()))
        streetPhotos.forEachIndexed { index, image ->
            val samplesWidth = bodyWidth - dp(if (street) 24 else 0)
            image.layoutParams = LinearLayout.LayoutParams(0, maxOf(dp(104), (samplesWidth - dp(16)) / 3 * 13 / 10), 1f)
                .apply { if (index > 0) marginStart = dp(8) }
        }
        val stackedActions = largeFont || bodyWidth < dp(300)
        auxiliaryActions.orientation = if (stackedActions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        listOf(preview, restore).filter { it.parent === auxiliaryActions }.forEachIndexed { index, button ->
            button.layoutParams = if (stackedActions) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { if (index > 0) topMargin = dp(8) }
                else LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(8) }
        }
        buy.textSize = if (largeFont) 16f else 18f
        preview.textSize = if (lastUi.owned) 16f else 13f
        val stackedSamples = largeFont || bodyWidth < dp(300)
        samples.orientation = if (stackedSamples) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        sourceCard.layoutParams = if (stackedSamples) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
        styleCard.layoutParams = if (stackedSamples) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            else LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
        listOf(sourceSample, styleSample).forEach {
            it.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(if (stackedSamples) 208 else 144))
        }
        val cardWidth = if (largeFont) dp(94) else maxOf(dp(48), (bodyWidth - dp(32)) / 5)
        styleCards.values.forEach {
            it.layoutParams = LinearLayout.LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            it.setArtworkHeight(if (largeFont) dp(68) else cardWidth)
        }
    }

    private fun updateContentInset() {
        val inset = if (currentStyle == CameraStyle.STREET_84) 0 else maxOf(dp(56), topBar.height)
        val params = content.layoutParams as LayoutParams
        if (params.topMargin != inset) {
            params.topMargin = inset
            content.layoutParams = params
        }
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
        features.text = style.uiTagline(activity)
        val street = style == CameraStyle.STREET_84
        series.visibility = if (street) GONE else VISIBLE
        description.visibility = if (street) GONE else VISIBLE
        film.visibility = if (street) GONE else VISIBLE
        streetSamples.visibility = if (street) VISIBLE else GONE
        styleRail.visibility = if (street) GONE else VISIBLE
        cameraArt.showHero(style)
        film.filmName = style.uiName(activity)
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
        comparisonCaption.text = activity.getString(R.string.purchase_sample_caption, style.name(activity))
        renderedSample.setImageDrawable(styleSample.drawable?.constantState?.newDrawable(resources))
        val sampleDescription = activity.getString(R.string.purchase_sample_style, style.name(activity))
        renderedSample.contentDescription = sampleDescription
        designSample.contentDescription = activity.getString(R.string.design_purchase_illustration)
        if (style == CameraStyle.PLASTIC_82) designSample.showSample(style)
        designSample.visibility = if (style == CameraStyle.PLASTIC_82) VISIBLE else GONE
        renderedSample.visibility = if (style == CameraStyle.PLASTIC_82) GONE else VISIBLE
        if (changedStyle) setComparisonExpanded(false)
        update(ui)
        updateLayout(if (width > 0) width - paddingLeft - paddingRight else dp(resources.configuration.screenWidthDp),
            if (height > 0) height - paddingTop - paddingBottom else dp(resources.configuration.screenHeightDp))
        if (isShowing()) {
            if (changedStyle) scroll.post { if (isShowing()) scroll.scrollTo(0, 0) }
            revealSelectedCard(style)
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
        revealSelectedCard(style)
        topBar.backButton.requestFocus()
        if (Build.VERSION.SDK_INT < 28) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.text.add(activity.getString(R.string.purchase_title))
            sendAccessibilityEventUnchecked(event)
        }
    }

    fun update(ui: CameraPurchaseUi) {
        val ownedChanged = lastUi.owned != ui.owned
        lastUi = ui
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
            ui.canBuy && !ui.price.isNullOrBlank() -> activity.getString(R.string.design_purchase_unlock, ui.price)
            else -> activity.getString(R.string.purchase_buy_unavailable)
        }
        buy.visibility = if (ui.owned) GONE else VISIBLE
        buy.isEnabled = ui.canBuy && !ui.price.isNullOrBlank() && !ui.busy && !ui.pending
        buy.alpha = if (buy.isEnabled) 1f else 0.5f
        preview.setText(if (ui.owned) R.string.purchase_use_camera else R.string.purchase_preview)
        preview.background = ToviTheme.actionBackground(activity, primary = ui.owned)
        preview.setTextColor(if (ui.owned) ToviTheme.SURFACE else ToviTheme.TEXT)
        positionPreview(ui.owned)
        oneTime.visibility = if (ui.owned) GONE else VISIBLE
        previewHint.visibility = if (ui.owned) GONE else VISIBLE
        details.visibility = if (ui.owned) GONE else VISIBLE
        restore.isEnabled = !ui.busy
        restore.alpha = if (restore.isEnabled) 1f else 0.5f
        retry.visibility = if (!ui.owned && !ui.canBuy && !ui.pending) VISIBLE else GONE
        retry.isEnabled = !ui.busy
        retry.alpha = if (retry.isEnabled) 1f else 0.5f
        styleCards.forEach { (style, card) ->
            card.update(selected = style == currentStyle,
                unlocked = (style == currentStyle && ui.owned) || isStyleUnlocked(style), enabled = !ui.busy)
        }
        if (ownedChanged && width > 0) updateLayout(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
    }

    private fun positionPreview(owned: Boolean) {
        val destination = if (owned) purchaseActions else auxiliaryActions
        if (preview.parent === destination) return
        (preview.parent as? ViewGroup)?.removeView(preview)
        destination.addView(preview, 0, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun revealSelectedCard(style: CameraStyle) {
        styleRail.post {
            if (!isShowing() || currentStyle != style) return@post
            val card = styleCards[style] ?: return@post
            if (card.left < styleRail.scrollX) styleRail.scrollTo(card.left, 0)
            else if (card.right > styleRail.scrollX + styleRail.width) styleRail.scrollTo(card.right - styleRail.width, 0)
        }
    }

    private fun setComparisonExpanded(expanded: Boolean) {
        comparisonExpanded = expanded
        comparison.visibility = if (expanded) VISIBLE else GONE
        compare.setText(if (expanded) R.string.design_purchase_compare_hide else R.string.design_purchase_compare)
        if (Build.VERSION.SDK_INT >= 30) compare.stateDescription = activity.getString(
            if (expanded) R.string.design_purchase_expanded else R.string.design_purchase_collapsed)
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
        textSize = if (primary) 18f else 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(if (primary) 52 else 48)
        minimumHeight = minHeight
        minWidth = 0
        minimumWidth = 0
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(if (primary) ToviTheme.SURFACE else ToviTheme.TEXT)
        background = ToviTheme.actionBackground(activity, primary)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Decorative lock/arrow inside the native, store-state-controlled CTA. */
    private class PurchaseGlyph(private val chevron: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ToviTheme.SURFACE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 18f, bounds.height() / 22f)
            paint.style = Paint.Style.STROKE
            if (chevron) {
                path.reset(); path.moveTo(4f, 5f); path.lineTo(13f, 11f); path.lineTo(4f, 17f)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawRoundRect(5f, 2.5f, 13f, 13f, 4f, 4f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(2f, 10f, 16f, 21f, 2f, 2f, paint)
                paint.color = ToviTheme.PRIMARY
                canvas.drawCircle(9f, 14f, 1.4f, paint)
                canvas.drawRect(8.4f, 14f, 9.6f, 17f, paint)
                paint.color = ToviTheme.SURFACE
            }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** A wide illustration with native film markings, separate from captured photos. */
    private class FilmFrame(context: Context) : FrameLayout(context) {
        var filmName = ""
            set(value) { field = value; invalidate() }
        private val unit = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ToviTheme.PRIMARY
            textSize = 9 * unit
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val marker = Path()

        init {
            background = ToviTheme.card(context, 12, 0xFF050506.toInt()).apply {
                setStroke(unit.toInt().coerceAtLeast(1), 0x80FFC629.toInt())
            }
            clipToOutline = true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val targetHeight = (MeasureSpec.getSize(widthMeasureSpec) / 1.68f).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY))
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val save = canvas.save()
            canvas.rotate(-90f, 10 * unit, height / 2f)
            canvas.drawText(filmName, 10 * unit, height / 2f + paint.textSize / 3, paint)
            canvas.restoreToCount(save)
            canvas.drawText(filmName.takeLast(2), width - 8 * unit, height / 2f + paint.textSize / 3, paint)
            marker.reset()
            marker.moveTo(7 * unit, height * .18f)
            marker.lineTo(11 * unit, height * .18f + 7 * unit)
            marker.lineTo(3 * unit, height * .18f + 7 * unit)
            marker.close()
            canvas.drawPath(marker, paint)
        }
    }

    private class StyleCard(context: Context, private val style: CameraStyle) : LinearLayout(context) {
        private val unit = resources.displayMetrics.density
        private val artFrame = FrameLayout(context)
        private val art = CameraArtworkView(context).apply { setStyle(style) }
        private val lock = LockBadge(context)
        private val caption = TextView(context).apply {
            text = style.uiName(context).replace(" COLOR", "\nCOLOR").replace(" MONO", "\nMONO").let {
                if (style == CameraStyle.HARINEZUMI_2PP || style == CameraStyle.HARINEZUMI_2PP_MONO) it
                else it.replace(" ", "\n")
            }
            textSize = 10.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            id = when (style) {
                CameraStyle.HARINEZUMI_2PP -> R.id.purchase_style_dh_color
                CameraStyle.HARINEZUMI_2PP_MONO -> R.id.purchase_style_dh_mono
                CameraStyle.DIGITAL_01 -> R.id.purchase_style_digital
                CameraStyle.PLASTIC_82 -> R.id.purchase_style_plastic
                CameraStyle.STREET_84 -> R.id.purchase_style_street
                CameraStyle.FISHEYE_05 -> R.id.purchase_style_fisheye
            }
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            minimumWidth = (48 * unit).toInt()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            artFrame.clipToOutline = true
            artFrame.addView(art, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            artFrame.addView(lock, FrameLayout.LayoutParams((20 * unit).toInt(), (20 * unit).toInt(), Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, (4 * unit).toInt(), (4 * unit).toInt()) })
            addView(artFrame, LayoutParams(LayoutParams.MATCH_PARENT, (68 * unit).toInt()))
            addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (6 * unit).toInt() })
        }

        fun update(selected: Boolean, unlocked: Boolean, enabled: Boolean) {
            isSelected = selected
            isEnabled = enabled
            alpha = if (enabled) 1f else .5f
            artFrame.background = ToviTheme.card(context, 12).apply {
                setStroke((if (selected) 3 * unit else unit).toInt().coerceAtLeast(1),
                    if (selected) ToviTheme.PRIMARY else ToviTheme.BORDER)
            }
            val inset = (if (selected) 3 * unit else unit).toInt().coerceAtLeast(1)
            artFrame.setPadding(inset, inset, inset, inset)
            lock.visibility = if (unlocked) GONE else VISIBLE
            caption.setTextColor(if (selected) ToviTheme.PRIMARY else ToviTheme.TEXT)
            contentDescription = context.getString(if (unlocked) R.string.design_purchase_camera_unlocked
                else R.string.design_purchase_camera_locked, style.uiName(context))
        }

        fun setArtworkHeight(height: Int) {
            artFrame.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height)
        }
    }

    private class LockBadge(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val scale = width / 20f
            val save = canvas.save()
            canvas.scale(scale, scale)
            paint.style = Paint.Style.FILL
            paint.color = 0xE609090A.toInt()
            canvas.drawCircle(10f, 10f, 10f, paint)
            paint.color = ToviTheme.TEXT
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.7f
            canvas.drawRoundRect(5.5f, 9f, 14.5f, 16f, 1f, 1f, paint)
            path.reset(); path.moveTo(7f, 9f); path.lineTo(7f, 6.5f)
            path.cubicTo(7f, 2.5f, 13f, 2.5f, 13f, 6.5f); path.lineTo(13f, 9f)
            canvas.drawPath(path, paint)
            canvas.drawLine(10f, 11f, 10f, 13f, paint)
            canvas.restoreToCount(save)
        }
    }

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
