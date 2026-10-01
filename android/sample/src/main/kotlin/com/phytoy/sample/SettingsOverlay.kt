package com.phytoy.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
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
import android.widget.Switch
import android.widget.TextView

/** A native offline settings page. The activity owns camera pause/resume and applying changes. */
internal class SettingsOverlay(
    private val activity: Activity,
    private val store: SettingsStore,
    var onDismiss: () -> Unit = {},
    var onChanged: (SettingsSnapshot) -> Unit = {},
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
            setPadding(dp(20), dp(20), dp(20), dp(28))
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
        body.addView(label(R.string.settings_intro, 14f, MUTED).apply { setPadding(0, 0, 0, dp(24)) })
        section(R.string.settings_section_camera)
        choice(R.id.settings_default_style, R.string.settings_default_style, settings.defaultStyle.name(activity),
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
        body.addView(label(R.string.settings_local_only, 13f, MUTED).apply { setPadding(0, dp(20), 0, 0) })
    }

    private fun section(title: Int) {
        body.addView(label(title, 12f, ACCENT).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, dp(12), 0, dp(12))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
    }

    private fun choice(id: Int, title: Int, value: String, hint: Int, click: () -> Unit) {
        val button = Button(activity).apply {
            this.id = id
            text = activity.getString(R.string.settings_choice_value, activity.getString(title), value)
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 17f
            minHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(FOREGROUND)
            background = touchBackground(CARD, 14)
            setOnClickListener { click() }
        }
        addControl(button, hint)
    }

    private fun action(id: Int, title: Int, hint: Int, click: () -> Unit) {
        val button = Button(activity).apply {
            this.id = id
            text = activity.getString(title)
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 17f
            minHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(if (id == R.id.settings_reset) ACCENT else FOREGROUND)
            background = touchBackground(CARD, 14)
            setOnClickListener { click() }
        }
        addControl(button, hint)
    }

    private fun toggle(id: Int, title: Int, hint: Int, checked: Boolean, changed: (Boolean) -> Unit) {
        val control = Switch(activity).apply {
            this.id = id
            text = activity.getString(title)
            textSize = 17f
            minHeight = dp(56)
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
        addControl(control, hint)
    }

    private fun addControl(control: View, hint: Int) {
        body.addView(control, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(label(hint, 13f, MUTED).apply {
            setPadding(dp(4), dp(8), dp(4), dp(18))
        })
    }

    private fun chooseDefaultStyle() {
        val styles = CameraStyle.entries
        showChoice(R.string.settings_default_style, styles.map { it.name(activity) }.toTypedArray(),
            styles.indexOf(settings.defaultStyle)) { index -> change(settings.copy(defaultStyle = styles[index]), true) }
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
                chosen(index)
                dialog.dismiss()
                choiceDialog = null
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
