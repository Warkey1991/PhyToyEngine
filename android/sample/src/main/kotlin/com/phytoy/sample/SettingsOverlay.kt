package com.phytoy.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** A native settings page. The activity owns camera pause/resume and applying changes. */
internal class SettingsOverlay(
    private val activity: Activity,
    private val store: SettingsStore,
    var onDismiss: () -> Unit = {},
    var onChanged: (SettingsSnapshot) -> Unit = {},
    var onRestorePurchases: () -> Unit = {},
    var canUseStyle: (CameraStyle) -> Boolean = { true },
    var onLockedStyleSelected: (CameraStyle) -> Unit = {},
) : FrameLayout(activity) {
    private val body = LinearLayout(activity)
    private val scroll = ScrollView(activity)
    private val topBar = PageTopBar(activity)
    private val back = topBar.backButton
    private val hiddenAccessibility = linkedMapOf<View, Int>()
    private var previousFocus: View? = null
    private var moreShowing = false
    private var mainScrollY = 0
    private var choiceDialog: AlertDialog? = null
    private var settings = store.load()
    private var purchaseResult: CharSequence? = null
    private var activeGroup: LinearLayout? = null
    private var defaultStyleValue: TextView? = null
    private var defaultStyleControl: View? = null
    private val purchaseNotice = TextView(activity).apply {
        id = R.id.settings_purchase_result
        textSize = 11f
        setTextColor(FOREGROUND)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = ToviTheme.card(activity, 12)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = GONE
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    fun showPurchaseResult(message: CharSequence) {
        purchaseResult = message
        purchaseNotice.text = message
        purchaseNotice.visibility = VISIBLE
    }

    /** Refresh entitlement text in place without rebuilding controls or moving input focus. */
    fun refreshStyleAccess() {
        val value = styleLabel(settings.defaultStyle)
        defaultStyleValue?.text = value
        defaultStyleControl?.contentDescription = rowDescription(
            R.string.design_settings_default_camera, value, R.string.design_settings_default_hint,
        )
    }

    init {
        id = R.id.settings_page
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = pageBackground()
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = activity.getString(R.string.settings_title)

        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        back.apply {
            id = R.id.settings_back
            contentDescription = activity.getString(R.string.settings_back)
        }
        topBar.titleView.apply {
            text = activity.getString(R.string.settings_title)
        }
        topBar.subtitleView.visibility = GONE
        topBar.setOnBackClickListener { handleBack() }
        topBar.addView(ImageView(activity).apply {
            id = R.id.design_settings_help_header
            setImageDrawable(SettingsIconDrawable(activity, SettingsRowIcon.HELP, 20, false))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = activity.getString(R.string.design_settings_help_description)
            isClickable = true
            isFocusable = true
            background = touchBackground()
            accessibilityDelegate = actionAccessibilityDelegate()
            setOnClickListener { showSettingsHelp() }
        }, LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(10)
        })
        page.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        scroll.apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        page.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
        rebuild()
    }

    fun show() {
        if (isShowing()) return
        settings = store.load()
        moreShowing = false
        mainScrollY = 0
        rebuild()
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
        scroll.post { if (isShowing()) scroll.scrollTo(0, 0) }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        requestApplyInsets()
        back.requestFocus()
        announceForAccessibility(activity.getString(R.string.settings_title))
    }

    fun dismiss(): Boolean {
        if (!isShowing()) return false
        choiceDialog?.dismiss()
        choiceDialog = null
        ProductInfo.dismiss()
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        hiddenAccessibility.forEach { (view, previous) -> view.importantForAccessibility = previous }
        hiddenAccessibility.clear()
        previousFocus?.takeIf { it.isShown }?.requestFocus()
        previousFocus = null
        onDismiss()
        return true
    }

    /** Both the toolbar and system Back pop the secondary page first. */
    fun handleBack(): Boolean {
        if (!isShowing()) return false
        if (!moreShowing) return dismiss()
        moreShowing = false
        rebuild()
        scroll.post {
            body.findViewById<View>(R.id.settings_more)?.requestFocus()
            scroll.scrollTo(0, mainScrollY)
        }
        announceForAccessibility(activity.getString(R.string.settings_title))
        return true
    }

    private fun showMore() {
        mainScrollY = scroll.scrollY
        moreShowing = true
        rebuild()
        scroll.post { scroll.scrollTo(0, 0) }
        back.requestFocus()
        announceForAccessibility(activity.getString(R.string.design_settings_more))
    }

    private fun change(updated: SettingsSnapshot, refresh: Boolean = false) {
        settings = updated
        store.update(updated)
        if (refresh) rebuild()
        onChanged(updated)
    }

    private fun rebuild() {
        body.removeAllViews()
        activeGroup = null
        defaultStyleControl = null
        defaultStyleValue = null
        body.id = if (moreShowing) R.id.settings_more_page else R.id.settings_main_page
        topBar.titleView.setText(if (moreShowing) R.string.design_settings_more else R.string.settings_title)
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = topBar.titleView.text

        if (moreShowing) {
            section(R.string.design_settings_camera_preferences)
            choice(R.id.settings_default_style, R.string.design_settings_default_camera, styleLabel(settings.defaultStyle),
                R.string.design_settings_default_hint, SettingsRowIcon.CAMERA) { chooseDefaultStyle() }
            toggle(R.id.settings_remember_style, R.string.design_settings_remember_camera, R.string.design_settings_remember_hint,
                settings.rememberLastStyle, SettingsRowIcon.HISTORY) { change(settings.copy(rememberLastStyle = it)) }
            section(R.string.design_settings_app_preferences)
            action(R.id.settings_licenses, R.string.settings_licenses, R.string.design_settings_licenses_hint, SettingsRowIcon.LICENSE) {
                ProductInfo.showLicenses(activity)
            }
            action(R.id.settings_reset, R.string.settings_reset, R.string.design_settings_reset_hint,
                SettingsRowIcon.RESET) { confirmReset() }
            return
        }

        section(R.string.settings_section_shooting)
        choice(R.id.settings_quality, R.string.design_settings_save_resolution, qualityLabel(),
            R.string.design_settings_resolution_hint, SettingsRowIcon.PHOTO) { chooseQuality() }
        toggle(R.id.settings_grid, R.string.design_settings_grid, R.string.design_settings_grid_hint,
            settings.gridEnabled, SettingsRowIcon.GRID) { change(settings.copy(gridEnabled = it)) }

        section(R.string.settings_section_experience)
        toggle(R.id.settings_haptics, R.string.design_settings_haptics, R.string.design_settings_haptics_hint,
            settings.hapticsEnabled, SettingsRowIcon.HAPTICS) { change(settings.copy(hapticsEnabled = it)) }
        toggle(R.id.settings_sound, R.string.settings_sound, R.string.design_settings_sound_hint,
            settings.soundEnabled, SettingsRowIcon.SOUND) { change(settings.copy(soundEnabled = it)) }
        toggle(R.id.settings_review, R.string.settings_review, R.string.design_settings_review_hint,
            settings.reviewAfterCapture, SettingsRowIcon.REVIEW) { change(settings.copy(reviewAfterCapture = it)) }

        section(R.string.settings_section_information)
        action(R.id.settings_restore_purchases, R.string.settings_restore_purchases,
            R.string.design_settings_restore_hint, SettingsRowIcon.PURCHASES) { onRestorePurchases() }
        (purchaseNotice.parent as? ViewGroup)?.removeView(purchaseNotice)
        purchaseNotice.text = purchaseResult ?: ""
        purchaseNotice.visibility = if (purchaseResult.isNullOrBlank()) GONE else VISIBLE
        activeGroup?.addView(purchaseNotice, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(6), 0, dp(6), dp(6)) })
        action(R.id.settings_privacy, R.string.settings_privacy, R.string.design_settings_privacy_hint, SettingsRowIcon.PRIVACY) {
            ProductInfo.showPrivacy(activity)
        }
        if (SupportInfo.hasEmail) {
            action(R.id.settings_support, R.string.design_settings_help_feedback,
                R.string.design_settings_feedback_hint, SettingsRowIcon.HELP) { SupportInfo.email(activity) }
        } else {
            action(R.id.design_settings_help, R.string.design_settings_help,
                R.string.design_settings_help_hint, SettingsRowIcon.HELP) { showSettingsHelp() }
        }

        action(R.id.settings_about, R.string.settings_about, R.string.design_settings_about_hint, SettingsRowIcon.INFO) {
            ProductInfo.showAbout(activity)
        }
        action(R.id.settings_more, R.string.design_settings_more, R.string.design_settings_more_hint,
            SettingsRowIcon.MORE) { showMore() }
        body.addView(label(R.string.settings_local_only, 10.5f, MUTED).apply {
            setPadding(dp(6), dp(14), dp(6), 0)
        })
    }

    private fun showSettingsHelp() {
        choiceDialog?.dismiss()
        choiceDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.design_settings_help_description)
            .setMessage(R.string.settings_camera_help)
            .setPositiveButton(R.string.product_info_close, null)
            .show()
    }

    private fun qualityLabel(): String = activity.getString(when (settings.photoQuality) {
        PhotoQuality.HIGH -> R.string.design_settings_quality_high
        PhotoQuality.BALANCED -> R.string.design_settings_quality_balanced
        PhotoQuality.COMPACT -> R.string.design_settings_quality_compact
    })

    private fun section(title: Int) {
        body.addView(label(title, 13f, MUTED).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(dp(6), dp(if (activeGroup == null) 8 else 16), 0, dp(7))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        activeGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = groupBackground()
            clipToOutline = true
        }.also { body.addView(it, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    private fun choice(id: Int, title: Int, value: String, hint: Int, icon: SettingsRowIcon, click: () -> Unit) {
        val (control, valueView) = actionRow(id, title, hint, icon, value, click)
        if (id == R.id.settings_default_style) {
            defaultStyleControl = control
            defaultStyleValue = valueView
        }
        addControl(control)
    }

    private fun action(id: Int, title: Int, hint: Int, icon: SettingsRowIcon, click: () -> Unit) {
        addControl(actionRow(id, title, hint, icon, null, click).first)
    }

    /** One focusable action per row; decorative children cannot duplicate TalkBack announcements. */
    private fun actionRow(
        id: Int, title: Int, hint: Int, icon: SettingsRowIcon, value: String?, click: () -> Unit,
    ): Pair<View, TextView?> {
        val row = LinearLayout(activity).apply {
            this.id = id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(6), dp(7), dp(6), dp(7))
            isClickable = true
            isFocusable = true
            background = touchBackground()
            contentDescription = rowDescription(title, value, hint)
            accessibilityDelegate = actionAccessibilityDelegate()
            setOnClickListener { click() }
        }
        row.addView(ImageView(activity).apply {
            setImageDrawable(SettingsIconDrawable(activity, icon))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })

        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        copy.addView(label(title, 13f, FOREGROUND).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        })
        copy.addView(label(hint, 10.5f, MUTED).apply {
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val valueView = value?.let {
            TextView(activity).apply {
                text = it
                textSize = 11.5f
                setTextColor(MUTED)
                includeFontPadding = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }.also { text ->
                // Let the current value wrap below the label when scaled text needs more room.
                if (resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 340) {
                    copy.addView(text, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(5)
                    })
                } else {
                    text.gravity = Gravity.END
                    text.maxWidth = dp(116)
                    row.addView(text, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(8)
                    })
                }
            }
        }
        row.addView(ImageView(activity).apply {
            setImageDrawable(SettingsIconDrawable(activity, SettingsRowIcon.CHEVRON))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleX = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f
        }, LinearLayout.LayoutParams(dp(12), dp(20)).apply { marginStart = dp(6) })
        return row to valueView
    }

    private fun toggle(
        id: Int, title: Int, hint: Int, checked: Boolean, icon: SettingsRowIcon, changed: (Boolean) -> Unit,
    ) {
        val control = Switch(activity).apply {
            this.id = id
            text = controlText(title, hint)
            textSize = 13f
            minHeight = dp(56)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(false)
            setLineSpacing(dp(1).toFloat(), 1f)
            switchPadding = dp(10)
            setPadding(dp(6), dp(7), dp(6), dp(7))
            setTextColor(FOREGROUND)
            background = touchBackground()
            setCompoundDrawablesRelativeWithIntrinsicBounds(SettingsIconDrawable(activity, icon), null, null, null)
            compoundDrawablePadding = dp(10)
            showText = false
            splitTrack = false
            switchMinWidth = dp(42)
            thumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FOREGROUND)
                setSize(dp(22), dp(22))
            }
            thumbTintList = ColorStateList.valueOf(FOREGROUND)
            trackDrawable = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), switchTrack(ACCENT))
                addState(intArrayOf(), switchTrack(0xFF44464C.toInt()))
            }
            trackTintList = null
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        addControl(control)
    }

    private fun switchTrack(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
        setSize(dp(42), dp(24))
    }

    private fun addControl(control: View) {
        val group = activeGroup ?: return
        if (group.childCount > 0) group.addView(View(activity).apply {
            setBackgroundColor(0xFF292B2F.toInt())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        group.addView(control, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rowDescription(title: Int, value: String?, hint: Int): String = listOfNotNull(
        displayText(title), value, activity.getString(if (hint == R.string.design_settings_restore_hint)
            R.string.settings_restore_purchases_hint else hint),
    ).joinToString(", ")

    private fun controlText(title: Int, hint: Int): CharSequence {
        val text = SpannableStringBuilder(displayText(title))
        text.setSpan(TypefaceSpan("sans-serif-medium"), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append('\n')
        val start = text.length
        text.append(displayText(hint))
        text.setSpan(RelativeSizeSpan(10.5f / 13f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(MUTED), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun styleLabel(style: CameraStyle): String {
        val name = style.uiName(activity)
        return if (canUseStyle(style)) name else activity.getString(R.string.settings_style_locked, name)
    }

    private fun chooseDefaultStyle() {
        val styles = CameraStyle.entries
        showChoice(R.string.settings_default_style, styles.map(::styleLabel).toTypedArray(),
            styles.indexOf(settings.defaultStyle)) { index ->
            val selected = styles[index]
            if (canUseStyle(selected)) change(settings.copy(defaultStyle = selected), true)
            else onLockedStyleSelected(selected)
        }
    }

    private fun chooseQuality() {
        val qualities = PhotoQuality.entries
        showChoice(R.string.design_settings_save_resolution, qualities.map { activity.getString(it.titleRes) }.toTypedArray(),
            qualities.indexOf(settings.photoQuality)) { index -> change(settings.copy(photoQuality = qualities[index]), true) }
    }

    private fun showChoice(title: Int, labels: Array<String>, selected: Int, chosen: (Int) -> Unit) {
        choiceDialog?.dismiss()
        choiceDialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                dialog.dismiss()
                choiceDialog = null
                chosen(index)
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun confirmReset() {
        choiceDialog?.dismiss()
        choiceDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.settings_reset_confirm_title)
            .setMessage(R.string.settings_reset_confirm_body)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_reset) { _, _ ->
                settings = store.reset()
                rebuild()
                onChanged(settings)
                announceForAccessibility(activity.getString(R.string.settings_reset_done))
                choiceDialog = null
            }
            .show()
    }

    private fun label(text: Int, size: Float, color: Int) = TextView(activity).apply {
        setText(displayText(text))
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun displayText(resource: Int): String = if (resource == R.string.design_settings_restore_hint) {
        activity.getString(resource, activity.getString(R.string.billing_store_name))
    } else activity.getString(resource)

    private fun actionAccessibilityDelegate() = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    private fun groupBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF151618.toInt(), 0xFF101113.toInt(), 0xFF0C0D0F.toInt()),
    ).apply {
        cornerRadius = dp(17).toFloat()
        setStroke(1, 0xFF2D2E32.toInt())
    }

    private fun pageBackground(): LayerDrawable {
        fun glow(x: Float, y: Float) = GradientDrawable().apply {
            colors = intArrayOf(0x16C98C14, 0x00C98C14)
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(190).toFloat()
            setGradientCenter(x, y)
        }
        return LayerDrawable(arrayOf(ColorDrawable(BACKGROUND), glow(1f, 0f), glow(0f, 0.9f)))
    }

    private fun touchBackground(): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(12).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x24FFC629), null, mask)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = ToviTheme.SURFACE
        private val FOREGROUND = ToviTheme.TEXT
        private val MUTED = ToviTheme.MUTED
        private val ACCENT = ToviTheme.PRIMARY
    }
}
