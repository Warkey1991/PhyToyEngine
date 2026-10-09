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
import android.widget.ScrollView
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

    private val portraitTopChromeDp = maxOf(60, (30f * resources.configuration.fontScale + 24f).toInt())
    private val compactTopChromeDp = maxOf(72, kotlin.math.ceil(Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }.fontSpacing / resources.displayMetrics.density).toInt() + 16)
    val topChromeDp: Int get() = if (compactLayout) compactTopChromeDp else portraitTopChromeDp
    val previewTopInsetDp: Int get() = if (compactLayout) topChromeDp else 0
    val previewBottomInsetDp: Int get() = if (compactLayout) bottomChromeDp else bottomChromeDp - styleRailDp
    private val styleCaptionHeightDp = maxOf(13, kotlin.math.ceil(Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }.fontSpacing / resources.displayMetrics.density).toInt() + 4)
    private val styleArtworkDp = if (resources.configuration.fontScale >= 1.5f) 42 else 48
    private val styleRailDp = styleArtworkDp + styleCaptionHeightDp * 2 + 8
    val bottomChromeDp: Int get() = if (compactLayout) 56 else styleRailDp + if (shortPortraitLayout) 82 else 94
    val sideChromeDp: Int get() = if (compactLayout) 128 else 0
    private var compactLayout = false
    private var shortPortraitLayout = false
    private val shutterControl = ShutterView(context)
    private val lensSwitchControl = LensSwitchView(context)
    private val focusIndicator = FocusIndicatorView(context)
    private val captureFormat = TextView(context)
    private val styleTitle = TextView(context)
    private val styleDescription = TextView(context)
    private val styleScroller = HorizontalScrollView(context)
    private val styleStrip = FrameLayout(context)
    private val flashModeControl = FlashControlView(context)
    private val header = LinearLayout(context)
    private val settingsControl = SettingsControlView(context)
    private val exposureControl = TextView(context)
    private val zoomControl = TextView(context)
    private val zoomPresets = LinearLayout(context)
    private val zoomPresetControls = linkedMapOf<Float, TextView>()
    private val message = TextView(context)
    private val recoveryAction = TextView(context)
    private val messagePanel = LinearLayout(context)
    private val flash = View(context)
    private val topScrim = View(context)
    private val bottomScrim = View(context)
    private val sideScrim = View(context)
    private val compactRailContent = FrameLayout(context)
    private val compactRailScroll = ScrollView(context).apply {
        isFillViewport = false
        overScrollMode = OVER_SCROLL_NEVER
        visibility = GONE
        addView(compactRailContent, LayoutParams(dp(128), LayoutParams.WRAP_CONTENT))
    }
    private val adjustmentPanel = CameraAdjustmentPanel(context)
    private val unlockControl = TextView(context)
    private val styleControls = linkedMapOf<CameraStyle, StyleChipView>()
    private val clearMessage = Runnable { messagePanel.visibility = INVISIBLE }
    private var selectedStyle = CameraStyle.HARINEZUMI_2PP
    private var capturing = false
    private var captureMegapixels = 0.0
    private var styleSelectedListener: ((CameraStyle) -> Unit)? = null
    private var canUseStyle: (CameraStyle) -> Boolean = { true }
    private var stylePreviewOnly = false
    private var cameraReady = false
    private var readyStatePublished = false
    private var lensSwitchAvailable = false
    private var flashAvailable = false
    private var exposureSupported = false
    private var exposureAdjustmentListener: ((Int) -> Unit)? = null
    private var zoomAdjustmentListener: ((Int) -> Unit)? = null
    private var exposureValueListener: ((Int) -> Unit)? = null
    private var zoomValueListener: ((Float) -> Unit)? = null
    private var unlockListener: (() -> Unit)? = null
    private var chromeSizeListener: (() -> Unit)? = null
    private var exposureMinimum = 0
    private var exposureMaximum = 0
    private var exposureStep = 0f
    private var exposureIndex = 0
    private var minimumZoom = 1f
    private var maximumZoom = 1f
    private var zoomRatio = 1f
    private var hintVisible = false
    private var firstHintDismiss: (() -> Unit)? = null
    private val clearFirstHint = Runnable { completeFirstUseHint() }
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
        addView(adjustmentPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = dp(bottomChromeDp + 8)
        })
        addView(compactRailScroll, LayoutParams(dp(128), LayoutParams.MATCH_PARENT, Gravity.END))

        flash.setBackgroundColor(Color.WHITE)
        flash.alpha = 0f
        flash.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        focusIndicator.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(flash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        listOf(shutterControl, lensSwitchControl, flashModeControl, thumbnail, settingsControl, recoveryAction)
            .forEach { it.accessibilityDelegate = buttonAccessibilityDelegate() }

        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val system = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(system.left, system.top, system.right, system.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            if (width > 0 && height > 0) {
                updateResponsiveLayout()
            }
            insets
        }
    }

    fun setReady(ready: Boolean) {
        if (readyStatePublished && cameraReady == ready) return
        readyStatePublished = true
        cameraReady = ready
        shutterControl.setReady(ready)
        lensSwitchControl.isEnabled = ready && lensSwitchAvailable && !capturing
        lensSwitchControl.alpha = if (lensSwitchControl.isEnabled) 1f else 0.35f
        flashModeControl.isEnabled = ready && flashAvailable && !capturing
        flashModeControl.alpha = if (flashModeControl.isEnabled) 1f else 0.35f
        styleTitle.text = selectedStyle.uiName(context)
        if (ready && !capturing && !hintVisible) messagePanel.visibility = INVISIBLE
        updateShutterDescription()
        updateStyleControlState()
    }

    fun setCapturing() {
        completeFirstUseHint()
        capturing = true
        hintVisible = false
        adjustmentPanel.dismiss()
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
        // The camera can remain ready throughout saving, so finish the progress
        // message here rather than relying on another readiness notification.
        if (recoveryAction.visibility != VISIBLE) {
            removeCallbacks(clearMessage)
            messagePanel.visibility = INVISIBLE
        }
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
        exposureIndex = if (exposureStep > 0f) kotlin.math.round(ev / exposureStep).toInt() else 0
        exposureControl.contentDescription = if (supported) {
            context.getString(R.string.camera_adjust_exposure_open, ev)
        } else {
            context.getString(R.string.exposure_unavailable_description)
        }
    }

    fun setZoomRatio(ratio: Float) {
        zoomRatio = ratio
        zoomControl.text = context.getString(R.string.zoom_ratio, ratio)
        zoomControl.contentDescription = context.getString(R.string.camera_adjust_zoom_open, ratio)
        updateZoomPresetState()
        updateZoomReadoutVisibility()
    }

    fun setExposureRange(minimum: Int, maximum: Int, step: Float) {
        adjustmentPanel.dismiss()
        exposureMinimum = minimum
        exposureMaximum = maximum
        exposureStep = step
    }

    fun setZoomRange(minimum: Float, maximum: Float) {
        adjustmentPanel.dismiss()
        minimumZoom = minimum
        maximumZoom = maximum
        rebuildZoomPresets()
    }

    fun setOnExposureValueSelectedListener(listener: (Int) -> Unit) { exposureValueListener = listener }
    fun setOnZoomValueSelectedListener(listener: (Float) -> Unit) { zoomValueListener = listener }
    fun setOnUnlockStyleClickListener(listener: () -> Unit) { unlockListener = listener }
    fun setOnChromeSizeChangedListener(listener: () -> Unit) { chromeSizeListener = listener }
    fun dismissAdjustment(): Boolean = adjustmentPanel.dismiss()

    fun showFirstUseHint(onDismiss: () -> Unit) {
        if (!cameraReady || capturing) return
        showMessage(context.getString(R.string.camera_first_use_compact))
        message.maxLines = if (resources.configuration.fontScale >= 1.5f) 4 else 2
        hintVisible = true
        firstHintDismiss = onDismiss
        postDelayed(clearFirstHint, messageTimeoutMillis())
    }

    private fun completeFirstUseHint() {
        if (!hintVisible) return
        removeCallbacks(clearFirstHint)
        hintVisible = false
        messagePanel.visibility = INVISIBLE
        val dismissed = firstHintDismiss
        firstHintDismiss = null
        dismissed?.invoke()
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
        styleTitle.text = style.uiName(context)
        updateStyleCaption()
        header.contentDescription = style.uiName(context)
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
        styleDescription.text = selectedStyle.uiTagline(context)
        styleDescription.setTextColor(if (stylePreviewOnly) ACCENT else ToviTheme.MUTED)
        unlockControl.visibility = if (stylePreviewOnly) VISIBLE else GONE
        captureFormat.visibility = GONE
        if (compactLayout && styleStrip.layoutParams != null) {
            styleStrip.layoutParams = (styleStrip.layoutParams as LayoutParams).apply { marginEnd = dp(sideChromeDp + if (stylePreviewOnly) 128 else 0) }
        }
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
        exposureControl.isEnabled = cameraReady && !capturing && exposureSupported
        exposureControl.alpha = if (exposureControl.isEnabled) 1f else 0.35f
        zoomControl.isEnabled = cameraReady && !capturing && maximumZoom > minimumZoom
        zoomControl.alpha = if (zoomControl.isEnabled) 1f else 0.35f
        unlockControl.isEnabled = !capturing
        settingsControl.isEnabled = !capturing
        header.isEnabled = !capturing
        updateZoomPresetState()
    }

    fun showFocusIndicator(x: Float, y: Float) {
        completeFirstUseHint()
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
        removeCallbacks(clearFirstHint)
        firstHintDismiss = null
        hintVisible = false
        removeCallbacks(clearMessage)
        messagePanel.animate().cancel()
        messagePanel.alpha = 1f
        message.text = text
        message.maxLines = 5
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

    fun clearThumbnail() {
        thumbnail.setImageResource(R.drawable.ic_gallery_placeholder)
        thumbnail.imageAlpha = 110
    }

    fun flashCapture() {
        flash.animate().cancel()
        flash.alpha = 0.82f
        flash.animate().alpha(0f).setDuration(240L).start()
    }

    private fun addScrims() {
        addView(
            topScrim.apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xC90B0B0C.toInt(), Color.TRANSPARENT),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(topChromeDp), Gravity.TOP),
        )
        addView(
            bottomScrim.apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(PageTopBar.SURFACE, PageTopBar.SURFACE),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(bottomChromeDp), Gravity.BOTTOM),
        )
        sideScrim.setBackgroundColor(PageTopBar.SURFACE)
        sideScrim.visibility = GONE
        addView(sideScrim, LayoutParams(dp(128), LayoutParams.MATCH_PARENT, Gravity.END))
    }

    private fun addTopBar() {
        header.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            setPadding(dp(10), dp(6), dp(8), dp(6))
            background = InsetDrawable(touchBackground(0xE60B0B0C.toInt(), dp(24).toFloat()), 0, dp(4), 0, dp(4))
            isClickable = true
            isFocusable = true
            accessibilityDelegate = buttonAccessibilityDelegate()
            setOnClickListener { if (!capturing) unlockListener?.invoke() }
        }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_gallery_camera)
            imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        styleTitle.apply {
            setTextColor(ACCENT)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        styleDescription.apply {
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(styleTitle)
            addView(styleDescription, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "⌄"
            textSize = 18f
            setTextColor(Color.WHITE)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(14), LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            marginStart = dp(12)
            marginEnd = dp(120)
            topMargin = dp(10)
        })
        settingsControl.apply {
            contentDescription = context.getString(R.string.camera_settings_action)
            isClickable = true
            isFocusable = true
            background = InsetDrawable(touchBackground(0xE60B0B0C.toInt(), dp(24).toFloat()), dp(6))
        }
        addView(settingsControl, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(8)
            topMargin = dp((topChromeDp - 48) / 2)
        })
        flashModeControl.apply {
            contentDescription = context.getString(R.string.flash_control_description)
            isClickable = true
            isFocusable = true
            background = InsetDrawable(touchBackground(0xE60B0B0C.toInt(), dp(24).toFloat()), dp(6))
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
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (cameraReady && !capturing && maximumZoom > minimumZoom) {
                    completeFirstUseHint()
                    adjustmentPanel.showZoom(zoomRatio, minimumZoom, maximumZoom) { zoomValueListener?.invoke(it) }
                    updateAdjustmentPanelBounds()
                }
            }
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
            val chip = StyleChipView(context, style, styleArtworkDp).apply {
                id = when (style) {
                    CameraStyle.HARINEZUMI_2PP -> R.id.style_dh_color
                    CameraStyle.HARINEZUMI_2PP_MONO -> R.id.style_dh_mono
                    CameraStyle.DIGITAL_01 -> R.id.style_digital
                    CameraStyle.PLASTIC_82 -> R.id.style_plastic
                    CameraStyle.STREET_84 -> R.id.style_street
                    CameraStyle.FISHEYE_05 -> R.id.style_fisheye
                }
                setTextColor(ToviTheme.TEXT)
                background = touchBackground(Color.TRANSPARENT, dp(16).toFloat())
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
                LinearLayout.LayoutParams(dp(maxOf(52, (52 * resources.configuration.fontScale).toInt())), dp(styleRailDp)).apply { marginEnd = dp(4) },
            )
        }
        styleScroller.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isHorizontalFadingEdgeEnabled = false
            addView(styleRow)
        }
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
            LayoutParams(LayoutParams.MATCH_PARENT, dp(styleRailDp), Gravity.BOTTOM).apply {
                bottomMargin = dp(94)
            },
        )
        zoomPresets.orientation = LinearLayout.HORIZONTAL
        zoomPresets.gravity = Gravity.CENTER
        zoomPresets.background = InsetDrawable(rounded(0xE60B0B0C.toInt(), dp(28).toFloat()), 0, dp(5), 0, dp(5))
        zoomPresets.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(zoomPresets, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(bottomChromeDp + 8)
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
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (cameraReady && !capturing && exposureSupported) {
                    completeFirstUseHint()
                    adjustmentPanel.showExposure(exposureIndex, exposureMinimum, exposureMaximum, exposureStep) {
                        exposureValueListener?.invoke(it)
                    }
                    updateAdjustmentPanelBounds()
                }
            }
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
        unlockControl.apply {
            id = R.id.camera_unlock_style
            text = context.getString(R.string.camera_unlock_short)
            contentDescription = context.getString(R.string.camera_unlock_style)
            setTextColor(ACCENT)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            background = compactControlBackground()
            isClickable = true
            isFocusable = true
            visibility = GONE
            accessibilityDelegate = buttonAccessibilityDelegate()
            setOnClickListener { if (!capturing) unlockListener?.invoke() }
        }
        addView(unlockControl, LayoutParams(dp(90), dp(48), Gravity.BOTTOM or Gravity.END).apply {
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
        val nextCompact = width > height
        val nextShortPortrait = !nextCompact && height - paddingTop - paddingBottom < dp(600)
        if (compactLayout != nextCompact || shortPortraitLayout != nextShortPortrait) {
            compactLayout = nextCompact
            shortPortraitLayout = nextShortPortrait
            updateResponsiveLayout()
            chromeSizeListener?.invoke()
        } else updateResponsiveLayout()
        updateAdjustmentPanelBounds(width, height)
        val available = height - paddingTop - paddingBottom - dp(topChromeDp + bottomChromeDp + 24)
        val panelHeight = minOf(dp(270), available.coerceAtLeast(dp(48)))
        if (metrics.layoutParams.height != panelHeight) {
            metrics.layoutParams = (metrics.layoutParams as LayoutParams).apply { this.height = panelHeight }
        }
    }

    private fun updateAdjustmentPanelBounds(width: Int = this.width, height: Int = this.height) {
        val usableWidth = width - paddingLeft - paddingRight - dp(sideChromeDp)
        val vertical = adjustmentPanel.isVerticalExposure && !compactLayout
        val panelWidth = minOf(usableWidth - dp(32), dp(if (vertical) 112 else if (compactLayout || resources.configuration.fontScale >= 1.5f) 320 else 200)).coerceAtLeast(dp(1))
        adjustmentPanel.layoutParams = (adjustmentPanel.layoutParams as LayoutParams).apply {
            this.width = panelWidth
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = if (vertical) (usableWidth - panelWidth - dp(16)).coerceAtLeast(0) else ((usableWidth - panelWidth) / 2).coerceAtLeast(0)
            marginEnd = dp(sideChromeDp + 16)
            bottomMargin = dp(bottomChromeDp + 8)
            topMargin = 0
            if (vertical) {
                gravity = Gravity.TOP or Gravity.START
                val focusX = if (focusIndicator.visibility == VISIBLE) focusIndicator.x + focusIndicator.width else usableWidth * 0.68f
                marginStart = minOf(focusX.toInt(), usableWidth - panelWidth - dp(8)).coerceAtLeast(dp(8))
                val focusY = if (focusIndicator.visibility == VISIBLE) focusIndicator.y - paddingTop else (height - paddingTop - paddingBottom) * 0.4f
                val latestTop = (height - paddingTop - paddingBottom - dp(bottomChromeDp + 248)).coerceAtLeast(dp(topChromeDp))
                topMargin = (focusY.toInt() - dp(64)).coerceIn(dp(topChromeDp), latestTop)
                bottomMargin = 0
            }
        }
        adjustmentPanel.setMaximumPanelHeight(
            (height - paddingTop - paddingBottom - dp(topChromeDp + bottomChromeDp + 16)).coerceAtLeast(0),
        )
    }

    private fun updateResponsiveLayout() {
        fun place(view: View, width: Int, height: Int, gravity: Int, bottom: Int, start: Int = 0, end: Int = 0) {
            view.layoutParams = LayoutParams(dp(width), dp(height), gravity).apply {
                bottomMargin = dp(bottom)
                marginStart = dp(start)
                marginEnd = dp(end)
            }
        }
        val sideControls = listOf(shutterControl, thumbnail, lensSwitchControl, exposureControl, zoomControl)
        sideControls.filter { it.parent === compactRailContent }.forEach { view ->
            compactRailContent.removeView(view)
            addView(view)
        }
        compactRailScroll.visibility = GONE
        styleTitle.maxLines = if (compactLayout) 1 else 2
        topScrim.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(topChromeDp), Gravity.TOP)
        header.layoutParams = (header.layoutParams as LayoutParams).apply {
            this.width = minOf((this@CameraChrome.width - paddingLeft - paddingRight - dp(112)).coerceAtLeast(dp(100)), dp((166 * resources.configuration.fontScale).toInt()))
            height = maxOf(dp(48), header.minimumHeight)
            topMargin = dp(4)
        }
        listOf(settingsControl, flashModeControl).forEach { control ->
            control.layoutParams = (control.layoutParams as LayoutParams).apply { topMargin = dp((topChromeDp - 48) / 2) }
        }
        bottomScrim.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(bottomChromeDp), Gravity.BOTTOM)
        sideScrim.visibility = if (compactLayout) VISIBLE else GONE
        sideScrim.layoutParams = LayoutParams(dp(sideChromeDp), LayoutParams.MATCH_PARENT, Gravity.END).apply { topMargin = dp(topChromeDp) }
        styleStrip.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(if (compactLayout) 56 else styleRailDp), Gravity.BOTTOM).apply {
            bottomMargin = dp(if (compactLayout) 0 else bottomChromeDp - styleRailDp)
        }
        styleControls.values.forEach { it.setCompact(compactLayout) }
        styleDescription.visibility = if (compactLayout || resources.configuration.fontScale >= 1.5f) GONE else VISIBLE
        zoomPresets.visibility = if (!compactLayout && zoomPresetControls.isNotEmpty()) VISIBLE else GONE
        if (compactLayout) {
            val availableHeight = ((height - paddingTop - paddingBottom) / resources.displayMetrics.density).toInt()
            val railHeight = (availableHeight - topChromeDp).coerceAtLeast(0)
            val gridControlHeight = maxOf(48, kotlin.math.ceil(exposureControl.paint.fontSpacing * 2 / resources.displayMetrics.density).toInt() + 18)
            val gridMinimumHeight = 48 + 48 + gridControlHeight
            val gridLayout = railHeight < 192
            val scrolling = gridLayout && railHeight < gridMinimumHeight
            val layoutHeight = if (scrolling) topChromeDp + gridMinimumHeight else availableHeight
            val shutterSize = if (!gridLayout && railHeight >= 224) 64 else 48
            val rowsHeight = 48 + shutterSize + if (gridLayout) gridControlHeight else 96
            val edge = minOf(8, ((layoutHeight - topChromeDp - rowsHeight) / 2).coerceAtLeast(0))
            val gap = minOf(8, ((layoutHeight - topChromeDp - rowsHeight - edge * 2) / if (gridLayout) 2 else 3).coerceAtLeast(0))
            val thumbnailTop = topChromeDp + edge
            val exposureTop = layoutHeight - edge - if (gridLayout) gridControlHeight else 96 + gap
            val minimumShutterTop = thumbnailTop + 48 + gap
            val maximumShutterTop = exposureTop - gap - shutterSize
            val shutterTop = ((minimumShutterTop + maximumShutterTop) / 2).coerceAtLeast(minimumShutterTop)
            place(shutterControl, shutterSize, shutterSize, Gravity.TOP or Gravity.END, 0, end = (128 - shutterSize) / 2)
            (shutterControl.layoutParams as LayoutParams).topMargin = dp(shutterTop)
            place(exposureControl, if (gridLayout) 60 else 100, if (gridLayout) gridControlHeight else 48,
                Gravity.TOP or Gravity.END, 0, end = if (gridLayout) 64 else 14)
            (exposureControl.layoutParams as LayoutParams).topMargin = dp(exposureTop)
            place(zoomControl, if (gridLayout) 60 else 100, if (gridLayout) gridControlHeight else 48,
                Gravity.TOP or Gravity.END, 0, end = if (gridLayout) 4 else 14)
            (zoomControl.layoutParams as LayoutParams).topMargin = dp(if (gridLayout) exposureTop else layoutHeight - edge - 48)
            place(unlockControl, 120, 48, Gravity.BOTTOM or Gravity.END, 4, end = sideChromeDp + 4)
            place(thumbnail, 48, 48, Gravity.TOP or Gravity.END, 0, end = 72)
            (thumbnail.layoutParams as LayoutParams).topMargin = dp(thumbnailTop)
            place(lensSwitchControl, 48, 48, Gravity.TOP or Gravity.END, 0, end = 12)
            (lensSwitchControl.layoutParams as LayoutParams).topMargin = dp(thumbnailTop)
            if (scrolling) {
                compactRailScroll.layoutParams = LayoutParams(dp(128), LayoutParams.MATCH_PARENT, Gravity.END).apply { topMargin = dp(topChromeDp) }
                compactRailContent.layoutParams = (compactRailContent.layoutParams as LayoutParams).apply { height = dp(gridMinimumHeight) }
                sideControls.forEach { view ->
                    removeView(view)
                    val params = (view.layoutParams as LayoutParams).apply { topMargin -= dp(topChromeDp) }
                    compactRailContent.addView(view, params)
                }
                compactRailScroll.visibility = VISIBLE
            }
        } else {
            val shutterSize = if (shortPortraitLayout) 68 else 78
            val thumbnailBottom = if (shortPortraitLayout) 12 else 18
            place(shutterControl, shutterSize + 8, shutterSize + 8, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 4)
            place(exposureControl, 100, 48, Gravity.TOP or Gravity.END, 0, end = 12)
            (exposureControl.layoutParams as LayoutParams).topMargin = dp(topChromeDp + 8)
            place(zoomControl, 90, 48, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottomChromeDp + if (zoomPresetControls.isEmpty()) 8 else 62)
            zoomPresets.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(bottomChromeDp + 8) }
            place(unlockControl, 110, 48, Gravity.TOP or Gravity.START, 0, start = 12)
            (unlockControl.layoutParams as LayoutParams).topMargin = dp(topChromeDp + 8)
            place(thumbnail, 56, 56, Gravity.BOTTOM or Gravity.START, thumbnailBottom, start = 24)
            place(lensSwitchControl, 56, 56, Gravity.BOTTOM or Gravity.END, thumbnailBottom, end = 24)
        }
        listOf(messagePanel, metrics).forEach { view ->
            view.layoutParams = (view.layoutParams as LayoutParams).apply { bottomMargin = dp(bottomChromeDp + 12) }
        }
        updateAdjustmentPanelBounds()
        updateStyleCaption()
        updateZoomReadoutVisibility()
        flash.bringToFront()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rebuildZoomPresets() {
        zoomPresets.removeAllViews()
        zoomPresetControls.clear()
        if (minimumZoom > 0f && maximumZoom > minimumZoom) {
            listOf(0.5f, 1f, 2f).filter { it >= minimumZoom && it <= maximumZoom }.forEach { value ->
                val button = TextView(context).apply {
                    text = if (value == 0.5f) "0.5×" else "${value.toInt()}×"
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    minimumWidth = dp(52)
                    minimumHeight = dp(48)
                    setPadding(dp(12), 0, dp(12), 0)
                    isClickable = true
                    isFocusable = true
                    accessibilityDelegate = buttonAccessibilityDelegate()
                    contentDescription = context.getString(R.string.camera_adjust_zoom_value, value)
                    setOnClickListener {
                        if (cameraReady && !capturing) {
                            if (kotlin.math.abs(value - zoomRatio) < 0.015f) {
                                adjustmentPanel.showZoom(zoomRatio, minimumZoom, maximumZoom) { zoomValueListener?.invoke(it) }
                                updateAdjustmentPanelBounds()
                            } else zoomValueListener?.invoke(value)
                        }
                    }
                }
                zoomPresetControls[value] = button
                zoomPresets.addView(button, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            }
        }
        updateZoomPresetState()
        if (width > 0) updateResponsiveLayout()
    }

    private fun updateZoomReadoutVisibility() {
        val presetMatch = zoomPresetControls.keys.any { kotlin.math.abs(it - zoomRatio) < 0.015f }
        zoomControl.visibility = if (compactLayout || !presetMatch || zoomPresetControls.isEmpty()) VISIBLE else INVISIBLE
    }

    private fun updateZoomPresetState() {
        zoomPresetControls.forEach { (value, button) ->
            val selected = kotlin.math.abs(value - zoomRatio) < 0.015f
            val selectionChanged = button.isSelected != selected || button.background == null
            button.isSelected = selected
            button.isEnabled = cameraReady && !capturing
            button.alpha = if (button.isEnabled) 1f else 0.35f
            val color = if (selected) Color.BLACK else ToviTheme.TEXT
            if (button.currentTextColor != color) button.setTextColor(color)
            if (selectionChanged) button.background = InsetDrawable(touchBackground(if (selected) ACCENT else Color.TRANSPARENT, dp(24).toFloat()), dp(5), dp(5), dp(5), dp(5))
        }
    }

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
        removeCallbacks(clearFirstHint)
        firstHintDismiss = null
        removeCallbacks(clearMessage)
        messagePanel.animate().cancel()
        flash.animate().cancel()
        super.onDetachedFromWindow()
    }

    // Keep the full 48dp accessibility area while drawing a quieter 32dp pill.
    private fun compactControlBackground() = InsetDrawable(
        rounded(0xE60B0B0C.toInt(), dp(16).toFloat()),
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

    private class StyleChipView(context: Context, style: CameraStyle, private val artworkDp: Int) : FrameLayout(context) {
        private val title = TextView(context).apply {
            text = style.uiName(context).replace(" COLOR", "\nCOLOR").replace(" MONO", "\nMONO").let {
                if (style == CameraStyle.HARINEZUMI_2PP || style == CameraStyle.HARINEZUMI_2PP_MONO) it
                else it.replace(" ", "\n")
            }
            textSize = 11f
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val artwork = CameraArtworkView(context).apply {
            setStyle(style)
            background = ToviTheme.card(context, 8)
            clipToOutline = true
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            strokeCap = Paint.Cap.ROUND
            strokeWidth = resources.displayMetrics.density * 2f
        }
        private var locked = false
        private val lockBody = RectF()
        private val lockShackle = Path()

        init {
            setWillNotDraw(false)
            val unit = resources.displayMetrics.density
            addView(artwork, LayoutParams((artworkDp * unit).toInt(), (artworkDp * unit).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (4 * unit).toInt() })
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = ((artworkDp + 8) * unit).toInt() })
        }

        fun setTextColor(color: Int) { title.setTextColor(color) }

        fun setCompact(compact: Boolean) {
            artwork.visibility = if (compact) GONE else VISIBLE
            title.textSize = if (compact) 10f else 9.5f
            title.gravity = Gravity.CENTER
            title.layoutParams = (title.layoutParams as LayoutParams).apply {
                height = if (compact) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
                topMargin = if (compact) 0 else ((artworkDp + 8) * resources.displayMetrics.density).toInt()
            }
        }

        fun setLocked(value: Boolean) {
            if (locked == value) return
            locked = value
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val unit = resources.displayMetrics.density
            if (artwork.visibility == VISIBLE && isSelected) {
                paint.color = ACCENT
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * unit
                canvas.drawRoundRect(RectF(artwork.left.toFloat(), artwork.top.toFloat(), artwork.right.toFloat(), artwork.bottom.toFloat()), 8f * unit, 8f * unit, paint)
            }
            if (locked) {
                val x = if (artwork.visibility == VISIBLE) artwork.right - 9f * unit else width - 8f * unit
                val y = if (artwork.visibility == VISIBLE) artwork.bottom - 10f * unit else 10f * unit
                paint.color = ToviTheme.SURFACE
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, 10f * unit, paint)
                paint.color = ToviTheme.TEXT
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
            if (isSelected && artwork.visibility != VISIBLE) {
                paint.color = ACCENT
                paint.strokeWidth = resources.displayMetrics.density * 2f
                val halfWidth = minOf(width * 0.20f, resources.displayMetrics.density * 18f)
                val baseline = height - resources.displayMetrics.density * 2f
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
            paint.color = 0xFF0B0B0C.toInt()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(centerX, centerY, minOf(width, height) * 0.48f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = 0xFF303034.toInt()
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
            paint.color = if (mode != CameraFlashMode.OFF) ACCENT else Color.WHITE
            paint.strokeWidth = unit * 1.8f
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = if (mode != CameraFlashMode.OFF) Paint.Style.FILL else Paint.Style.STROKE
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
        private val icon = context.getDrawable(R.drawable.ic_gallery_settings)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = (22 * resources.displayMetrics.density).toInt()
            icon?.setBounds((width - size) / 2, (height - size) / 2, (width + size) / 2, (height + size) / 2)
            icon?.draw(canvas)
        }
    }

    private class ShutterView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var ready = false
        private var capturing = false
        private val animatedProgress = android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f

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
            paint.color = if (isPressed) 0xFFFFE08A.toInt() else ACCENT
            canvas.drawCircle(centerX, centerY, radius * 0.91f, paint)

            paint.style = Paint.Style.FILL
            paint.color = if (isPressed) 0xFFDDE0E5.toInt() else 0xFFF5F6FA.toInt()
            canvas.drawCircle(centerX, centerY, radius * if (capturing) 0.62f else 0.78f, paint)

            if (capturing) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.075f
                paint.color = ACCENT
                val inset = radius * 0.08f
                val angle = if (animatedProgress) (android.os.SystemClock.uptimeMillis() % 1200L) * 360f / 1200f else -90f
                canvas.drawArc(RectF(inset, inset, width - inset, height - inset), angle, 110f, false, paint)
                if (animatedProgress) postInvalidateDelayed(32L)
            }


        }
    }

    companion object {
        private const val ACCENT = ToviTheme.PRIMARY
        private const val MESSAGE_DURATION_MILLIS = 3_500L
    }
}
