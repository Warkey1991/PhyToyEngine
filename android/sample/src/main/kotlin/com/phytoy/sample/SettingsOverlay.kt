package com.phytoy.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.widget.Button
import android.widget.FrameLayout
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

    fun isShowing(): Boolean = visibility == VISIBLE

    /** Refresh entitlement text in place without rebuilding controls or moving input focus. */
    fun refreshStyleAccess() {
        body.findViewById<Button>(R.id.settings_default_style)?.text = controlText(
            R.string.settings_default_style, styleLabel(settings.defaultStyle), R.string.settings_default_style_hint,
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
        page.addView(View(activity).apply { setBackgroundColor(0xFF292929.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        scroll.apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(20), dp(12), dp(20), dp(24))
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
        body.addView(label(R.string.settings_intro, 14f, MUTED).apply { setPadding(0, 0, 0, dp(12)) })
        section(R.string.settings_section_camera)
        choice(R.id.settings_default_style, R.string.settings_default_style, styleLabel(settings.defaultStyle),
            R.string.settings_default_style_hint) { chooseDefaultStyle() }
        toggle(R.id.settings_remember_style, R.string.settings_remember_style, R.string.settings_remember_style_hint,
            settings.rememberLastStyle) { change(settings.copy(rememberLastStyle = it)) }
        choice(R.id.settings_quality, R.string.settings_quality, activity.getString(settings.photoQuality.titleRes),
            R.string.settings_quality_hint) { chooseQuality() }

        section(R.string.settings_section_shooting)
        toggle(R.id.settings_grid, R.string.settings_grid, R.string.settings_grid_hint,
            settings.gridEnabled) { change(settings.copy(gridEnabled = it)) }
        toggle(R.id.settings_sound, R.string.settings_sound, R.string.settings_sound_hint,
            settings.soundEnabled) { change(settings.copy(soundEnabled = it)) }
        toggle(R.id.settings_haptics, R.string.settings_haptics, R.string.settings_haptics_hint,
            settings.hapticsEnabled) { change(settings.copy(hapticsEnabled = it)) }
        toggle(R.id.settings_review, R.string.settings_review, R.string.settings_review_hint,
            settings.reviewAfterCapture) { change(settings.copy(reviewAfterCapture = it)) }

        section(R.string.settings_section_purchases)
        action(R.id.settings_restore_purchases, R.string.settings_restore_purchases,
            R.string.settings_restore_purchases_hint) { onRestorePurchases() }

        section(R.string.settings_section_information)
        action(R.id.settings_about, R.string.settings_about, R.string.settings_about_hint) {
            ProductInfo.showAbout(activity)
        }
        action(R.id.settings_privacy, R.string.settings_privacy, R.string.settings_privacy_hint) {
            ProductInfo.showPrivacy(activity)
        }
        action(R.id.settings_licenses, R.string.settings_licenses, R.string.settings_licenses_hint) {
            ProductInfo.showLicenses(activity)
        }
        action(R.id.settings_reset, R.string.settings_reset, R.string.settings_reset_hint) { confirmReset() }
        body.addView(label(R.string.settings_local_only, 13f, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun section(title: Int) {
        body.addView(label(title, 12f, ACCENT).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, dp(12), 0, dp(8))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
    }

    private fun choice(id: Int, title: Int, value: String, hint: Int, click: () -> Unit) {
        val button = Button(activity).apply {
            this.id = id
            text = controlText(title, value, hint)
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 17f
            minHeight = dp(48)
            includeFontPadding = false
            setSingleLine(false)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(FOREGROUND)
            background = touchBackground(CARD, 14)
            setOnClickListener { click() }
        }
        addControl(button)
    }

    private fun action(id: Int, title: Int, hint: Int, click: () -> Unit) {
        val button = Button(activity).apply {
            this.id = id
            text = controlText(title, null, hint, if (id == R.id.settings_reset) ACCENT else FOREGROUND)
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 17f
            minHeight = dp(48)
            includeFontPadding = false
            setSingleLine(false)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(FOREGROUND)
            background = touchBackground(CARD, 14)
            setOnClickListener { click() }
        }
        addControl(button)
    }

    private fun toggle(id: Int, title: Int, hint: Int, checked: Boolean, changed: (Boolean) -> Unit) {
        val control = Switch(activity).apply {
            this.id = id
            text = controlText(title, null, hint)
            textSize = 17f
            minHeight = dp(48)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(false)
            setLineSpacing(dp(3).toFloat(), 1f)
            switchPadding = dp(16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(FOREGROUND)
            background = touchBackground(CARD, 14)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(FOREGROUND, FOREGROUND),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF846B3D.toInt(), 0xFF777777.toInt()),
            )
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        addControl(control)
    }

    private fun addControl(control: View) {
        body.addView(control, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    /** Keep one native, accessible control per setting, with text that can grow at large font sizes. */
    private fun controlText(title: Int, value: String?, hint: Int, titleColor: Int = FOREGROUND): CharSequence {
        val text = SpannableStringBuilder(activity.getString(title))
        text.setSpan(TypefaceSpan("sans-serif-medium"), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(titleColor), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        fun appendLine(line: String, size: Float, color: Int) {
            text.append('\n')
            val start = text.length
            text.append(line)
            text.setSpan(RelativeSizeSpan(size / 17f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        value?.let { appendLine(it, 15f, ACCENT) }
        appendLine(activity.getString(hint), 13f, MUTED)
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

    private fun touchBackground(color: Int, radius: Int): RippleDrawable {
        val surface = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), 0xFF30343B.toInt())
        }
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(radius).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x28F2B84B), surface, mask)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BACKGROUND = PageTopBar.SURFACE
        private const val CARD = 0xFF1D2026.toInt()
        private const val FOREGROUND = PageTopBar.ON_SURFACE
        private const val MUTED = PageTopBar.ON_SURFACE_VARIANT
        private const val ACCENT = PageTopBar.PRIMARY
    }
}
