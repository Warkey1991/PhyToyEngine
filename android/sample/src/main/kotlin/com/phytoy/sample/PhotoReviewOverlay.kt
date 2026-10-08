package com.phytoy.sample

import android.app.AlertDialog
import android.animation.ValueAnimator
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
import android.widget.ImageView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val infoButton = action(R.id.review_info, R.string.photo_info)
    private val metadataCard = LinearLayout(context)
    private val metadataTitle = TextView(context)
    private val metadataTime = TextView(context)
    private val metadataDimensions = TextView(context)
    private val resetButton = action(R.id.review_zoom_reset, R.string.photo_zoom_reset)
    private val statePanel = LinearLayout(context)
    private val stateViewport = ScrollView(context)
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
    private var informationDialog: AlertDialog? = null
    private var photoIndex = -1
    private var photoCount = 0
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

    /** Position comes from the same activity snapshot used by previous/next navigation. */
    fun setPhotoPosition(index: Int, total: Int) {
        photoIndex = index
        photoCount = total
        updateTitle()
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
        setBackgroundColor(ToviTheme.SURFACE)

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
        details.visibility = GONE
        title.apply {
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 1
        }
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
            maxLines = if (resources.configuration.fontScale >= 1.5f) 3 else 2
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
        photo.onNavigate = { direction ->
            if (!busy && reviewBitmap != null && (if (direction < 0) canPrevious else canNext)) {
                navigateListener?.invoke(direction)
            }
        }
        statePanel.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
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
        stateViewport.apply {
            isFillViewport = true
            visibility = GONE
            addView(statePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        imageArea.addView(stateViewport, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        photoBody.addView(imageArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        actions.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = ToviTheme.card(context, 24)
            minimumHeight = dp(88)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        tools.orientation = LinearLayout.VERTICAL
        buildMetadataCard()
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
        infoButton.setOnClickListener { if (!busy) showInformation() }
        listOf(shareButton to R.drawable.ic_photo_share, deleteButton to R.drawable.ic_photo_delete,
            infoButton to R.drawable.ic_photo_info).forEach { (button, icon) ->
            val drawable = context.getDrawable(icon)?.mutate()?.apply { setBounds(0, 0, dp(26), dp(26)) }
            button.setCompoundDrawables(null, drawable, null, null)
            button.compoundDrawablePadding = dp(6)
            button.minimumHeight = dp(68)
        }
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
            background = ToviTheme.actionBackground(context, primary = true)
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
        stateViewport.visibility = GONE
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
        stateViewport.visibility = VISIBLE
        updateActions()
        reveal()
    }

    fun setLoadFailed(text: CharSequence) {
        loading.visibility = GONE
        stateText.text = text
        retryButton.visibility = VISIBLE
        stateViewport.visibility = VISIBLE
        updateActions()
    }

    private fun bindPhoto(saved: PhotoStore.SavedPhoto) {
        informationDialog?.dismiss()
        informationDialog = null
        currentPhoto = saved
        updateTitle()
        metadataTitle.text = saved.styleName
        metadataTime.text = capturedTime(saved)?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }.orEmpty()
        metadataTime.visibility = if (metadataTime.text.isEmpty()) GONE else VISIBLE
        metadataDimensions.text = if (saved.width > 0 && saved.height > 0) {
            context.getString(R.string.review_dimensions, saved.width, saved.height, aspectRatio(saved))
        } else saved.styleCode
    }

    private fun updateTitle() {
        title.text = if (photoIndex in 0 until photoCount) context.getString(R.string.review_position,
            photoIndex + 1, photoCount) else context.getString(R.string.review_pane_title)
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
            if (ValueAnimator.areAnimatorsEnabled()) {
                alpha = 0f
                animate().alpha(1f).setDuration(160L).start()
            } else alpha = 1f
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
        informationDialog?.dismiss()
        informationDialog = null
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
        photoIndex = -1
        photoCount = 0
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
        val sideTools = w > h && w >= dp(480)
        val largeFont = resources.configuration.fontScale >= 1.5f
        val bodyHeight = (h - paddingTop - paddingBottom - topBar.measuredHeight).coerceAtLeast(dp(120))
        // Every landscape action stays reachable in one scrolling side column.
        // Portrait keeps Continue fixed and scrolls the information and tools.
        val scrollAllActions = sideTools || bodyHeight < dp(if (largeFont) 400 else 320)
        val railWidth = dp(if (largeFont) 272 else 248)
        val focusedAction = listOf(previousButton, nextButton, shareButton, deleteButton, infoButton, continueButton)
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
                photoBody.addView(sidebarScroll)
            }
            sidebarScroll.layoutParams = if (sideTools) LinearLayout.LayoutParams(railWidth, LayoutParams.MATCH_PARENT)
                else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (bodyHeight * 0.55f).toInt())
        } else {
            if (sidebarScroll.parent === photoBody) photoBody.removeView(sidebarScroll)
            if (actions.parent !== photoBody) {
                (actions.parent as? ViewGroup)?.removeView(actions)
                photoBody.addView(actions)
            }
            val actionFraction = if (!largeFont && bodyHeight >= dp(480)) 0.60f else 0.50f
            val actionHeight = minOf(dp(if (largeFont) 360 else 304), (bodyHeight * actionFraction).toInt())
            actions.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, actionHeight)
        }
        val toolWidth = (if (sideTools) railWidth else w - paddingLeft - paddingRight) -
            actions.paddingLeft - actions.paddingRight
        // A side rail is narrow even on a wide display. At large fonts, give
        // Share/Delete the full row so that their words stay together.
        rebuildTools(toolWidth < dp(300) || (toolWidth < dp(600) && largeFont),
            toolWidth < dp(300) && largeFont)
        toolsScroll.layoutParams = if (!scrollAllActions) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        focusedAction?.requestFocus()
    }

    private fun rebuildTools(split: Boolean, stackActions: Boolean) {
        if (splitToolRows == split && stackToolActions == stackActions) return
        splitToolRows = split
        stackToolActions = stackActions
        val buttons = listOf(previousButton, shareButton, deleteButton, infoButton, nextButton)
        val focused = buttons.firstOrNull { it.hasFocus() }
        buttons.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        tools.removeAllViews()
        (metadataCard.parent as? ViewGroup)?.removeView(metadataCard)
        tools.addView(metadataCard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        previousButton.textSize = if (split) 18f else 24f
        nextButton.textSize = if (split) 18f else 24f
        val rows = when {
            stackActions -> listOf(listOf(previousButton, nextButton), listOf(shareButton), listOf(deleteButton), listOf(infoButton))
            else -> listOf(listOf(previousButton, nextButton), listOf(shareButton, deleteButton, infoButton))
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

    private fun buildMetadataCard() {
        metadataCard.apply {
            id = R.id.review_metadata
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ToviTheme.card(context, 16, ToviTheme.SURFACE).apply {
                setStroke(dp(1), ToviTheme.BORDER)
            }
        }
        metadataCard.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_gallery_camera)
            imageTintList = ColorStateList.valueOf(ToviTheme.PRIMARY)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        metadataTitle.apply {
            textSize = 15f
            setTextColor(ToviTheme.PRIMARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            includeFontPadding = false
        }
        metadataTime.apply {
            textSize = 11f
            setTextColor(ToviTheme.MUTED)
            includeFontPadding = false
        }
        metadataDimensions.apply {
            textSize = 11f
            setTextColor(ToviTheme.TEXT)
            includeFontPadding = false
        }
        text.addView(metadataTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        text.addView(metadataTime, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        text.addView(metadataDimensions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        metadataCard.addView(text, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun showInformation() {
        val saved = currentPhoto ?: return
        if (informationDialog?.isShowing == true) return
        val lines = mutableListOf(context.getString(R.string.review_metadata_style, saved.styleName))
        capturedTime(saved)?.let {
            lines += context.getString(R.string.review_metadata_time,
                DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM).format(Date(it)))
        }
        if (saved.width > 0 && saved.height > 0) {
            lines += context.getString(R.string.review_dimensions, saved.width, saved.height,
                context.getString(R.string.review_megapixels, saved.width.toLong() * saved.height / 1_000_000.0))
            lines += context.getString(R.string.review_metadata_ratio, aspectRatio(saved))
        }
        if (saved.displayName.isNotBlank()) lines += context.getString(R.string.review_metadata_name, saved.displayName)
        informationDialog = AlertDialog.Builder(context)
            .setTitle(R.string.review_metadata_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.review_metadata_close, null)
            .setOnDismissListener { informationDialog = null }
            .show()
        informationDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ToviTheme.PRIMARY)
    }

    /** Uses the existing capture filename; never invents unavailable EXIF fields. */
    private fun capturedTime(saved: PhotoStore.SavedPhoto): Long? = runCatching {
        if (!saved.displayName.startsWith("PT_")) return@runCatching null
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply { isLenient = false }
            .parse(saved.displayName.removePrefix("PT_").substringBeforeLast('.'))?.time
    }.getOrNull()

    private fun aspectRatio(saved: PhotoStore.SavedPhoto): String {
        var a = saved.width
        var b = saved.height
        while (b != 0) { val remainder = a % b; a = b; b = remainder }
        val divisor = a.coerceAtLeast(1)
        return "${saved.width / divisor}:${saved.height / divisor}"
    }

    private fun updateActions() {
        previousButton.isEnabled = canPrevious && !busy
        nextButton.isEnabled = canNext && !busy
        shareButton.isEnabled = reviewBitmap != null && !busy
        deleteButton.isEnabled = reviewBitmap != null && !busy
        infoButton.isEnabled = currentPhoto != null && !busy
        retryButton.isEnabled = !busy
        listOf(previousButton, nextButton, shareButton, deleteButton, infoButton, retryButton).forEach {
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
        background = ToviTheme.actionBackground(context)
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
