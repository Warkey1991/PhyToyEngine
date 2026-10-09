package com.phytoy.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.util.concurrent.Executors

/** Native, decorative crops from the supplied artwork; never an engine input or a photo record. */
internal class CameraArtworkView(context: Context) : View(context) {
    private var key = CameraArtworkCache.Key(CameraStyle.HARINEZUMI_2PP, "card")
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; setStyle(key.style) }

    fun setStyle(value: CameraStyle) = load(CameraArtworkCache.Key(value, "card"))
    fun showHero(value: CameraStyle) = load(CameraArtworkCache.Key(value, "hero"))
    fun showSample(value: CameraStyle) = load(CameraArtworkCache.Key(value, "sample"))
    fun showStreetSample(index: Int) = load(CameraArtworkCache.Key(CameraStyle.STREET_84, "street${index.coerceIn(0, 2)}"))

    private fun load(value: CameraArtworkCache.Key) {
        key = value
        bitmap = CameraArtworkCache.get(value)
        if (bitmap == null) CameraArtworkCache.load(context, value) { result ->
            if (key == value) { bitmap = result; invalidate() }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        // The artwork fills its native card. Actual captured photos use ZoomablePhotoView's fit policy.
        val scale = maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        bounds.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(image, null, bounds, paint)
    }
}

internal object CameraArtworkCache {
    data class Key(val style: CameraStyle, val mode: String)
    private val cache = object : LruCache<Key, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tovicam-ui-art").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    fun get(key: Key): Bitmap? = cache.get(key)
    fun get(style: CameraStyle): Bitmap? = get(Key(style, "card"))
    fun load(context: Context, style: CameraStyle, onReady: (Bitmap?) -> Unit) = load(context, Key(style, "card"), onReady)

    fun load(context: Context, key: Key, onReady: (Bitmap?) -> Unit) {
        val resources = context.applicationContext.resources
        worker.execute {
            val image = cache.get(key) ?: runCatching {
                val (resource, region) = region(key)
                resources.openRawResource(resource).use { stream ->
                    val decoder = BitmapRegionDecoder.newInstance(stream, false)
                        ?: error("Decorative artwork cannot be decoded")
                    try {
                        val actualRegion = Rect(region).apply { intersect(0, 0, decoder.width, decoder.height) }
                        val decoded = decoder.decodeRegion(actualRegion, BitmapFactory.Options().apply {
                            inSampleSize = if (key.mode == "card") 1 else 2
                        })
                        if (key.style in listOf(CameraStyle.FISHEYE_05, CameraStyle.STREET_84) &&
                            key.mode == "card" && decoded != null) {
                            // Rebuild these decorative tiles from clean sky regions and
                            // existing camera layers. Reference padlocks are never baked
                            // into the assets; actual ownership controls the native lock.
                            val tile = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(tile)
                            val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                            canvas.drawBitmap(decoded, null, Rect(0, 0, 192, 192), brush)
                            val cameraResource = if (key.style == CameraStyle.STREET_84)
                                R.drawable.camera_art_street else R.drawable.camera_art_fisheye
                            val camera = BitmapFactory.decodeResource(resources, cameraResource,
                                BitmapFactory.Options().apply { inSampleSize = 4 })
                            if (camera != null) {
                                canvas.drawBitmap(camera, null, Rect(0, 0, 192, 192), brush)
                                camera.recycle()
                            }
                            decoded.recycle()
                            tile
                        } else decoded
                    } finally { decoder.recycle() }
                }
            }.getOrNull()?.also { cache.put(key, it) }
            main.post { onReady(image) }
        }
    }

    private fun region(key: Key): Pair<Int, Rect> = when {
        key.mode.startsWith("street") -> R.drawable.design_street_reference to when (key.mode) {
            "street0" -> Rect(36, 920, 312, 1276)
            "street1" -> Rect(335, 920, 607, 1276)
            else -> Rect(633, 920, 906, 1276)
        }
        key.mode == "sample" -> R.drawable.design_plastic_reference to Rect(70, 618, 874, 1087)
        key.style == CameraStyle.PLASTIC_82 -> R.drawable.design_plastic_reference to Rect(488, 230, 930, 600)
        key.style == CameraStyle.STREET_84 && key.mode == "card" ->
            R.drawable.design_plastic_reference to Rect(795, 1138, 935, 1162)
        key.style == CameraStyle.STREET_84 -> R.drawable.design_street_reference to Rect(90, 170, 853, 702)
        key.mode == "hero" && key.style != CameraStyle.PLASTIC_82 && key.style != CameraStyle.STREET_84 ->
            when (key.style) {
                CameraStyle.HARINEZUMI_2PP -> R.drawable.camera_art_dh_color
                CameraStyle.HARINEZUMI_2PP_MONO -> R.drawable.camera_art_dh_mono
                CameraStyle.DIGITAL_01 -> R.drawable.camera_art_digital
                else -> R.drawable.camera_art_fisheye
            } to Rect(0, 0, 752, 752)
        key.style == CameraStyle.FISHEYE_05 -> R.drawable.design_camera_reference to Rect(792, 1174, 904, 1209)
        else -> R.drawable.design_plastic_reference to when (key.style) {
            CameraStyle.HARINEZUMI_2PP -> Rect(32, 1137, 180, 1281)
            CameraStyle.HARINEZUMI_2PP_MONO -> Rect(215, 1137, 360, 1281)
            CameraStyle.DIGITAL_01 -> Rect(398, 1137, 545, 1281)
            else -> error("Unsupported decorative region")
        }
    }
}
