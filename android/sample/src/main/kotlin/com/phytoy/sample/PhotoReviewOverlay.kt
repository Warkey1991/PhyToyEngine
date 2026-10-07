package com.phytoy.sample

import android.app.AlertDialog
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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Local review with deliberate sharing/deletion actions and a bounded zoomable image. */
internal class PhotoReviewOverlay(context: Context) : FrameLayout(context) {
    private val photo = ZoomablePhotoView(context)
    private val topBar = PageTopBar(context)
    private val title = topBar.titleView
    private val details = topBar.subtitleView
    private val back = topBar.backButton
    private val continueButton = TextView(context)
    private val previousButton = action(R.id.review_previous, R.string.photo_previous)
    private val nextButton = action(R.id.review_next, R.string.photo_next)
    private val shareButton = action(R.id.review_share, R.string.photo_share)
    private val deleteButton = action(R.id.review_delete, R.string.photo_delete)
    private val resetButton = action(R.id.review_zoom_reset, R.string.photo_zoom_reset)
    private val statePanel = LinearLayout(context)
    private val loading = ProgressBar(context)
    private val stateText = TextView(context)
    private val retryButton = action(R.id.review_retry, R.string.photo_retry)
    private val imageArea = FrameLayout(context)
    private val actions = LinearLayout(context)
    private val tools = LinearLayout(context)
    private val toolsScroll = ScrollView(context)
    private val sidebarScroll = ScrollView(context).apply {
        isFillViewport = false
        isHorizontalScrollBarEnabled = false
    }
    private val photoBody = LinearLayout(context)
    private val zoomHint = TextView(context)
    private var splitToolRows: Boolean? = null
    private var stackToolActions: Boolean? = null
    private var zoomHintShown = false
    private val hideZoomHint = Runnable { zoomHint.visibility = GONE }
    private var deletionDialog: AlertDialog? = null
    private var reviewBitmap: Bitmap? = null
    var currentPhoto: PhotoStore.SavedPhoto? = null
        private set
    private var shareListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var deleteListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var navigateListener: ((Int) -> Unit)? = null
    private var retryListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var canPrevious = false
    private var canNext = false
    private var busy = false
    private var visibilityListener: ((Boolean) -> Unit)? = null
    private var continueListener: (() -> Unit)? = null

    fun setOnContinueShootingListener(listener: () -> Unit) {
        continueListener = listener
    }

    fun setOnReviewVisibilityChangedListener(listener: (Boolean) -> Unit) {
        visibilityListener = listener
    }
    fun setOnShareListener(listener: (PhotoStore.SavedPhoto) -> Unit) { shareListener = listener }
    /** Called only after the user confirms deletion of this exact photo. */
    fun setOnDeleteListener(listener: (PhotoStore.SavedPhoto) -> Unit) { deleteListener = listener }
    fun setOnNavigateListener(listener: (Int) -> Unit) { navigateListener = listener }
    fun setOnRetryListener(listener: (PhotoStore.SavedPhoto) -> Unit) { retryListener = listener }

    fun setNavigation(canPrevious: Boolean, canNext: Boolean) {
        this.canPrevious = canPrevious
        this.canNext = canNext
        updateActions()
    }

    fun setBusy(busy: Boolean) {
        this.busy = busy
        updateActions()
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
        photo.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        photo.setBackgroundColor(Color.BLACK)
        back.apply {
            contentDescription = context.getString(R.string.review_navigate_back)
        }
        topBar.setOnBackClickListener { dismiss() }
        details.visibility = VISIBLE
        page.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        photoBody.orientation = LinearLayout.VERTICAL
        page.addView(photoBody, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        imageArea.setBackgroundColor(Color.BLACK)
        imageArea.addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        resetButton.setOnClickListener { photo.resetZoom() }
        resetButton.visibility = GONE
        imageArea.addView(resetButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { topMargin = dp(12); marginEnd = dp(12) })
        zoomHint.apply {
            setText(R.string.photo_zoom_hint)
            textSize = 12f
            setTextColor(PageTopBar.ON_SURFACE_VARIANT)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0xD9111318.toInt(), dp(12).toFloat())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = GONE
        }
        imageArea.addView(zoomHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
        })
        photo.onZoomChanged = {
            resetButton.visibility = if (it) VISIBLE else GONE
            if (it) zoomHint.visibility = GONE
        }
        statePanel.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = GONE
        }
        stateText.apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(PageTopBar.ON_SURFACE)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(16), 0, dp(16))
        }
        statePanel.addView(loading, LinearLayout.LayoutParams(dp(40), dp(40)))
        statePanel.addView(stateText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        retryButton.setOnClickListener { if (!busy) currentPhoto?.let { retryListener?.invoke(it) } }
        statePanel.addView(retryButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        imageArea.addView(statePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        photoBody.addView(imageArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        actions.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PageTopBar.SURFACE)
            minimumHeight = dp(88)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        tools.orientation = LinearLayout.VERTICAL
        toolsScroll.apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        previousButton.apply {
            setText(R.string.photo_previous_symbol)
            textSize = 24f
            contentDescription = context.getString(R.string.photo_previous)
        }
        nextButton.apply {
            setText(R.string.photo_next_symbol)
            textSize = 24f
            contentDescription = context.getString(R.string.photo_next)
        }
        val initialToolWidth = resources.displayMetrics.widthPixels - actions.paddingLeft - actions.paddingRight
        val initialLargeFont = resources.configuration.fontScale >= 1.5f
        rebuildTools(initialToolWidth < dp(300) || (initialToolWidth < dp(600) && initialLargeFont),
            initialToolWidth < dp(300) && initialLargeFont)
        previousButton.setOnClickListener { if (!busy && canPrevious) navigateListener?.invoke(-1) }
        nextButton.setOnClickListener { if (!busy && canNext) navigateListener?.invoke(1) }
        shareButton.setOnClickListener { if (!busy && reviewBitmap != null) currentPhoto?.let { shareListener?.invoke(it) } }
        deleteButton.setOnClickListener { if (!busy && reviewBitmap != null) confirmDeletion() }
        deleteButton.setTextColor(0xFFFFABA5.toInt())
        actions.addView(toolsScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
        )
        photoBody.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
        updateActions()
    }

    fun show(bitmap: Bitmap, saved: PhotoStore.SavedPhoto) {
        deletionDialog?.dismiss()
        deletionDialog = null
        bindPhoto(saved)
        busy = false
        photo.setImageBitmap(bitmap)
        reviewBitmap?.takeIf { it !== bitmap }?.recycle()
        reviewBitmap = bitmap
        photo.visibility = VISIBLE
        zoomHint.visibility = if (!zoomHintShown) VISIBLE else GONE
        if (!zoomHintShown) {
            zoomHintShown = true
            removeCallbacks(hideZoomHint)
            postDelayed(hideZoomHint, 3_000L)
        }
        statePanel.visibility = GONE
        photo.contentDescription = context.getString(
            R.string.review_photo_description, saved.styleName, saved.width, saved.height,
        ) + ". " + context.getString(R.string.photo_zoom_hint)
        updateActions()
        reveal()
    }

    /** Open immediately; background decoding belongs to the activity and can be canceled. */
    fun setLoading(saved: PhotoStore.SavedPhoto) {
        deletionDialog?.dismiss()
        deletionDialog = null
        bindPhoto(saved)
        busy = false
        photo.setImageBitmap(null)
        reviewBitmap?.recycle()
        reviewBitmap = null
        photo.visibility = INVISIBLE
        removeCallbacks(hideZoomHint)
        zoomHint.visibility = GONE
        loading.visibility = VISIBLE
        retryButton.visibility = GONE
        stateText.setText(R.string.photo_loading)
        statePanel.visibility = VISIBLE
        updateActions()
        reveal()
    }

    fun setLoadFailed(text: CharSequence) {
        loading.visibility = GONE
        stateText.text = text
        retryButton.visibility = VISIBLE
        statePanel.visibility = VISIBLE
        updateActions()
    }

    private fun bindPhoto(saved: PhotoStore.SavedPhoto) {
        currentPhoto = saved
        title.text = saved.styleName
        details.text = context.getString(R.string.review_image_details, saved.width, saved.height)
    }

    private fun reveal() {
        // Another modal may have hidden this sibling while it was inactive.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val wasShowing = isShowing()
        if (!isShowing()) {
            previousInputFocus = rootView.findFocus()
            previousAccessibilityFocus = findAccessibilityFocus(rootView)
        }
        isolateAccessibility()
        visibility = VISIBLE
        requestApplyInsets()
        if (!wasShowing) visibilityListener?.invoke(true)
        if (!wasShowing) {
            animate().cancel()
            alpha = 0f
            animate().alpha(1f).setDuration(160L).start()
            back.requestFocus()
        }
        // The native pane-title event is sufficient on API 28+. Announce the
        // equivalent window change on older platforms without assigning the
        // accessibility service's focus to a particular button.
        if (!wasShowing && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.text.add(context.getString(R.string.review_pane_title))
            sendAccessibilityEventUnchecked(event)
        }
    }

    fun dismiss(): Boolean {
        if (visibility != VISIBLE) return false
        animate().cancel()
        removeCallbacks(hideZoomHint)
        deletionDialog?.dismiss()
        deletionDialog = null
        visibility = GONE
        visibilityListener?.invoke(false)
        photo.setImageBitmap(null)
        reviewBitmap?.recycle()
        reviewBitmap = null
        zoomHint.visibility = GONE
        currentPhoto = null
        busy = false
        canPrevious = false
        canNext = false
        restoreBackgroundAccessibility()
        val candidates = if (isTouchExplorationEnabled()) listOf(previousAccessibilityFocus, previousInputFocus)
            else listOf(previousInputFocus)
        val restored = candidates.firstOrNull { it != null && it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }
        restored?.requestFocus()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            restored?.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        }
        previousInputFocus = null
        previousAccessibilityFocus = null
        return true
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // In a short, wide window keep the photograph tall and move its tools to the side.
        val sideTools = w > h && w >= dp(600)
        val largeFont = resources.configuration.fontScale >= 1.5f
        // In a very short window, a fixed two-line primary action can leave
        // less than one complete touch target for the tools. Scroll the whole
        // column instead, so every action can be brought fully into view.
        val scrollAllActions = sideTools && largeFont && h < dp(420)
        val focusedAction = listOf(previousButton, nextButton, shareButton, deleteButton, continueButton)
            .firstOrNull { it.hasFocus() }
        photoBody.orientation = if (sideTools) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        imageArea.layoutParams = if (sideTools) LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        if (scrollAllActions) {
            if (actions.parent !== sidebarScroll) {
                (actions.parent as? ViewGroup)?.removeView(actions)
                sidebarScroll.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            if (sidebarScroll.parent !== photoBody) {
                photoBody.addView(sidebarScroll, LinearLayout.LayoutParams(dp(240), LayoutParams.MATCH_PARENT))
            }
        } else {
            if (sidebarScroll.parent === photoBody) photoBody.removeView(sidebarScroll)
            if (actions.parent !== photoBody) {
                (actions.parent as? ViewGroup)?.removeView(actions)
                photoBody.addView(actions)
            }
            actions.layoutParams = if (sideTools) LinearLayout.LayoutParams(dp(240), LayoutParams.MATCH_PARENT)
                else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val toolWidth = (if (sideTools) dp(240) else w - paddingLeft - paddingRight) -
            actions.paddingLeft - actions.paddingRight
        // A side rail is narrow even on a wide display. At large fonts, give
        // Share/Delete the full row so that their words stay together.
        rebuildTools(toolWidth < dp(300) || (toolWidth < dp(600) && largeFont),
            toolWidth < dp(300) && largeFont)
        toolsScroll.layoutParams = if (sideTools && !scrollAllActions) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        focusedAction?.requestFocus()
    }

    private fun rebuildTools(split: Boolean, stackActions: Boolean) {
        if (splitToolRows == split && stackToolActions == stackActions) return
        splitToolRows = split
        stackToolActions = stackActions
        val buttons = listOf(previousButton, shareButton, deleteButton, nextButton)
        val focused = buttons.firstOrNull { it.hasFocus() }
        buttons.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        tools.removeAllViews()
        previousButton.textSize = if (split) 18f else 24f
        nextButton.textSize = if (split) 18f else 24f
        val rows = when {
            stackActions -> listOf(listOf(previousButton, nextButton), listOf(shareButton), listOf(deleteButton))
            split -> listOf(listOf(previousButton, nextButton), listOf(shareButton, deleteButton))
            else -> listOf(buttons)
        }
        rows.forEachIndexed { rowIndex, rowButtons ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowButtons.forEachIndexed { index, button ->
                row.addView(button, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                })
            }
            tools.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = dp(8)
            })
        }
        focused?.requestFocus()
    }

    private fun confirmDeletion() {
        val saved = currentPhoto ?: return
        if (deletionDialog?.isShowing == true) return
        deletionDialog = AlertDialog.Builder(context)
            .setTitle(R.string.photo_delete_title)
            .setMessage(R.string.photo_delete_body)
            .setNegativeButton(R.string.photo_delete_cancel, null)
            .setPositiveButton(R.string.photo_delete_confirm) { _, _ ->
                if (isShowing() && currentPhoto?.uri == saved.uri && !busy) deleteListener?.invoke(saved)
            }
            .setOnDismissListener { deletionDialog = null }
            .show()
        deletionDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFFABA5.toInt())
    }

    private fun updateActions() {
        previousButton.isEnabled = canPrevious && !busy
        nextButton.isEnabled = canNext && !busy
        shareButton.isEnabled = reviewBitmap != null && !busy
        deleteButton.isEnabled = reviewBitmap != null && !busy
        retryButton.isEnabled = !busy
        listOf(previousButton, nextButton, shareButton, deleteButton, retryButton).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.38f
        }
    }

    private fun action(id: Int, text: Int) = TextView(context).apply {
        this.id = id
        setText(text)
        textSize = 13f
        setTextColor(PageTopBar.ON_SURFACE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minimumHeight = dp(48)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        maxLines = 2
        background = touchBackground(0xFF24272E.toInt(), dp(16).toFloat())
        isClickable = true
        isFocusable = true
        accessibilityDelegate = buttonAccessibilityDelegate()
    }

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
        removeCallbacks(hideZoomHint)
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
