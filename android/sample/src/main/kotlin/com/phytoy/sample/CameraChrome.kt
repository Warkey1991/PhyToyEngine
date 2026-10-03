package com.phytoy.sample

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class CameraChrome(context: Context) : FrameLayout(context) {
    val metrics = TextView(context)
    val shutter: View
        get() = shutterControl
    val thumbnail = ImageView(context)
    val switchCamera: View
        get() = lensSwitchControl
    val flashMode: View
        get() = flashModeControl

    val topChromeDp = maxOf(64, (44f * resources.configuration.fontScale + 20f).toInt())
    private val styleCaptionHeightDp = maxOf(24, kotlin.math.ceil(Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }.fontSpacing / resources.displayMetrics.density).toInt() + 4)
    val bottomChromeDp = 178 + styleCaptionHeightDp
    private val shutterControl = ShutterView(context)
    private val lensSwitchControl = LensSwitchView(context)
    private val focusIndicator = FocusIndicatorView(context)
    private val captureFormat = TextView(context)
    private val styleTitle = TextView(context)
    private val styleDescription = TextView(context)
    private val styleScroller = HorizontalScrollView(context)
    private val flashModeControl = FlashControlView(context)
    private val header = LinearLayout(context)
    private val settingsControl = SettingsControlView(context)
    private val exposureControl = TextView(context)
    private val zoomControl = TextView(context)
    private val message = TextView(context)
    private val recoveryAction = TextView(context)
    private val messagePanel = LinearLayout(context)
    private val flash = View(context)
    private val styleControls = linkedMapOf<CameraStyle, StyleChipView>()
    private val clearMessage = Runnable { messagePanel.visibility = INVISIBLE }
    private var selectedStyle = CameraStyle.HARINEZUMI_2PP
    private var capturing = false
    private var captureMegapixels = 0.0
    private var styleSelectedListener: ((CameraStyle) -> Unit)? = null
    private var canUseStyle: (CameraStyle) -> Boolean = { true }
    private var stylePreviewOnly = false
    private var cameraReady = false
    private var lensSwitchAvailable = false
    private var flashAvailable = false
    private var exposureSupported = false
    private var exposureAdjustmentListener: ((Int) -> Unit)? = null
    private var zoomAdjustmentListener: ((Int) -> Unit)? = null
    private var hapticsEnabled = true

    fun setHapticsEnabled(enabled: Boolean) { hapticsEnabled = enabled }
    fun setOnSettingsClickListener(listener: () -> Unit) {
        settingsControl.setOnClickListener { if (!capturing) listener() }
    }

    init {
        isClickable = false
        fitsSystemWindows = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        metrics.id = R.id.engine_metrics
        shutterControl.id = R.id.shutter
        thumbnail.id = R.id.last_photo
        lensSwitchControl.id = R.id.switch_camera
        focusIndicator.id = R.id.focus_indicator
        flashModeControl.id = R.id.flash_mode
        exposureControl.id = R.id.exposure_value
        zoomControl.id = R.id.zoom_ratio
        settingsControl.id = R.id.open_settings
        recoveryAction.id = R.id.camera_recovery
        addScrims()
        addTopBar()
        addBottomControls()
        addView(
            focusIndicator,
            LayoutParams(dp(76), dp(76)).apply { focusIndicator.visibility = INVISIBLE },
        )
        addMetricsPanel()

        flash.setBackgroundColor(Color.WHITE)
        flash.alpha = 0f
        flash.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        focusIndicator.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(flash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        listOf(shutterControl, lensSwitchControl, flashModeControl, thumbnail, settingsControl, recoveryAction)
            .forEach { it.accessibilityDelegate = buttonAccessibilityDelegate() }

        setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
            insets
        }
    }

    fun setReady(ready: Boolean) {
        cameraReady = ready
        shutterControl.setReady(ready)
        lensSwitchControl.isEnabled = ready && lensSwitchAvailable && !capturing
        lensSwitchControl.alpha = if (lensSwitchControl.isEnabled) 1f else 0.35f
        flashModeControl.isEnabled = ready && flashAvailable && !capturing
        flashModeControl.alpha = if (flashModeControl.isEnabled) 1f else 0.35f
        styleTitle.text = selectedStyle.name(context)
        if (ready && !capturing) messagePanel.visibility = INVISIBLE
        updateShutterDescription()
        updateStyleControlState()
    }

    fun setCapturing() {
        capturing = true
        removeCallbacks(clearMessage)
        shutterControl.setCapturing(true)
        lensSwitchControl.isEnabled = false
        lensSwitchControl.alpha = 0.35f
        flashModeControl.isEnabled = false
        flashModeControl.alpha = 0.35f
        updateShutterDescription()
        showMessage(context.getString(R.string.preparing_capture))
        if (hapticsEnabled) shutterControl.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        updateStyleControlState()
    }

    fun updateCaptureMessage(text: CharSequence) {
        showMessage(text)
    }

    fun finishCapture() {
        capturing = false
        shutterControl.setCapturing(false)
        shutterControl.setReady(cameraReady)
        updateShutterDescription()
        lensSwitchControl.isEnabled = cameraReady && lensSwitchAvailable
        lensSwitchControl.alpha = if (lensSwitchControl.isEnabled) 1f else 0.35f
        flashModeControl.isEnabled = cameraReady && flashAvailable
        flashModeControl.alpha = if (flashModeControl.isEnabled) 1f else 0.35f
        updateStyleControlState()
    }

    fun setLensSwitchAvailable(available: Boolean) {
        lensSwitchAvailable = available
        lensSwitchControl.isEnabled = available && cameraReady && !capturing
        lensSwitchControl.alpha = if (lensSwitchControl.isEnabled) 1f else 0.35f
    }

    fun setLensFacing(front: Boolean) {
        lensSwitchControl.setFrontFacing(front)
        lensSwitchControl.contentDescription = context.getString(
            if (front) R.string.switch_to_back_camera else R.string.switch_to_front_camera
        )
    }

    fun setFlashAvailable(available: Boolean) {
        flashAvailable = available
        flashModeControl.isEnabled = available && cameraReady && !capturing
        flashModeControl.alpha = if (flashModeControl.isEnabled) 1f else 0.35f
        if (!available) {
            flashModeControl.contentDescription = context.getString(R.string.flash_unavailable)
        }
    }

    fun setFlashMode(mode: CameraFlashMode) {
        if (!flashAvailable) {
            flashModeControl.contentDescription = context.getString(R.string.flash_unavailable)
            return
        }
        flashModeControl.setMode(mode)
        val label = context.getString(
            when (mode) {
                CameraFlashMode.OFF -> R.string.flash_off
                CameraFlashMode.AUTO -> R.string.flash_auto
                CameraFlashMode.ON -> R.string.flash_on
            }
        )
        flashModeControl.contentDescription = context.getString(
            R.string.flash_mode_description, label,
        )
    }

    fun setExposureCompensation(ev: Float, supported: Boolean) {
        exposureControl.text = context.getString(R.string.exposure_value, ev)
        exposureControl.alpha = if (supported) 1f else 0.35f
        exposureSupported = supported
        exposureControl.contentDescription = if (supported) {
            context.getString(R.string.exposure_value_description, ev)
        } else {
            context.getString(R.string.exposure_unavailable_description)
        }
    }

    fun setZoomRatio(ratio: Float) {
        zoomControl.text = context.getString(R.string.zoom_ratio, ratio)
        zoomControl.contentDescription = context.getString(R.string.zoom_value_description, ratio)
    }

    fun setCaptureSize(width: Int, height: Int) {
        captureMegapixels = width.toLong() * height / 1_000_000.0
        updateCaptureFormat()
    }

    fun setOnStyleSelectedListener(listener: (CameraStyle) -> Unit) {
        styleSelectedListener = listener
    }

    /** Locked styles remain selectable for preview or purchase. */
    fun setStyleAvailability(canUse: (CameraStyle) -> Boolean) {
        canUseStyle = canUse
        updateStyleAvailability()
    }

    /** Display state only; the activity owns capture authorization. */
    fun setStylePreviewOnly(previewOnly: Boolean) {
        stylePreviewOnly = previewOnly
        updateStyleCaption()
        updateShutterDescription()
    }

    fun setOnExposureAdjustmentListener(listener: (Int) -> Unit) {
        exposureAdjustmentListener = listener
    }

    fun setOnZoomAdjustmentListener(listener: (Int) -> Unit) {
        zoomAdjustmentListener = listener
    }

    fun setStyle(style: CameraStyle) {
        selectedStyle = style
        styleTitle.text = style.name(context)
        updateStyleCaption()
        header.contentDescription = style.name(context)
        styleControls.forEach { (candidate, control) ->
            val selected = candidate == style
            control.isSelected = selected
            control.setTextColor(if (selected) ACCENT else 0xBFFFFFFF.toInt())
            control.invalidate()
        }
        updateStyleAvailability()
        styleScroller.post {
            styleControls[selectedStyle]?.let { chip ->
                styleScroller.smoothScrollTo(chip.left - (styleScroller.width - chip.width) / 2, 0)
            }
        }
        updateCaptureFormat()
    }

    private fun updateCaptureFormat() {
        captureFormat.text = context.getString(R.string.capture_resolution, captureMegapixels)
    }

    private fun updateStyleAvailability() {
        styleControls.forEach { (style, control) ->
            val locked = !canUseStyle(style)
            control.setLocked(locked)
            control.contentDescription = context.getString(
                when {
                    locked -> R.string.style_chip_locked_description
                    style == selectedStyle -> R.string.style_chip_selected_description
                    else -> R.string.style_chip_description
                },
                style.name(context),
            )
        }
    }

    private fun updateStyleCaption() {
        styleDescription.setText(if (stylePreviewOnly) R.string.billing_preview_only else selectedStyle.descriptionRes)
        styleDescription.setTextColor(if (stylePreviewOnly) ACCENT else 0xFF8E939B.toInt())
    }

    private fun updateShutterDescription() {
        shutterControl.contentDescription = context.getString(when {
            capturing -> R.string.camera_busy_description
            stylePreviewOnly -> R.string.billing_unlock_to_shoot
            else -> R.string.shutter_description
        })
    }

    private fun updateStyleControlState() {
        styleControls.values.forEach { control ->
            control.isEnabled = cameraReady && !capturing
            control.alpha = if (control.isEnabled) 1f else 0.45f
        }
    }

    fun showFocusIndicator(x: Float, y: Float) {
        focusIndicator.animate().cancel()
        focusIndicator.setSuccess(null)
        focusIndicator.x = x - focusIndicator.layoutParams.width / 2f
        focusIndicator.y = y - focusIndicator.layoutParams.height / 2f
        focusIndicator.alpha = 1f
        focusIndicator.scaleX = 1.35f
        focusIndicator.scaleY = 1.35f
        focusIndicator.visibility = VISIBLE
        focusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        if (hapticsEnabled) focusIndicator.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun completeFocus(success: Boolean) {
        focusIndicator.setSuccess(success)
        focusIndicator.animate().alpha(0.72f).setDuration(180L).start()
    }

    fun clearFocusIndicator() {
        focusIndicator.animate().cancel()
        focusIndicator.animate().alpha(0f).setDuration(180L).withEndAction {
            focusIndicator.visibility = INVISIBLE
        }.start()
    }

    fun showMessage(text: CharSequence, temporary: Boolean = false) {
        removeCallbacks(clearMessage)
        messagePanel.animate().cancel()
        messagePanel.alpha = 1f
        message.text = text
        messagePanel.visibility = VISIBLE
        recoveryAction.visibility = GONE
        recoveryAction.setOnClickListener(null)
        if (temporary) {
            postDelayed(clearMessage, messageTimeoutMillis())
        }
    }

    fun showRecoveryMessage(text: CharSequence, actionLabel: CharSequence, action: () -> Unit) {
        showMessage(text)
        recoveryAction.text = actionLabel
        recoveryAction.contentDescription = actionLabel
        recoveryAction.visibility = VISIBLE
        recoveryAction.setOnClickListener { action() }
    }

    fun showThumbnail(bitmap: Bitmap) {
        thumbnail.setImageBitmap(bitmap)
        thumbnail.imageAlpha = 255
    }

    fun flashCapture() {
        flash.animate().cancel()
        flash.alpha = 0.82f
        flash.animate().alpha(0f).setDuration(240L).start()
    }

    private fun addScrims() {
        addView(
            View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(PageTopBar.SURFACE, PageTopBar.SURFACE),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(topChromeDp), Gravity.TOP),
        )
        addView(
            View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(PageTopBar.SURFACE, PageTopBar.SURFACE),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(bottomChromeDp), Gravity.BOTTOM),
        )
    }

    private fun addTopBar() {
        header.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            setPadding(0, dp(8), 0, dp(8))
        }
        styleTitle.apply {
            setTextColor(PageTopBar.ON_SURFACE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(styleTitle)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(topChromeDp), Gravity.TOP or Gravity.START).apply {
            marginStart = dp(16)
            marginEnd = dp(120)
        })
        settingsControl.apply {
            contentDescription = context.getString(R.string.camera_settings_action)
            isClickable = true
            isFocusable = true
            background = touchBackground(Color.TRANSPARENT, dp(24).toFloat())
        }
        addView(settingsControl, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(8)
            topMargin = dp((topChromeDp - 48) / 2)
        })
        flashModeControl.apply {
            contentDescription = context.getString(R.string.flash_control_description)
            isClickable = true
            isFocusable = true
            background = touchBackground(Color.TRANSPARENT, dp(24).toFloat())
        }
        addView(flashModeControl, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(60)
            topMargin = dp((topChromeDp - 48) / 2)
        })
    }

    private fun addBottomControls() {
        zoomControl.apply {
            text = context.getString(R.string.zoom_ratio, 1.0)
            contentDescription = context.getString(R.string.zoom_control_description)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = adjustmentAccessibilityDelegate(isExposure = false)
            setTextColor(PageTopBar.ON_SURFACE)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            background = compactControlBackground()
        }
        addView(
            zoomControl,
            LayoutParams(dp(90), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(82)
            },
        )

        val styleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        CameraStyle.entries.forEach { style ->
            val chip = StyleChipView(context).apply {
                id = when (style) {
                    CameraStyle.HARINEZUMI_2PP -> R.id.style_dh_color
                    CameraStyle.HARINEZUMI_2PP_MONO -> R.id.style_dh_mono
                    CameraStyle.DIGITAL_01 -> R.id.style_digital
                    CameraStyle.PLASTIC_82 -> R.id.style_plastic
                    CameraStyle.STREET_84 -> R.id.style_street
                    CameraStyle.FISHEYE_05 -> R.id.style_fisheye
                }
                text = style.name(context)
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(12), 0, dp(12), dp(2))
                minimumWidth = dp(88)
                letterSpacing = 0.035f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans", Typeface.BOLD)
                includeFontPadding = false
                background = touchBackground(Color.TRANSPARENT, dp(12).toFloat())
                isClickable = true
                isFocusable = true
                accessibilityDelegate = buttonAccessibilityDelegate()
                setOnClickListener {
                    if (isEnabled && (style != selectedStyle || !canUseStyle(style))) {
                        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        styleSelectedListener?.invoke(style)
                    }
                }
            }
            styleControls[style] = chip
            styleRow.addView(
                chip,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(4) },
            )
        }
        styleScroller.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isHorizontalFadingEdgeEnabled = false
            addView(styleRow)
        }
        val styleStrip = FrameLayout(context)
        styleStrip.addView(styleScroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val fadeWidth = dp(24)
        val leftFade = View(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(PageTopBar.SURFACE, Color.TRANSPARENT))
        }
        val rightFade = View(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(PageTopBar.SURFACE, Color.TRANSPARENT))
        }
        styleStrip.addView(leftFade, LayoutParams(fadeWidth, LayoutParams.MATCH_PARENT, Gravity.LEFT))
        styleStrip.addView(rightFade, LayoutParams(fadeWidth, LayoutParams.MATCH_PARENT, Gravity.RIGHT))
        val updateFades = {
            val extent = (styleRow.width - styleScroller.width).coerceAtLeast(0)
            leftFade.alpha = (styleScroller.scrollX.toFloat() / fadeWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
            rightFade.alpha = ((extent - styleScroller.scrollX).toFloat() / fadeWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        styleScroller.setOnScrollChangeListener { _, _, _, _, _ -> updateFades() }
        styleScroller.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFades() }
        styleRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFades() }
        addView(
            styleStrip,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM).apply {
                bottomMargin = dp(130 + styleCaptionHeightDp)
            },
        )

        styleDescription.apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(18), 0, dp(18), 0)
        }
        addView(styleDescription, LayoutParams(LayoutParams.MATCH_PARENT, dp(styleCaptionHeightDp), Gravity.BOTTOM).apply {
            bottomMargin = dp(130)
        })

        exposureControl.apply {
            text = context.getString(R.string.exposure_value, 0.0)
            contentDescription = context.getString(R.string.exposure_control_description)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = adjustmentAccessibilityDelegate(isExposure = true)
            setTextColor(PageTopBar.ON_SURFACE)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            background = compactControlBackground()
        }
        addView(
            exposureControl,
            LayoutParams(dp(90), dp(48), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(18)
                bottomMargin = dp(82)
            },
        )

        message.apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(12), dp(16), dp(12))
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        recoveryAction.apply {
            setTextColor(ACCENT)
            textSize = 14f
            typeface = Typeface.create("sans", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            minWidth = dp(120)
            background = touchBackground(0x14FFFFFF, dp(12).toFloat())
            visibility = GONE
            isFocusable = true
        }
        messagePanel.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
            background = rounded(0xF2151515.toInt(), dp(14).toFloat(), 0x24FFFFFF, dp(1))
            visibility = INVISIBLE
            addView(message, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(recoveryAction, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        }
        addView(messagePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginStart = dp(24)
            marginEnd = dp(24)
            bottomMargin = dp(bottomChromeDp + 12)
        })

        updateShutterDescription()
        addView(shutterControl, LayoutParams(dp(78), dp(78), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(4)
        })

        thumbnail.apply {
            contentDescription = context.getString(R.string.gallery_description)
            scaleType = ImageView.ScaleType.CENTER_CROP
            imageAlpha = 110
            setImageResource(R.drawable.ic_gallery_placeholder)
            background = touchBackground(0x14FFFFFF, dp(12).toFloat(), 0x42FFFFFF)
            clipToOutline = true
            isClickable = true
            isFocusable = true
        }
        addView(thumbnail, LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(24)
            bottomMargin = dp(18)
        })

        captureFormat.apply {
            text = context.getString(R.string.capture_resolution, 0.0)
            setTextColor(0xFF8E939B.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(captureFormat, LayoutParams(dp(90), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(18)
            bottomMargin = dp(82)
        })

        lensSwitchControl.apply {
            contentDescription = context.getString(R.string.switch_to_front_camera)
            isClickable = true
            isFocusable = true
        }
        addView(lensSwitchControl, LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(24)
            bottomMargin = dp(18)
        })
        setStyle(selectedStyle)
        updateStyleControlState()
    }

    private fun addMetricsPanel() {
        metrics.apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            text = context.getString(R.string.metrics_waiting)
            background = rounded(0xD9000000.toInt(), dp(14).toFloat(), 0x40FFFFFF, dp(1))
            movementMethod = ScrollingMovementMethod()
            visibility = GONE
        }
        addView(metrics, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270), Gravity.BOTTOM).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = dp(bottomChromeDp + 12)
        })
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val available = height - paddingTop - paddingBottom - dp(topChromeDp + bottomChromeDp + 24)
        val panelHeight = minOf(dp(270), available.coerceAtLeast(dp(48)))
        if (metrics.layoutParams.height != panelHeight) {
            metrics.layoutParams = (metrics.layoutParams as LayoutParams).apply { this.height = panelHeight }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buttonAccessibilityDelegate() = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    private fun adjustmentAccessibilityDelegate(isExposure: Boolean) = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val listener = if (isExposure) exposureAdjustmentListener else zoomAdjustmentListener
            val available = cameraReady && !capturing && (!isExposure || exposureSupported)
            info.isEnabled = available
            if (listener != null && available) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    R.id.increase_value,
                    context.getString(if (isExposure) R.string.exposure_increase else R.string.zoom_increase),
                ))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    R.id.decrease_value,
                    context.getString(if (isExposure) R.string.exposure_decrease else R.string.zoom_decrease),
                ))
            }
        }

        override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
            val direction = when (action) {
                R.id.increase_value -> 1
                R.id.decrease_value -> -1
                else -> return super.performAccessibilityAction(host, action, arguments)
            }
            if (!cameraReady || capturing || (isExposure && !exposureSupported)) return false
            val listener = if (isExposure) exposureAdjustmentListener else zoomAdjustmentListener
            listener?.invoke(direction) ?: return false
            if (hapticsEnabled) host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return true
        }
    }

    private fun messageTimeoutMillis(): Long {
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && accessibility != null) {
            accessibility.getRecommendedTimeoutMillis(
                MESSAGE_DURATION_MILLIS.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT,
            ).toLong()
        } else if (accessibility?.isTouchExplorationEnabled == true) {
            MESSAGE_DURATION_MILLIS * 3
        } else {
            MESSAGE_DURATION_MILLIS
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearMessage)
        messagePanel.animate().cancel()
        flash.animate().cancel()
        super.onDetachedFromWindow()
    }

    // Keep the full 48dp accessibility area while drawing a quieter 32dp pill.
    private fun compactControlBackground() = InsetDrawable(
        rounded(0xFF20242A.toInt(), dp(16).toFloat()),
        dp(4), dp(8), dp(4), dp(8),
    )

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

    private fun touchBackground(color: Int, radius: Float, strokeColor: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(0x30FFFFFF),
        rounded(color, radius, strokeColor, dp(1)),
        rounded(Color.WHITE, radius),
    )

    private class StyleChipView(context: Context) : TextView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            strokeCap = Paint.Cap.ROUND
            strokeWidth = resources.displayMetrics.density * 2f
        }
        private var locked = false
        private val lockBody = RectF()
        private val lockShackle = Path()

        fun setLocked(value: Boolean) {
            if (locked == value) return
            locked = value
            val unit = resources.displayMetrics.density
            setPaddingRelative(((if (locked) 34 else 12) * unit).toInt(), 0, (12 * unit).toInt(), (2 * unit).toInt())
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (locked) {
                val unit = resources.displayMetrics.density
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - 19f * unit else 19f * unit
                val y = height / 2f - unit
                paint.color = currentTextColor
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.4f * unit
                lockBody.set(x - 4f * unit, y - unit, x + 4f * unit, y + 6f * unit)
                canvas.drawRoundRect(lockBody, 1.2f * unit, 1.2f * unit, paint)
                lockShackle.reset()
                lockShackle.moveTo(x - 3f * unit, y - unit)
                lockShackle.lineTo(x - 3f * unit, y - 3f * unit)
                lockShackle.cubicTo(x - 3f * unit, y - 7f * unit, x + 3f * unit, y - 7f * unit, x + 3f * unit, y - 3f * unit)
                lockShackle.lineTo(x + 3f * unit, y - unit)
                canvas.drawPath(lockShackle, paint)
                canvas.drawPoint(x, y + 3f * unit, paint)
            }
            if (isSelected) {
                paint.color = ACCENT
                paint.strokeWidth = resources.displayMetrics.density * 2f
                val halfWidth = minOf(width * 0.20f, resources.displayMetrics.density * 18f)
                val baseline = height - resources.displayMetrics.density * 5f
                canvas.drawLine(width / 2f - halfWidth, baseline, width / 2f + halfWidth, baseline, paint)
            }
        }
    }

    private class FocusIndicatorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2f
            strokeCap = Paint.Cap.SQUARE
        }
        private var success: Boolean? = null

        fun setSuccess(value: Boolean?) {
            success = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = when (success) {
                true -> 0xFF8BE28B.toInt()
                false -> 0xFFFF786E.toInt()
                null -> ACCENT
            }
            val inset = width * 0.14f
            val edge = width * 0.24f
            val right = width - inset
            val bottom = height - inset
            canvas.drawLine(inset, inset, inset + edge, inset, paint)
            canvas.drawLine(inset, inset, inset, inset + edge, paint)
            canvas.drawLine(right, inset, right - edge, inset, paint)
            canvas.drawLine(right, inset, right, inset + edge, paint)
            canvas.drawLine(inset, bottom, inset + edge, bottom, paint)
            canvas.drawLine(inset, bottom, inset, bottom - edge, paint)
            canvas.drawLine(right, bottom, right - edge, bottom, paint)
            canvas.drawLine(right, bottom, right, bottom - edge, paint)
        }
    }

    private class LensSwitchView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = RectF()
        private val body = RectF()
        private var frontFacing = false

        fun setFrontFacing(value: Boolean) {
            frontFacing = value
            invalidate()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) * 0.31f
            paint.color = if (isPressed) 0x30FFFFFF else 0x14FFFFFF
            paint.style = Paint.Style.FILL
            canvas.drawCircle(centerX, centerY, minOf(width, height) * 0.48f, paint)
            paint.color = 0xD9FFFFFF.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * density
            paint.strokeCap = Paint.Cap.ROUND
            arc.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            canvas.drawArc(arc, 204f, 138f, false, paint)
            canvas.drawArc(arc, 24f, 138f, false, paint)
            val arrowX = radius * 0.95f
            val arrowY = radius * 0.31f
            val arrow = 3.5f * density
            canvas.drawLine(centerX + arrowX, centerY - arrowY, centerX + arrowX - arrow, centerY - arrowY, paint)
            canvas.drawLine(centerX + arrowX, centerY - arrowY, centerX + arrowX, centerY - arrowY - arrow, paint)
            canvas.drawLine(centerX - arrowX, centerY + arrowY, centerX - arrowX + arrow, centerY + arrowY, paint)
            canvas.drawLine(centerX - arrowX, centerY + arrowY, centerX - arrowX, centerY + arrowY + arrow, paint)

            body.set(centerX - 8f * density, centerY - 5f * density, centerX + 8f * density, centerY + 6f * density)
            canvas.drawRoundRect(body, 2f * density, 2f * density, paint)
            paint.color = if (frontFacing) ACCENT else 0xD9FFFFFF.toInt()
            canvas.drawCircle(centerX, centerY + density / 2f, 2.7f * density, paint)
        }
    }

    private class FlashControlView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bolt = Path()
        private var mode = CameraFlashMode.OFF

        fun setMode(value: CameraFlashMode) { mode = value; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = resources.displayMetrics.density
            val x = width / 2f
            val y = height / 2f
            bolt.reset()
            bolt.moveTo(x + 4f * unit, y - 11f * unit)
            bolt.lineTo(x - 7f * unit, y + unit)
            bolt.lineTo(x - unit, y + unit)
            bolt.lineTo(x - 4f * unit, y + 11f * unit)
            bolt.lineTo(x + 7f * unit, y - unit)
            bolt.lineTo(x + unit, y - unit)
            bolt.close()
            paint.color = if (mode == CameraFlashMode.ON) ACCENT else Color.WHITE
            paint.strokeWidth = unit * 1.8f
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = if (mode == CameraFlashMode.ON) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawPath(bolt, paint)
            if (mode == CameraFlashMode.OFF) {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(x - 11f * unit, y - 11f * unit, x + 11f * unit, y + 11f * unit, paint)
            } else if (mode == CameraFlashMode.AUTO) {
                paint.style = Paint.Style.FILL
                paint.textSize = 9f * unit
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("A", x + 7f * unit, y - 5f * unit, paint)
            }
        }
    }

    private class SettingsControlView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.7f
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = resources.displayMetrics.density
            val centerX = width / 2f
            val centerY = height / 2f
            for (row in -1..1) {
                val y = centerY + row * 7f * unit
                val knob = centerX + (if (row == 0) 4f else -4f) * unit
                canvas.drawLine(centerX - 10f * unit, y, knob - 2.5f * unit, y, paint)
                canvas.drawLine(knob + 2.5f * unit, y, centerX + 10f * unit, y, paint)
                canvas.drawCircle(knob, y, 2.5f * unit, paint)
            }
        }
    }

    private class ShutterView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var ready = false
        private var capturing = false

        init {
            isClickable = true
            isFocusable = true
        }

        fun setReady(value: Boolean) {
            ready = value
            isEnabled = value && !capturing
            alpha = if (value) 1f else 0.48f
            invalidate()
        }

        fun setCapturing(value: Boolean) {
            capturing = value
            if (value) {
                isEnabled = false
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(100L).start()
            } else {
                animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
            }
            invalidate()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) / 2f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.075f
            paint.color = Color.WHITE
            canvas.drawCircle(centerX, centerY, radius * 0.91f, paint)

            paint.style = Paint.Style.FILL
            paint.color = if (capturing || isPressed) ACCENT else 0xFFF7F4EC.toInt()
            canvas.drawCircle(centerX, centerY, radius * if (capturing) 0.58f else 0.72f, paint)

            if (ready && !capturing) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.025f
                paint.color = 0x55000000
                canvas.drawCircle(centerX, centerY, radius * 0.70f, paint)
            }
        }
    }

    companion object {
        private const val ACCENT = 0xFFF2B84B.toInt()
        private const val MESSAGE_DURATION_MILLIS = 3_500L
    }
}
