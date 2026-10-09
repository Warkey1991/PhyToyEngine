package com.phytoy.sample

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local, virtualized gallery. Only visible rows request bounded background thumbnails. */
internal class PhotoGalleryOverlay(
    context: Context,
    private val library: PhotoLibrary,
    private val photoStore: PhotoStore,
) : FrameLayout(context) {
    // Virtualize complete photo rows so a date heading spans all columns.
    private val grid = ListView(context)
    private val galleryHeader = LinearLayout(context)
    private val heading = TextView(context)
    private val select = TextView(context)
    private val selectionCount = TextView(context)
    private val selectionShare = TextView(context)
    private val selectionBar = LinearLayout(context)
    private lateinit var navigation: View
    private var selecting = false
    private val selectedUris = linkedSetOf<Uri>()
    private val content = FrameLayout(context)
    private val stateContainer = ScrollView(context)
    private val statePanel = LinearLayout(context)
    private val stateText = TextView(context)
    private val spinner = ProgressBar(context)
    private val retry = TextView(context)
    private val notice = TextView(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(24)) { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "PhyToyGallery").apply {
            isDaemon = true
        }
    }
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceIn(2L * 1024 * 1024, 8L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        // Never recycle an evicted bitmap: a visible recycled cell may still draw it.
    }
    private data class ThumbnailJob(val key: String, val generation: Int, var future: Future<*>? = null)
    private val pending = linkedMapOf<String, ThumbnailJob>()
    private val failedThumbnails = mutableSetOf<String>()
    private val backgroundAccessibility = linkedMapOf<View, Int>()
    private var entries: List<PhotoLibrary.Entry> = emptyList()
    private sealed class GalleryRow {
        data class Heading(val day: Long) : GalleryRow()
        data class Photos(val entries: List<PhotoLibrary.Entry>) : GalleryRow()
    }
    private var rows: List<GalleryRow> = emptyList()
    private var columns = 3
    private var generation = 0
    private var released = false
    private var listing: Future<*>? = null
    private var previousInputFocus: View? = null
    private var previousAccessibilityFocus: View? = null
    private var photoSelectedListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var backListener: (() -> Unit)? = null
    private var settingsListener: (() -> Unit)? = null
    private var visibilityListener: ((Boolean) -> Unit)? = null
    private val adapter = GalleryAdapter()
    private val hideNotice = Runnable { notice.visibility = GONE }

    init {
        id = R.id.gallery_page
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(ToviTheme.SURFACE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            accessibilityPaneTitle = context.getString(R.string.gallery_title)
        }
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addHeader()
        page.addView(galleryHeader, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        page.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        grid.apply {
            divider = null
            dividerHeight = dp(8)
            setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            itemsCanFocus = true
            setPadding(dp(12), 0, dp(12), dp(20))
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            adapter = this@PhotoGalleryOverlay.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, count: Int, total: Int) {
                    val visibleStart = first.coerceIn(0, rows.size)
                    val visibleEnd = (visibleStart + count + 2).coerceAtMost(rows.size)
                    val visibleEntries = rows.subList(visibleStart, visibleEnd)
                        .filterIsInstance<GalleryRow.Photos>().flatMap { it.entries }
                    val visible = visibleEntries.map { it.photo.uri.toString() }.toSet()
                    val obsolete = pending.values.filter { it.key !in visible }
                    obsolete.forEach { pending.remove(it.key); it.future?.cancel(false) }
                    executor.purge()
                    visibleEntries.forEach { entry ->
                        if (cache.get(entry.photo.uri.toString()) == null) requestThumbnail(entry.photo)
                    }
                }
            })
        }
        content.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addStatePanel()
        navigation = bottomNavigation()
        page.addView(navigation, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buildSelectionBar()
        page.addView(selectionBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        notice.apply {
            textSize = 13f
            setTextColor(PageTopBar.ON_SURFACE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ToviTheme.card(context, 16)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = GONE
        }
        addView(notice, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(96)
        })
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
    }

    fun setOnPhotoSelectedListener(listener: (PhotoStore.SavedPhoto) -> Unit) { photoSelectedListener = listener }
    fun setOnBackListener(listener: () -> Unit) { backListener = listener }
    fun setOnSettingsListener(listener: () -> Unit) { settingsListener = listener }
    fun setOnGalleryVisibilityChangedListener(listener: (Boolean) -> Unit) { visibilityListener = listener }
    fun isShowing(): Boolean = visibility == VISIBLE

    /** Stable navigation snapshot in the same newest-first order as the grid. */
    fun photos(): List<PhotoStore.SavedPhoto> = entries.map { it.photo }

    fun removePhoto(uri: Uri) {
        // A scan started before the delete must not publish a stale entry afterward.
        cancelPending()
        val key = uri.toString()
        cache.remove(key)
        failedThumbnails.remove(key)
        entries = entries.filterNot { it.photo.uri == uri }
        selectedUris.remove(uri)
        rebuildRows()
        updateSelection()
        if (entries.isEmpty()) showState(R.string.gallery_empty, loading = false)
    }

    fun show() {
        if (released) return
        val wasShowing = isShowing()
        if (!wasShowing) {
            previousInputFocus = rootView.findFocus()
            previousAccessibilityFocus = findAccessibilityFocus(rootView)
            isolateAccessibility()
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = VISIBLE
        requestApplyInsets()
        if (!wasShowing) visibilityListener?.invoke(true)
        if (!wasShowing) {
            select.requestFocus()
            // API 28+ announces the accessibility pane when it appears. Older
            // platforms need a window event, not an app-selected TalkBack focus.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                event.text.add(context.getString(R.string.gallery_title))
                sendAccessibilityEventUnchecked(event)
            }
        }
        refresh()
    }

    fun refresh() {
        if (released || !isShowing()) return
        cancelPending()
        val token = generation
        failedThumbnails.clear()
        // Retain bounded thumbnails and the visible grid while rescanning the album.
        // Returning to the list should not flash a blank loading screen every time.
        if (entries.isEmpty()) showState(R.string.gallery_loading, loading = true)
        listing = executor.submit {
            val result = runCatching {
                val found = library.entries()
                if (library.prune() > 0) library.entries() else found
            }
            mainHandler.post {
                if (released || !isShowing() || token != generation) return@post
                listing = null
                result.onSuccess { photos ->
                    entries = photos
                    selectedUris.retainAll(photos.map { it.photo.uri }.toSet())
                    rebuildRows()
                    updateSelection()
                    if (photos.isEmpty()) showState(R.string.gallery_empty, loading = false)
                    else {
                        stateContainer.visibility = GONE
                        grid.visibility = VISIBLE
                    }
                }.onFailure {
                    if (entries.isEmpty()) showState(R.string.gallery_load_failed, loading = false, canRetry = true)
                    else showMessage(context.getString(R.string.gallery_load_failed))
                }
            }
        }
    }

    fun showMessage(text: CharSequence) {
        mainHandler.removeCallbacks(hideNotice)
        notice.text = text
        notice.visibility = VISIBLE
        mainHandler.postDelayed(hideNotice, if (isTouchExplorationEnabled()) 10_000L else 4_000L)
    }

    fun dismiss(): Boolean {
        if (!isShowing()) return false
        visibility = GONE
        cancelPending()
        mainHandler.removeCallbacks(hideNotice)
        notice.visibility = GONE
        selecting = false
        selectedUris.clear()
        updateSelection()
        restoreBackgroundAccessibility()
        // Restore keyboard/input navigation. The pane-disappeared event lets
        // the accessibility service decide where its own focus should return.
        val candidates = if (isTouchExplorationEnabled()) listOf(previousAccessibilityFocus, previousInputFocus)
            else listOf(previousInputFocus)
        val restored = candidates.firstOrNull { it != null && it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }
        restored?.requestFocus()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            restored?.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        }
        previousInputFocus = null
        previousAccessibilityFocus = null
        visibilityListener?.invoke(false)
        return true
    }

    fun release() {
        dismiss()
        released = true
        cancelPending()
        executor.shutdownNow()
        cache.evictAll()
        entries = emptyList()
        rows = emptyList()
        adapter.notifyDataSetChanged()
        mainHandler.removeCallbacks(hideNotice)
    }

    private fun cancelPending() {
        generation++
        listing?.cancel(false)
        listing = null
        pending.values.forEach { it.future?.cancel(false) }
        pending.clear()
        executor.purge()
    }

    private fun requestThumbnail(photo: PhotoStore.SavedPhoto) {
        val key = photo.uri.toString()
        if (released || !isShowing() || key in pending || key in failedThumbnails) return
        val job = ThumbnailJob(key, generation)
        pending[key] = job
        try {
            job.future = executor.submit {
                val bitmap = photoStore.loadThumbnail(photo.uri)
                mainHandler.post {
                    if (released || job.generation != generation || pending[key] !== job) {
                        bitmap?.recycle()
                        return@post
                    }
                    pending.remove(key)
                    if (bitmap != null) cache.put(key, bitmap) else failedThumbnails.add(key)
                    adapter.notifyDataSetChanged()
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
        }
    }

    private inner class GalleryAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): GalleryRow = rows[position]
        override fun getItemId(position: Int): Long = when (val row = rows[position]) {
            is GalleryRow.Heading -> row.day
            is GalleryRow.Photos -> row.entries.first().photo.uri.toString().hashCode().toLong()
        }
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is GalleryRow.Heading) 0 else 1
        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(position: Int): Boolean = rows[position] is GalleryRow.Photos
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)
            if (row is GalleryRow.Heading) {
                return (convertView as? TextView ?: TextView(context).apply {
                    textSize = 12f
                    setTextColor(ToviTheme.MUTED)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    letterSpacing = 0.06f
                    includeFontPadding = false
                    setPadding(dp(6), dp(14), dp(4), dp(4))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
                }).apply { text = if (row.day > 0) DateFormat.getDateInstance(DateFormat.LONG).format(Date(row.day)).uppercase(Locale.getDefault())
                    else context.getString(R.string.gallery_saved_photos) }
            }
            val photoRow = row as GalleryRow.Photos
            val container = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            if (container.childCount != columns) {
                container.removeAllViews()
                repeat(columns) { index ->
                    container.addView(PhotoCell(context), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    })
                }
            }
            repeat(columns) { index ->
                val cell = container.getChildAt(index) as PhotoCell
                val entry = photoRow.entries.getOrNull(index)
                cell.visibility = if (entry == null) INVISIBLE else VISIBLE
                if (entry != null) bindCell(cell, entry)
                else { cell.image.setImageDrawable(null); cell.setOnClickListener(null) }
            }
            return container
        }
        private fun bindCell(cell: PhotoCell, entry: PhotoLibrary.Entry) {
            val photo = entry.photo
            val bitmap = cache.get(photo.uri.toString())
            cell.image.setImageBitmap(bitmap)
            if (bitmap == null) cell.image.setImageResource(R.drawable.ic_gallery_placeholder)
            cell.label.text = if (photo.uri.toString() in failedThumbnails) context.getString(R.string.gallery_thumbnail_failed)
                else if (photo.styleCode == "S84") "ST84" else photo.styleCode
            cell.label.setTextColor(if (photo.styleCode == "DH2") ToviTheme.PRIMARY else ToviTheme.TEXT)
            cell.label.compoundDrawables[0]?.setTint(if (photo.styleCode == "DH2") ToviTheme.PRIMARY else ToviTheme.TEXT)
            cell.contentDescription = context.getString(R.string.gallery_item_description, photo.styleName,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.capturedAtMillis)))
            val checked = photo.uri in selectedUris
            cell.isSelected = checked
            cell.selectionMark.visibility = if (selecting) VISIBLE else GONE
            cell.selectionMark.text = if (checked) "✓" else ""
            cell.selectionMark.background = rounded(if (checked) ToviTheme.PRIMARY else 0xA60B0B0C.toInt(), dp(14).toFloat()).apply {
                setStroke(dp(1), if (checked) ToviTheme.PRIMARY else ToviTheme.TEXT)
            }
            val border = if (checked) rounded(Color.TRANSPARENT, dp(14).toFloat()).apply { setStroke(dp(2), ToviTheme.PRIMARY) } else null
            cell.foreground = RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), border, rounded(Color.WHITE, dp(14).toFloat()))
            cell.setOnClickListener { if (selecting) toggleSelection(photo.uri) else photoSelectedListener?.invoke(photo) }
            cell.setOnLongClickListener { if (!selecting) { selecting = true; selectedUris.clear() }; toggleSelection(photo.uri); true }
            if (bitmap == null) requestThumbnail(photo)
        }
    }

    private inner class PhotoCell(context: Context) : FrameLayout(context) {
        val image = ImageView(context)
        val label = TextView(context)
        val selectionMark = TextView(context)
        init {
            id = R.id.gallery_item
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = if (selecting) android.widget.CheckBox::class.java.name else Button::class.java.name
                    info.isCheckable = selecting
                    info.isChecked = isSelected
                }
            }
            background = ToviTheme.card(context, 14)
            clipToOutline = true
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            label.apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                includeFontPadding = false
                minimumHeight = dp(24)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // A compact dark badge stays readable without covering the photo edge to edge.
                background = rounded(0xEB0B0B0C.toInt(), dp(16).toFloat())
                val icon = context.getDrawable(R.drawable.ic_gallery_camera)?.mutate()?.apply {
                    setBounds(0, 0, dp(14), dp(14))
                }
                setCompoundDrawables(icon, null, null, null)
                compoundDrawablePadding = dp(4)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(6)
                bottomMargin = dp(6)
            })
            selectionMark.apply {
                textSize = 16f; gravity = Gravity.CENTER; setTextColor(ToviTheme.SURFACE)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = GONE
            }
            addView(selectionMark, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(6); topMargin = dp(6)
            })
            foreground = RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), null, rounded(Color.WHITE, dp(14).toFloat()))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            label.maxWidth = (size - dp(12)).coerceAtLeast(0)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxOf(dp(48), size), MeasureSpec.EXACTLY))
        }
    }

    private fun addHeader() {
        galleryHeader.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(12), dp(8))
            minimumHeight = dp(64)
        }
        heading.apply {
            id = R.id.design_gallery_heading
            text = context.getString(R.string.gallery_title)
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ToviTheme.TEXT)
            includeFontPadding = false
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        select.apply {
            id = R.id.design_gallery_select
            setText(R.string.design_gallery_select)
            textSize = 15f; setTextColor(ToviTheme.PRIMARY); gravity = Gravity.CENTER
            minimumHeight = dp(48); minimumWidth = dp(64)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(0x24FFC629), null, rounded(Color.WHITE, dp(24).toFloat()))
            isClickable = true; isFocusable = true; accessibilityDelegate = buttonDelegate()
            setOnClickListener { selecting = !selecting; if (!selecting) selectedUris.clear(); updateSelection(); adapter.notifyDataSetChanged() }
        }
        galleryHeader.addView(heading, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        galleryHeader.addView(select, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun addStatePanel() {
        statePanel.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(16), dp(32), dp(16))
        }
        stateText.apply {
            id = R.id.gallery_empty
            textSize = 16f; setTextColor(PageTopBar.ON_SURFACE_VARIANT); gravity = Gravity.CENTER
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        retry.apply {
            id = R.id.gallery_state_action
            text = context.getString(R.string.gallery_retry)
            textSize = 14f; setTextColor(ACCENT); gravity = Gravity.CENTER
            minimumHeight = dp(48)
            includeFontPadding = false
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = ToviTheme.actionBackground(context); isClickable = true; isFocusable = true
            accessibilityDelegate = buttonDelegate()
            setOnClickListener { refresh() }
        }
        statePanel.addView(spinner, LinearLayout.LayoutParams(dp(36), dp(36)).apply { bottomMargin = dp(20) })
        statePanel.addView(stateText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        statePanel.addView(retry, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        stateContainer.apply {
            isFillViewport = true
            clipToPadding = false
            addView(statePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        content.addView(stateContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showState(textRes: Int, loading: Boolean, canRetry: Boolean = false) {
        grid.visibility = GONE
        stateContainer.visibility = VISIBLE
        spinner.visibility = if (loading) VISIBLE else GONE
        val empty = textRes == R.string.gallery_empty
        retry.visibility = if (canRetry || empty) VISIBLE else GONE
        retry.setText(if (empty) R.string.gallery_first_photo else R.string.gallery_retry)
        retry.setOnClickListener { if (empty) { dismiss(); backListener?.invoke() } else refresh() }
        stateText.setText(textRes)
        updateSelection()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val available = (w - paddingLeft - paddingRight - dp(24)).coerceAtLeast(0)
        val count = if (w < dp(600)) (if (resources.configuration.fontScale >= 1.5f) 2 else 3)
            else (available / dp(112).coerceAtLeast(1)).coerceIn(3, 6)
        if (columns != count) { columns = count; rebuildRows() }
    }

    private fun rebuildRows() {
        val oldRow = rows.getOrNull(grid.firstVisiblePosition)
        // If deletion removed the first cell, retain another surviving cell
        // from that visible row instead of losing the row's scroll anchor.
        val anchorUri = (oldRow as? GalleryRow.Photos)?.entries?.asSequence()?.map { it.photo.uri }
            ?.firstOrNull { candidate -> entries.any { it.photo.uri == candidate } }
        val anchorDay = (oldRow as? GalleryRow.Heading)?.day
        val anchorOffset = grid.getChildAt(0)?.top ?: 0
        val localDay = Calendar.getInstance()
        val grouped = entries.groupBy { entry ->
            if (entry.capturedAtMillis <= 0) 0L else localDay.apply {
                timeInMillis = entry.capturedAtMillis
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        rows = grouped.flatMap { (day, dayEntries) ->
            listOf<GalleryRow>(GalleryRow.Heading(day)) + dayEntries.chunked(columns).map { GalleryRow.Photos(it) }
        }
        adapter.notifyDataSetChanged()
        val anchor = rows.indexOfFirst { row ->
            when (row) {
                is GalleryRow.Heading -> anchorDay != null && row.day == anchorDay
                is GalleryRow.Photos -> anchorUri != null && row.entries.any { it.photo.uri == anchorUri }
            }
        }
        if (anchor >= 0) grid.setSelectionFromTop(anchor, anchorOffset)
    }

    private fun bottomNavigation(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(6))
        background = rounded(ToviTheme.SURFACE, dp(22).toFloat()).apply { setStroke(dp(1), ToviTheme.BORDER) }
        val tabs = listOf(
            Triple(R.id.gallery_tab_camera, R.string.gallery_camera, R.drawable.ic_gallery_camera),
            Triple(R.id.gallery_tab_gallery, R.string.gallery_title, R.drawable.ic_gallery_photos),
            Triple(R.id.gallery_tab_settings, R.string.gallery_settings, R.drawable.ic_gallery_settings),
        )
        tabs.forEachIndexed { index, (id, label, icon) ->
            addView(TextView(context).apply {
                this.id = id
                setText(label)
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (index == 1) ToviTheme.PRIMARY else ToviTheme.MUTED)
                val drawable = context.getDrawable(icon)?.mutate()?.apply {
                    setTint(if (index == 1) ToviTheme.PRIMARY else ToviTheme.MUTED)
                    setBounds(0, 0, dp(28), dp(28))
                }
                val dot = if (index == 1) GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(ToviTheme.PRIMARY); setBounds(0, 0, dp(5), dp(5))
                } else GradientDrawable().apply { setColor(Color.TRANSPARENT); setBounds(0, 0, dp(5), dp(5)) }
                setCompoundDrawables(null, drawable, null, dot)
                compoundDrawablePadding = dp(6)
                minimumHeight = dp(66)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                maxLines = 2
                background = RippleDrawable(ColorStateList.valueOf(0x24FFC629), null, rounded(Color.WHITE, dp(16).toFloat()))
                isClickable = true; isFocusable = true
                accessibilityDelegate = buttonDelegate()
                if (index == 1) isSelected = true
                setOnClickListener {
                    when (index) {
                        0 -> { dismiss(); backListener?.invoke() }
                        2 -> settingsListener?.invoke()
                    }
                }
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildSelectionBar() {
        selectionBar.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(10))
            background = rounded(ToviTheme.SURFACE, dp(22).toFloat()).apply { setStroke(dp(1), ToviTheme.BORDER) }
            visibility = GONE
        }
        selectionCount.apply {
            textSize = 14f; setTextColor(ToviTheme.TEXT)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        selectionShare.apply {
            id = R.id.design_gallery_share_selected
            setText(R.string.photo_share)
            textSize = 15f; setTextColor(ToviTheme.SURFACE); gravity = Gravity.CENTER
            minimumHeight = dp(48); minimumWidth = dp(100)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = ToviTheme.actionBackground(context, true)
            isClickable = true; isFocusable = true; accessibilityDelegate = buttonDelegate()
            setOnClickListener { shareSelection() }
        }
        selectionBar.addView(selectionCount, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(selectionShare, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        updateSelection()
    }

    private fun toggleSelection(uri: Uri) {
        if (!selectedUris.add(uri)) selectedUris.remove(uri)
        updateSelection()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelection() {
        select.setText(if (selecting) R.string.design_gallery_done else R.string.design_gallery_select)
        select.isEnabled = selecting || entries.isNotEmpty()
        select.alpha = if (select.isEnabled) 1f else 0.4f
        if (::navigation.isInitialized) navigation.visibility = if (selecting) GONE else VISIBLE
        selectionBar.visibility = if (selecting) VISIBLE else GONE
        selectionCount.text = context.getString(R.string.design_gallery_selected_count, selectedUris.size)
        selectionShare.isEnabled = selectedUris.isNotEmpty()
        selectionShare.alpha = if (selectionShare.isEnabled) 1f else 0.4f
    }

    /** Shares only explicitly selected app-photo URIs with the system chooser. */
    private fun shareSelection() {
        val chosen = entries.filter { it.photo.uri in selectedUris }.map { it.photo }
        if (chosen.isEmpty()) return
        try {
            // Reuse the existing saved-MediaStore-URI validation for every item.
            val validated = chosen.map(photoStore::createShareIntent)
            val send = if (chosen.size == 1) validated.first() else {
                val uris = ArrayList(chosen.map { it.uri })
                val clip = ClipData.newUri(context.contentResolver, context.getString(R.string.gallery_title), uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.photo_share_title)).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
            showMessage(context.getString(R.string.design_gallery_share_failed))
        }
    }

    private fun isolateAccessibility() {
        val container = parent as? ViewGroup ?: return
        for (index in 0 until container.childCount) {
            val sibling = container.getChildAt(index)
            if (sibling === this || sibling.visibility != VISIBLE) continue
            backgroundAccessibility.putIfAbsent(sibling, sibling.importantForAccessibility)
            sibling.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
    private fun restoreBackgroundAccessibility() {
        backgroundAccessibility.forEach { (view, previous) -> view.importantForAccessibility = previous }
        backgroundAccessibility.clear()
    }
    private fun findAccessibilityFocus(view: View): View? {
        if (view.isAccessibilityFocused) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findAccessibilityFocus(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private fun isTouchExplorationEnabled(): Boolean =
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    private fun buttonDelegate() = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
        }
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun touchBackground() = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), rounded(0x14FFFFFF, dp(24).toFloat()),
        rounded(Color.WHITE, dp(24).toFloat()))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun onDetachedFromWindow() { release(); restoreBackgroundAccessibility(); super.onDetachedFromWindow() }

    companion object { private const val ACCENT = PageTopBar.PRIMARY }
}
