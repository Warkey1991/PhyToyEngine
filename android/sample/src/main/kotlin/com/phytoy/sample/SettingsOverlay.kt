package com.phytoy.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private var choiceDialog: AlertDialog? = null
    private var settings = store.load()
    private var purchaseResult: CharSequence? = null
    private var activeGroup: LinearLayout? = null
    private var defaultStyleValue: TextView? = null
    private var defaultStyleControl: View? = null
    private val purchaseNotice = TextView(activity).apply {
        id = R.id.settings_purchase_result
        textSize = 14f
        setTextColor(FOREGROUND)
        setPadding(dp(16), dp(12), dp(16), dp(12))
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
            R.string.settings_default_style, value, R.string.settings_default_style_short,
        )
    }

    init {
        id = R.id.settings_page
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(BACKGROUND)
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
        topBar.setOnBackClickListener { dismiss() }
        page.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        scroll.apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(28))
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

        section(R.string.settings_section_shooting)
        choice(R.id.settings_default_style, R.string.settings_default_style, styleLabel(settings.defaultStyle),
            R.string.settings_default_style_short, SettingsRowIcon.CAMERA) { chooseDefaultStyle() }
        toggle(R.id.settings_remember_style, R.string.settings_remember_style, R.string.settings_remember_short,
            settings.rememberLastStyle, SettingsRowIcon.HISTORY) { change(settings.copy(rememberLastStyle = it)) }
        choice(R.id.settings_quality, R.string.settings_quality, activity.getString(settings.photoQuality.titleRes),
            R.string.settings_quality_short, SettingsRowIcon.PHOTO) { chooseQuality() }
        toggle(R.id.settings_grid, R.string.settings_grid, R.string.settings_grid_short,
            settings.gridEnabled, SettingsRowIcon.GRID) { change(settings.copy(gridEnabled = it)) }
        addControl(Button(activity).apply {
            text = activity.getString(R.string.settings_options_help)
            isAllCaps = false
            textSize = 13f
            minHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(ACCENT)
            background = touchBackground()
            setOnClickListener {
                choiceDialog?.dismiss()
                choiceDialog = AlertDialog.Builder(activity).setTitle(R.string.settings_section_shooting)
                    .setMessage(R.string.settings_camera_help)
                    .setPositiveButton(R.string.product_info_close, null).show()
            }
        })

        section(R.string.settings_section_experience)
        toggle(R.id.settings_sound, R.string.settings_sound, R.string.settings_sound_short,
            settings.soundEnabled, SettingsRowIcon.SOUND) { change(settings.copy(soundEnabled = it)) }
        toggle(R.id.settings_haptics, R.string.settings_haptics, R.string.settings_haptics_short,
            settings.hapticsEnabled, SettingsRowIcon.HAPTICS) { change(settings.copy(hapticsEnabled = it)) }
        toggle(R.id.settings_review, R.string.settings_review, R.string.settings_review_short,
            settings.reviewAfterCapture, SettingsRowIcon.REVIEW) { change(settings.copy(reviewAfterCapture = it)) }

        section(R.string.settings_section_information)
        action(R.id.settings_restore_purchases, R.string.settings_restore_purchases,
            R.string.settings_restore_purchases_hint, SettingsRowIcon.PURCHASES) { onRestorePurchases() }
        (purchaseNotice.parent as? ViewGroup)?.removeView(purchaseNotice)
        purchaseNotice.text = purchaseResult ?: ""
        purchaseNotice.visibility = if (purchaseResult.isNullOrBlank()) GONE else VISIBLE
        activeGroup?.addView(purchaseNotice, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        action(R.id.settings_about, R.string.settings_about, R.string.settings_about_short, SettingsRowIcon.INFO) {
            ProductInfo.showAbout(activity)
        }
        action(R.id.settings_privacy, R.string.settings_privacy, R.string.settings_privacy_short, SettingsRowIcon.PRIVACY) {
            ProductInfo.showPrivacy(activity)
        }
        action(R.id.settings_licenses, R.string.settings_licenses, R.string.settings_licenses_short, SettingsRowIcon.LICENSE) {
            ProductInfo.showLicenses(activity)
        }
        if (SupportInfo.hasEmail) action(R.id.settings_support, R.string.support_title, R.string.support_hint,
            SettingsRowIcon.HELP) { SupportInfo.email(activity) }
        action(R.id.settings_reset, R.string.settings_reset, R.string.settings_reset_short,
            SettingsRowIcon.RESET) { confirmReset() }
        body.addView(label(R.string.settings_local_only, 12f, MUTED).apply {
            setPadding(dp(8), dp(18), dp(8), 0)
        })
    }

    private fun section(title: Int) {
        body.addView(label(title, 17f, MUTED).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(6), dp(20), 0, dp(10))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        activeGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = ToviTheme.card(activity)
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
            minimumHeight = dp(76)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
            background = touchBackground()
            contentDescription = rowDescription(title, value, hint)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            setOnClickListener { click() }
        }
        row.addView(ImageView(activity).apply {
            setImageDrawable(SettingsIconDrawable(activity, icon))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })

        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        copy.addView(label(title, 16f, FOREGROUND).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        })
        copy.addView(label(hint, 12f, MUTED).apply {
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val valueView = value?.let {
            TextView(activity).apply {
                text = it
                textSize = 13f
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
                    text.maxWidth = dp(104)
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
        }, LinearLayout.LayoutParams(dp(16), dp(24)).apply { marginStart = dp(8) })
        return row to valueView
    }

    private fun toggle(
        id: Int, title: Int, hint: Int, checked: Boolean, icon: SettingsRowIcon, changed: (Boolean) -> Unit,
    ) {
        val control = Switch(activity).apply {
            this.id = id
            text = controlText(title, hint)
            textSize = 16f
            minHeight = dp(76)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(false)
            setLineSpacing(dp(2).toFloat(), 1f)
            switchPadding = dp(12)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setTextColor(FOREGROUND)
            background = touchBackground()
            setCompoundDrawablesRelativeWithIntrinsicBounds(SettingsIconDrawable(activity, icon), null, null, null)
            compoundDrawablePadding = dp(12)
            showText = false
            splitTrack = false
            switchMinWidth = dp(48)
            thumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FOREGROUND)
                setSize(dp(24), dp(24))
            }
            thumbTintList = ColorStateList.valueOf(FOREGROUND)
            trackDrawable = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), switchTrack(ACCENT))
                addState(intArrayOf(), switchTrack(0xFF55555C.toInt()))
            }
            trackTintList = null
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        addControl(control)
    }

    private fun switchTrack(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        setSize(dp(48), dp(28))
    }

    private fun addControl(control: View) {
        val group = activeGroup ?: return
        if (group.childCount > 0) group.addView(View(activity).apply {
            setBackgroundColor(ToviTheme.BORDER)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(60)
            marginEnd = dp(8)
        })
        group.addView(control, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rowDescription(title: Int, value: String?, hint: Int): String = listOfNotNull(
        activity.getString(title), value, activity.getString(hint),
    ).joinToString(", ")

    private fun controlText(title: Int, hint: Int): CharSequence {
        val text = SpannableStringBuilder(activity.getString(title))
        text.setSpan(TypefaceSpan("sans-serif-medium"), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append('\n')
        val start = text.length
        text.append(activity.getString(hint))
        text.setSpan(RelativeSizeSpan(12f / 16f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(MUTED), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun styleLabel(style: CameraStyle): String {
        val name = style.name(activity)
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
        showChoice(R.string.settings_quality, qualities.map { activity.getString(it.titleRes) }.toTypedArray(),
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
        setText(text)
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(2).toFloat(), 1f)
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
