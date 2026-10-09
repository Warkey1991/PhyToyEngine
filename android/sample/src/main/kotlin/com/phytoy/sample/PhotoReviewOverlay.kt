package com.phytoy.sample

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/** Photo-first review. Presentation metadata and favorites never alter the saved photo. */
internal class PhotoReviewOverlay(context: Context) : FrameLayout(context) {
    private val photo = ZoomablePhotoView(context)
    private val photoBody = LinearLayout(context)
    private val imageArea = FrameLayout(context)
    private val back = ImageView(context)
    private val title = TextView(context)
    private val more = ImageView(context)
    private val zoomHint = TextView(context)
    private val stateViewport = ScrollView(context)
    private val statePanel = LinearLayout(context)
    private val loading = ProgressBar(context)
    private val stateText = TextView(context)
    private val retryButton = Button(context)
    private val actionScroll = ReviewActionScroll(context)
    private val actions = LinearLayout(context)
    private val metadataRow = LinearLayout(context)
    private val styleCard = LinearLayout(context)
    private val cameraArt = CameraArtworkView(context)
    private val metadataTitle = TextView(context)
    private val metadataTime = TextView(context)
    private val stats = LinearLayout(context)
    private val actionGrid = LinearLayout(context)
    private val favoriteButton = PhotoAction(context, R.id.review_favorite, R.string.design_review_favorite, R.drawable.design_photo_heart)
    private val shareButton = PhotoAction(context, R.id.review_share, R.string.photo_share, R.drawable.ic_photo_share)
    private val deleteButton = PhotoAction(context, R.id.review_delete, R.string.photo_delete, R.drawable.ic_photo_delete)
    private val infoButton = PhotoAction(context, R.id.review_info, R.string.photo_info, R.drawable.ic_photo_info)
    private val continueButton = TextView(context)
    private val favorites = context.applicationContext.getSharedPreferences("tovicam_photo_ui", Context.MODE_PRIVATE)
    private val metadataWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tovicam-photo-info").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var metadataWork: Future<*>? = null
    private var metadataGeneration = 0
    private var metadataUri: String? = null
    private var information = PhotoInformation()
    private var deletionDialog: AlertDialog? = null
    private var informationDialog: AlertDialog? = null
    private var moreDialog: AlertDialog? = null
    private var reviewBitmap: Bitmap? = null
    private var canPrevious = false
    private var canNext = false
    private var busy = false
    private var zoomed = false
    private var photoIndex = -1
    private var photoCount = 0
    private var zoomHintShown = false
    private val hideZoomHint = Runnable { zoomHint.visibility = GONE }
    private var actionColumns = 0
    private val backgroundAccessibility = linkedMapOf<View, Int>()
    private var previousInputFocus: View? = null
    private var previousAccessibilityFocus: View? = null

    var currentPhoto: PhotoStore.SavedPhoto? = null
        private set
    private var shareListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var deleteListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var navigateListener: ((Int) -> Unit)? = null
    private var retryListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var visibilityListener: ((Boolean) -> Unit)? = null
    private var continueListener: (() -> Unit)? = null

    fun setOnContinueShootingListener(listener: () -> Unit) { continueListener = listener }
    fun setOnReviewVisibilityChangedListener(listener: (Boolean) -> Unit) { visibilityListener = listener }
    fun setOnShareListener(listener: (PhotoStore.SavedPhoto) -> Unit) { shareListener = listener }
    /** Called only after the user confirms deletion of this exact photo. */
    fun setOnDeleteListener(listener: (PhotoStore.SavedPhoto) -> Unit) { deleteListener = listener }
    fun setOnNavigateListener(listener: (Int) -> Unit) { navigateListener = listener }
    fun setOnRetryListener(listener: (PhotoStore.SavedPhoto) -> Unit) { retryListener = listener }
    fun setNavigation(canPrevious: Boolean, canNext: Boolean) {
        this.canPrevious = canPrevious; this.canNext = canNext
        moreDialog?.dismiss(); moreDialog = null
        updateActions()
    }
    fun setPhotoPosition(index: Int, total: Int) {
        photoIndex = index; photoCount = total
        title.text = if (index in 0 until total) context.getString(R.string.review_position, index + 1, total)
            else context.getString(R.string.review_pane_title)
    }
    fun setBusy(busy: Boolean) {
        this.busy = busy
        if (busy) { moreDialog?.dismiss(); moreDialog = null }
        updateActions()
    }

    init {
        visibility = GONE
        isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(Color.BLACK)
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = context.getString(R.string.review_pane_title)
        photoBody.orientation = LinearLayout.VERTICAL
        addView(photoBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        photoBody.addView(imageArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        imageArea.setBackgroundColor(Color.BLACK)
        photo.id = R.id.review_photo
        photo.setBackgroundColor(Color.BLACK)
        imageArea.addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureState()
        configurePhotoHeader()
        zoomHint.apply {
            setText(R.string.photo_zoom_hint)
            textSize = 11f; setTextColor(ToviTheme.TEXT)
            gravity = Gravity.CENTER; maxLines = 3
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = shape(0xBF0B0B0C.toInt(), 16)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = GONE
        }
        imageArea.addView(zoomHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(8)
        })
        photo.onZoomChanged = {
            zoomed = it
            if (it) zoomHint.visibility = GONE
        }
        photo.onNavigate = { direction ->
            if (!busy && reviewBitmap != null && (if (direction < 0) canPrevious else canNext)) navigateListener?.invoke(direction)
        }
        actions.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0B0B0C.toInt(), ToviTheme.SURFACE)).apply {
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        configureMetadata()
        actions.addView(metadataRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        actionGrid.orientation = LinearLayout.VERTICAL
        actions.addView(actionGrid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        arrangeActionRows(resources.displayMetrics.widthPixels)
        favoriteButton.setOnClickListener { if (!busy) toggleFavorite() }
        shareButton.setOnClickListener { if (!busy && reviewBitmap != null) currentPhoto?.let { shareListener?.invoke(it) } }
        deleteButton.setOnClickListener { if (!busy && reviewBitmap != null) confirmDeletion() }
        infoButton.setOnClickListener { if (!busy) showInformation() }
        continueButton.apply {
            id = R.id.review_continue
            setText(R.string.review_action_continue)
            textSize = 16f; setTextColor(ToviTheme.SURFACE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minimumHeight = dp(48)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 2
            contentDescription = context.getString(R.string.review_continue_description)
            val arrow = context.getDrawable(R.drawable.design_photo_next)?.mutate()?.apply {
                setTint(ToviTheme.SURFACE); setBounds(0, 0, dp(20), dp(20))
            }
            setCompoundDrawablesRelative(null, null, arrow, null)
            compoundDrawablePadding = dp(12)
            background = ToviTheme.actionBackground(context, true)
            isClickable = true; isFocusable = true
            accessibilityDelegate = buttonDelegate()
            setOnClickListener { dismiss(); continueListener?.invoke() }
        }
        actions.addView(continueButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        actionScroll.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        photoBody.addView(actionScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        updateActions()
    }

    private fun configurePhotoHeader() {
        val header = FrameLayout(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        configureIconButton(back, R.id.review_back, R.drawable.design_photo_back, R.string.review_navigate_back)
        back.setOnClickListener { dismiss() }
        header.addView(back, LayoutParams(dp(48), dp(48), Gravity.START or Gravity.TOP).apply { marginStart = dp(12); topMargin = dp(10) })
        title.apply {
            textSize = 16f; setTextColor(ToviTheme.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER; includeFontPadding = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = shape(0xC60B0B0C.toInt(), 24)
            maxLines = 1
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        header.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12); marginStart = dp(72); marginEnd = dp(72)
        })
        configureIconButton(more, R.id.review_more, R.drawable.design_photo_more, R.string.design_review_more)
        more.setOnClickListener { if (!busy) showMore() }
        header.addView(more, LayoutParams(dp(48), dp(48), Gravity.END or Gravity.TOP).apply { marginEnd = dp(12); topMargin = dp(10) })
        imageArea.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(72), Gravity.TOP))
    }

    private fun configureIconButton(view: ImageView, id: Int, drawable: Int, description: Int) {
        view.id = id
        view.setImageResource(drawable)
        view.setPadding(dp(12), dp(12), dp(12), dp(12))
        view.background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x30FFFFFF),
            shape(0xDC0B0B0C.toInt(), 28), null), dp(5))
        view.contentDescription = context.getString(description)
        view.isClickable = true; view.isFocusable = true
        view.accessibilityDelegate = buttonDelegate()
    }

    private fun configureState() {
        statePanel.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        loading.indeterminateTintList = ColorStateList.valueOf(ToviTheme.PRIMARY)
        statePanel.addView(loading, LinearLayout.LayoutParams(dp(36), dp(36)))
        stateText.apply {
            textSize = 15f; setTextColor(ToviTheme.TEXT); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statePanel.addView(stateText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        retryButton.apply {
            id = R.id.review_retry; setText(R.string.photo_retry); isAllCaps = false
            minimumHeight = dp(48); setTextColor(ToviTheme.PRIMARY)
            background = shape(ToviTheme.SURFACE, 24)
            setOnClickListener { if (!busy) currentPhoto?.let { retryListener?.invoke(it) } }
        }
        statePanel.addView(retryButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        stateViewport.apply {
            visibility = GONE; isFillViewport = true
            addView(statePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        imageArea.addView(stateViewport, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun configureMetadata() {
        metadataRow.apply { id = R.id.review_metadata; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        styleCard.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = shape(0x400B0B0C, 12, ToviTheme.BORDER)
        }
        cameraArt.apply {
            background = shape(ToviTheme.CARD, 8)
            clipToOutline = true
        }
        styleCard.addView(cameraArt, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(7) })
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        metadataTitle.apply {
            textSize = 12f; setTextColor(ToviTheme.PRIMARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false; maxLines = 2
        }
        metadataTime.apply { textSize = 9f; setTextColor(ToviTheme.MUTED); includeFontPadding = false; maxLines = 2 }
        copy.addView(metadataTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        copy.addView(metadataTime, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        styleCard.addView(copy, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        stats.orientation = LinearLayout.HORIZONTAL
        stats.gravity = Gravity.CENTER_VERTICAL
        metadataRow.addView(styleCard, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        metadataRow.addView(stats, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
    }

    private fun arrangeActionRows(width: Int) {
        val columns = if (resources.configuration.fontScale >= 1.5f && width < dp(500)) 2 else 4
        if (actionColumns == columns) return
        actionColumns = columns
        val buttons = listOf(favoriteButton, shareButton, deleteButton, infoButton)
        val focus = buttons.firstOrNull { it.hasFocus() }
        buttons.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        actionGrid.removeAllViews()
        buttons.chunked(columns).forEachIndexed { index, group ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                if (columns == 4) {
                    dividerDrawable = GradientDrawable().apply { setColor(ToviTheme.BORDER); setSize(dp(1), dp(1)) }
                    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                    dividerPadding = dp(16)
                }
            }
            group.forEach { button -> row.addView(button, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
            actionGrid.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
        focus?.requestFocus()
    }

    fun show(bitmap: Bitmap, saved: PhotoStore.SavedPhoto) {
        deletionDialog?.dismiss(); deletionDialog = null
        bindPhoto(saved)
        busy = false
        photo.setImageBitmap(bitmap)
        reviewBitmap?.takeIf { it !== bitmap }?.recycle()
        reviewBitmap = bitmap
        photo.visibility = VISIBLE
        stateViewport.visibility = GONE
        photo.contentDescription = context.getString(R.string.review_photo_description, saved.styleName, saved.width, saved.height) +
            ". " + context.getString(R.string.photo_zoom_hint)
        zoomHint.visibility = if (!zoomHintShown) VISIBLE else GONE
        if (!zoomHintShown) {
            zoomHintShown = true
            removeCallbacks(hideZoomHint); postDelayed(hideZoomHint, 3_000L)
        }
        updateActions()
        reveal()
    }

    fun setLoading(saved: PhotoStore.SavedPhoto) {
        deletionDialog?.dismiss(); deletionDialog = null
        bindPhoto(saved)
        busy = false
        photo.setImageBitmap(null)
        reviewBitmap?.recycle(); reviewBitmap = null
        photo.visibility = INVISIBLE
        removeCallbacks(hideZoomHint); zoomHint.visibility = GONE
        loading.visibility = VISIBLE; retryButton.visibility = GONE
        stateText.setText(R.string.photo_loading)
        stateViewport.visibility = VISIBLE
        updateActions()
        reveal()
    }

    fun setLoadFailed(text: CharSequence) {
        loading.visibility = GONE; retryButton.visibility = VISIBLE
        stateText.text = text; stateViewport.visibility = VISIBLE
        updateActions()
    }

    private fun bindPhoto(saved: PhotoStore.SavedPhoto) {
        informationDialog?.dismiss(); informationDialog = null
        moreDialog?.dismiss(); moreDialog = null
        currentPhoto = saved
        val style = CameraStyle.entries.firstOrNull { it.shortCode == saved.styleCode }
        metadataTitle.text = style?.uiName(context) ?: saved.styleName
        cameraArt.visibility = if (style != null) VISIBLE else GONE
        if (style != null) cameraArt.setStyle(style)
        if (metadataUri != saved.uri.toString()) {
            metadataUri = saved.uri.toString()
            information = PhotoInformation(width = saved.width, height = saved.height, capturedAt = capturedTime(saved))
            publishInformation()
            loadInformation(saved)
        } else publishInformation()
        updateFavorite()
    }

    private fun loadInformation(saved: PhotoStore.SavedPhoto) {
        metadataGeneration++
        val token = metadataGeneration
        metadataWork?.cancel(false)
        val resolver = context.applicationContext.contentResolver
        metadataWork = metadataWorker.submit {
            val result = runCatching {
                resolver.openInputStream(saved.uri)?.use { input ->
                    val exif = ExifInterface(input)
                    val width = saved.width.takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
                    val height = saved.height.takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
                    val stamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    val time = stamp?.let { runCatching {
                        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }.parse(it)?.time
                    }.getOrNull() }
                    PhotoInformation(width, height, time ?: capturedTime(saved),
                        exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                        exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0.0 && it.isFinite() },
                        exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0.0 && it.isFinite() },
                        exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0.0 && it.isFinite() },
                        exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let {
                            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0).takeIf { value -> value.isFinite() }
                        })
                }
            }.getOrNull()
            main.post {
                if (token != metadataGeneration || currentPhoto?.uri != saved.uri || !isShowing()) return@post
                metadataWork = null
                if (result != null) { information = result; publishInformation() }
            }
        }
    }

    private fun publishInformation() {
        metadataTime.text = information.capturedAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }.orEmpty()
        metadataTime.visibility = if (metadataTime.text.isEmpty()) GONE else VISIBLE
        stats.removeAllViews()
        if (information.width > 0 && information.height > 0) {
            addStatistic(R.drawable.design_photo_ratio, ratio(information.width, information.height),
                context.getString(R.string.review_metadata_ratio, ratio(information.width, information.height)))
            val pixels = information.width.toLong() * information.height / 1_000_000.0
            addStatistic(R.drawable.design_photo_pixels, String.format(Locale.getDefault(), "%.1fMP", pixels),
                context.getString(R.string.review_megapixels, pixels))
        }
        information.aperture?.let { addStatistic(R.drawable.design_photo_aperture, context.getString(R.string.design_review_aperture, it),
            context.getString(R.string.design_review_aperture, it)) }
        information.iso?.let { addStatistic(0, it.toString(), context.getString(R.string.design_review_iso, it)) }
        informationDialog?.takeIf { it.isShowing }?.setMessage(informationText())
    }

    private fun addStatistic(icon: Int, text: String, description: String) {
        if (stats.childCount > 0) stats.addView(View(context).apply {
            setBackgroundColor(ToviTheme.BORDER); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(1), dp(28)).apply { marginStart = dp(2); marginEnd = dp(2) })
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(3), 0, dp(3), 0)
            contentDescription = description
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        if (icon != 0) column.addView(ImageView(context).apply {
            setImageResource(icon); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { bottomMargin = dp(5) })
        else column.addView(TextView(context).apply {
            this.text = "ISO"; textSize = 8f; setTextColor(ToviTheme.TEXT); gravity = Gravity.CENTER
            background = shape(Color.TRANSPARENT, 3, ToviTheme.TEXT)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(18)).apply { bottomMargin = dp(5) })
        column.addView(TextView(context).apply {
            this.text = text; textSize = 10f; setTextColor(ToviTheme.TEXT); gravity = Gravity.CENTER
            maxLines = 2; includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        stats.addView(column, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun toggleFavorite() {
        val saved = currentPhoto ?: return
        val values = favorites.getStringSet("favorite_uris", emptySet()).orEmpty().toMutableSet()
        val added = if (values.remove(saved.uri.toString())) false else { values.add(saved.uri.toString()); true }
        favorites.edit().putStringSet("favorite_uris", values).apply()
        updateFavorite()
        favoriteButton.announceForAccessibility(context.getString(if (added) R.string.design_review_favorite_added else R.string.design_review_favorite_removed))
    }

    private fun updateFavorite() {
        val checked = currentPhoto?.let { it.uri.toString() in favorites.getStringSet("favorite_uris", emptySet()).orEmpty() } == true
        favoriteButton.isSelected = checked
        favoriteButton.image.setImageResource(if (checked) R.drawable.design_photo_heart_filled else R.drawable.design_photo_heart)
        favoriteButton.contentDescription = context.getString(if (checked) R.string.design_review_favorite_remove else R.string.design_review_favorite_add)
    }

    private fun showMore() {
        if (moreDialog?.isShowing == true || busy) return
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun item(id: Int, label: Int, enabled: Boolean, action: () -> Unit) {
            panel.addView(Button(context).apply {
                this.id = id; setText(label); isAllCaps = false
                minimumHeight = dp(48); isEnabled = enabled
                setOnClickListener { moreDialog?.dismiss(); moreDialog = null; if (!busy) action() }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        item(R.id.review_previous, R.string.photo_previous, canPrevious) { if (canPrevious) navigateListener?.invoke(-1) }
        item(R.id.review_next, R.string.photo_next, canNext) { if (canNext) navigateListener?.invoke(1) }
        item(R.id.review_zoom_reset, R.string.photo_zoom_reset, zoomed) { photo.resetZoom() }
        val viewport = ScrollView(context).apply { addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
        moreDialog = AlertDialog.Builder(context).setTitle(R.string.design_review_more_title).setView(viewport)
            .setNegativeButton(R.string.review_metadata_close, null).setOnDismissListener { moreDialog = null }.show()
    }

    private fun confirmDeletion() {
        val saved = currentPhoto ?: return
        if (deletionDialog?.isShowing == true) return
        deletionDialog = AlertDialog.Builder(context).setTitle(R.string.photo_delete_title).setMessage(R.string.photo_delete_body)
            .setNegativeButton(R.string.photo_delete_cancel, null)
            .setPositiveButton(R.string.photo_delete_confirm) { _, _ ->
                if (isShowing() && currentPhoto?.uri == saved.uri && !busy) deleteListener?.invoke(saved)
            }.setOnDismissListener { deletionDialog = null }.show()
        deletionDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFFABA5.toInt())
    }

    private fun showInformation() {
        if (currentPhoto == null || informationDialog?.isShowing == true) return
        informationDialog = AlertDialog.Builder(context).setTitle(R.string.review_metadata_title).setMessage(informationText())
            .setPositiveButton(R.string.review_metadata_close, null).setOnDismissListener { informationDialog = null }.show()
        informationDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ToviTheme.PRIMARY)
    }

    private fun informationText(): String {
        val saved = currentPhoto ?: return ""
        val lines = mutableListOf(context.getString(R.string.review_metadata_style, metadataTitle.text.toString()))
        information.capturedAt?.let { lines += context.getString(R.string.review_metadata_time,
            DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM).format(Date(it))) }
        if (information.width > 0 && information.height > 0) {
            lines += context.getString(R.string.review_dimensions, information.width, information.height,
                context.getString(R.string.review_megapixels, information.width.toLong() * information.height / 1_000_000.0))
            lines += context.getString(R.string.review_metadata_ratio, ratio(information.width, information.height))
        }
        information.iso?.let { lines += context.getString(R.string.design_review_iso, it) }
        information.aperture?.let { lines += context.getString(R.string.design_review_aperture, it) }
        information.exposure?.let {
            val seconds = if (it < 1.0) "1/" + (1.0 / it).roundToInt() + " s" else String.format(Locale.getDefault(), "%.3f s", it)
            lines += context.getString(R.string.design_review_exposure, seconds)
        }
        information.focal?.let { lines += context.getString(R.string.design_review_focal, it) }
        information.ev?.let { lines += context.getString(R.string.design_review_ev, it) }
        if (saved.displayName.isNotBlank()) lines += context.getString(R.string.review_metadata_name, saved.displayName)
        return lines.joinToString("\n\n")
    }

    private fun updateActions() {
        shareButton.isEnabled = reviewBitmap != null && !busy
        deleteButton.isEnabled = reviewBitmap != null && !busy
        infoButton.isEnabled = currentPhoto != null && !busy
        favoriteButton.isEnabled = currentPhoto != null && !busy
        more.isEnabled = currentPhoto != null && !busy
        retryButton.isEnabled = !busy
        listOf(shareButton, deleteButton, infoButton, favoriteButton, more, retryButton).forEach { it.alpha = if (it.isEnabled) 1f else 0.38f }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = w - paddingLeft - paddingRight
        val height = h - paddingTop - paddingBottom
        val side = width > height && width >= dp(480)
        photoBody.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        imageArea.layoutParams = if (side) LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        actionScroll.side = side
        val actionWidth = if (side) dp(if (resources.configuration.fontScale >= 1.5f) 272 else 248) else width
        actionScroll.layoutParams = if (side) LinearLayout.LayoutParams(actionWidth, LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        val stackMetadata = resources.configuration.fontScale >= 1.5f || actionWidth < dp(280)
        metadataRow.orientation = if (stackMetadata) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        styleCard.layoutParams = if (stackMetadata) LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        stats.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (stackMetadata) { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL } else marginStart = dp(6)
        }
        arrangeActionRows(actionWidth)
    }

    private fun reveal() {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val wasShowing = isShowing()
        if (!wasShowing) {
            previousInputFocus = rootView.findFocus()
            previousAccessibilityFocus = findAccessibilityFocus(rootView)
        }
        isolateAccessibility()
        visibility = VISIBLE
        requestApplyInsets()
        if (!wasShowing) {
            visibilityListener?.invoke(true)
            actionScroll.scrollTo(0, 0)
            animate().cancel()
            if (ValueAnimator.areAnimatorsEnabled()) { alpha = 0f; animate().alpha(1f).setDuration(160).start() } else alpha = 1f
            back.requestFocus()
            if (Build.VERSION.SDK_INT < 28) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                event.text.add(context.getString(R.string.review_pane_title)); sendAccessibilityEventUnchecked(event)
            }
        }
    }

    fun dismiss(): Boolean {
        if (!isShowing()) return false
        animate().cancel(); removeCallbacks(hideZoomHint)
        deletionDialog?.dismiss(); deletionDialog = null
        informationDialog?.dismiss(); informationDialog = null
        moreDialog?.dismiss(); moreDialog = null
        metadataGeneration++; metadataWork?.cancel(false); metadataWork = null; metadataUri = null
        visibility = GONE
        visibilityListener?.invoke(false)
        photo.setImageBitmap(null)
        reviewBitmap?.recycle(); reviewBitmap = null
        zoomHint.visibility = GONE; currentPhoto = null
        busy = false; canPrevious = false; canNext = false; photoIndex = -1; photoCount = 0
        restoreBackgroundAccessibility()
        val candidates = if (isTouchExplorationEnabled()) listOf(previousAccessibilityFocus, previousInputFocus) else listOf(previousInputFocus)
        val restored = candidates.firstOrNull { it != null && it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }
        restored?.requestFocus()
        if (Build.VERSION.SDK_INT < 28) restored?.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        previousInputFocus = null; previousAccessibilityFocus = null
        return true
    }
    fun isShowing(): Boolean = visibility == VISIBLE

    private fun capturedTime(saved: PhotoStore.SavedPhoto): Long? = runCatching {
        if (!saved.displayName.startsWith("PT_")) return@runCatching null
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply { isLenient = false }
            .parse(saved.displayName.removePrefix("PT_").substringBeforeLast('.'))?.time
    }.getOrNull()
    private fun ratio(width: Int, height: Int): String {
        var a = width; var b = height
        while (b != 0) { val r = a % b; a = b; b = r }
        val divisor = a.coerceAtLeast(1)
        return (width / divisor).toString() + ":" + (height / divisor)
    }
    private fun isolateAccessibility() {
        val group = parent as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            val sibling = group.getChildAt(i)
            if (sibling === this) continue
            backgroundAccessibility.putIfAbsent(sibling, sibling.importantForAccessibility)
            sibling.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
    private fun restoreBackgroundAccessibility() {
        backgroundAccessibility.forEach { (view, value) -> view.importantForAccessibility = value }
        backgroundAccessibility.clear()
    }
    private fun findAccessibilityFocus(view: View): View? {
        if (view.isAccessibilityFocused) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findAccessibilityFocus(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun isTouchExplorationEnabled() = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    private fun buttonDelegate() = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
        }
    }
    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    override fun onDetachedFromWindow() {
        dismiss(); restoreBackgroundAccessibility(); metadataWorker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private inner class PhotoAction(context: Context, id: Int, label: Int, icon: Int) : LinearLayout(context) {
        val image = ImageView(context)
        init {
            this.id = id
            orientation = VERTICAL; gravity = Gravity.CENTER
            minimumWidth = dp(48); minimumHeight = dp(66)
            setPadding(dp(1), 0, dp(1), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, shape(Color.WHITE, 12))
            isClickable = true; isFocusable = true
            contentDescription = context.getString(label)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = if (id == R.id.review_favorite) android.widget.CheckBox::class.java.name else Button::class.java.name
                    info.isCheckable = id == R.id.review_favorite
                    info.isChecked = id == R.id.review_favorite && isSelected
                }
            }
            image.apply {
                setImageResource(icon)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = shape(0x66000000, 26, ToviTheme.BORDER)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(image, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply {
                setText(label); textSize = 11f; setTextColor(ToviTheme.MUTED)
                gravity = Gravity.CENTER; includeFontPadding = false
                maxLines = 2; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }
    }

    private data class PhotoInformation(
        val width: Int = 0, val height: Int = 0, val capturedAt: Long? = null,
        val iso: Int? = null, val aperture: Double? = null, val exposure: Double? = null,
        val focal: Double? = null, val ev: Double? = null,
    )
    /** Normal typography wraps its natural ~210dp height; larger content remains scrollable. */
    private class ReviewActionScroll(context: Context) : ScrollView(context) {
        var side = false
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val available = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (!side && mode != MeasureSpec.UNSPECIFIED)
                MeasureSpec.makeMeasureSpec((available * 0.44f).roundToInt(), MeasureSpec.AT_MOST) else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, limit)
        }
    }
}
