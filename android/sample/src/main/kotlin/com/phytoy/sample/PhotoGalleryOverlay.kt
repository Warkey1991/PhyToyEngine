package com.phytoy.sample

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
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
    private val grid = GridView(context)
    private val topBar = PageTopBar(context)
    private val subtitle = topBar.subtitleView
    private val back = topBar.backButton
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
    private var generation = 0
    private var released = false
    private var listing: Future<*>? = null
    private var previousInputFocus: View? = null
    private var previousAccessibilityFocus: View? = null
    private var photoSelectedListener: ((PhotoStore.SavedPhoto) -> Unit)? = null
    private var backListener: (() -> Unit)? = null
    private var visibilityListener: ((Boolean) -> Unit)? = null
    private val adapter = GalleryAdapter()
    private val hideNotice = Runnable { notice.visibility = GONE }

    init {
        id = R.id.gallery_page
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(PageTopBar.SURFACE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            accessibilityPaneTitle = context.getString(R.string.gallery_title)
        }
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addHeader()
        page.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        page.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        grid.apply {
            numColumns = 3
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            setPadding(dp(12), dp(4), dp(12), dp(20))
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            adapter = this@PhotoGalleryOverlay.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, count: Int, total: Int) {
                    val visibleEntries = entries.drop(first).take(count + 3)
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
        notice.apply {
            textSize = 13f
            setTextColor(PageTopBar.ON_SURFACE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(0xFF1D2026.toInt(), dp(14).toFloat())
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = GONE
        }
        addView(notice, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(20)
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
    fun setOnGalleryVisibilityChangedListener(listener: (Boolean) -> Unit) { visibilityListener = listener }
    fun isShowing(): Boolean = visibility == VISIBLE

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
        back.requestFocus()
        if (isTouchExplorationEnabled()) back.post {
            if (isShowing()) back.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
        refresh()
    }

    fun refresh() {
        if (released || !isShowing()) return
        cancelPending()
        val token = generation
        cache.evictAll()
        failedThumbnails.clear()
        entries = emptyList()
        adapter.notifyDataSetChanged()
        showState(R.string.gallery_loading, loading = true)
        listing = executor.submit {
            val result = runCatching { library.entries(); library.prune(); library.entries() }
            mainHandler.post {
                if (released || !isShowing() || token != generation) return@post
                listing = null
                result.onSuccess { photos ->
                    entries = photos
                    subtitle.text = resources.getQuantityString(R.plurals.gallery_photo_count, photos.size, photos.size)
                    if (photos.isEmpty()) showState(R.string.gallery_empty, loading = false)
                    else {
                        stateContainer.visibility = GONE
                        grid.visibility = VISIBLE
                        adapter.notifyDataSetChanged()
                    }
                }.onFailure { showState(R.string.gallery_load_failed, loading = false, canRetry = true) }
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
        restoreBackgroundAccessibility()
        previousInputFocus?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled }?.requestFocus()
        if (isTouchExplorationEnabled()) previousAccessibilityFocus?.let { previous -> previous.post {
            if (previous.isAttachedToWindow && previous.isShown) {
                previous.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        } }
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
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): PhotoLibrary.Entry = entries[position]
        override fun getItemId(position: Int): Long = entries[position].photo.uri.toString().hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? PhotoCell ?: PhotoCell(context)
            val entry = getItem(position)
            val photo = entry.photo
            val bitmap = cache.get(photo.uri.toString())
            cell.image.setImageBitmap(bitmap)
            if (bitmap == null) cell.image.setImageResource(R.drawable.ic_gallery_placeholder)
            cell.label.text = if (photo.uri.toString() in failedThumbnails) context.getString(R.string.gallery_thumbnail_failed)
                else photo.styleCode
            cell.contentDescription = context.getString(R.string.gallery_item_description, photo.styleName,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.capturedAtMillis)))
            cell.setOnClickListener { photoSelectedListener?.invoke(photo) }
            if (bitmap == null) requestThumbnail(photo)
            return cell
        }
    }

    private inner class PhotoCell(context: Context) : FrameLayout(context) {
        val image = ImageView(context)
        val label = TextView(context)
        init {
            id = R.id.gallery_item
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = buttonDelegate()
            background = rounded(0xFF1D2026.toInt(), dp(10).toFloat())
            clipToOutline = true
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            label.apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                includeFontPadding = false
                minimumHeight = dp(28)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // A dark scrim keeps the label readable over bright thumbnails.
                setBackgroundColor(0xDE000000.toInt())
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            foreground = RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), null, rounded(Color.WHITE, dp(10).toFloat()))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        }
    }

    private fun addHeader() {
        topBar.titleView.apply {
            text = context.getString(R.string.gallery_title)
        }
        subtitle.apply {
            id = R.id.gallery_count
            visibility = VISIBLE
            text = context.getString(R.string.gallery_newest_first)
        }
        back.apply {
            id = R.id.gallery_back
            contentDescription = context.getString(R.string.gallery_back_description)
        }
        topBar.setOnBackClickListener { dismiss(); backListener?.invoke() }
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
            text = context.getString(R.string.gallery_retry)
            textSize = 14f; setTextColor(ACCENT); gravity = Gravity.CENTER
            minimumHeight = dp(48)
            includeFontPadding = false
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = touchBackground(); isClickable = true; isFocusable = true
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
        retry.visibility = if (canRetry) VISIBLE else GONE
        stateText.setText(textRes)
        subtitle.text = context.getString(R.string.gallery_newest_first)
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
