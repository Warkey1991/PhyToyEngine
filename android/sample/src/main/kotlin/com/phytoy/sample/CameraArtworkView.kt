package com.phytoy.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.util.concurrent.Executors

/** Decorative product illustration; never used as an engine or sample-photo input. */
internal class CameraArtworkView(context: Context) : View(context) {
    private var style = CameraStyle.HARINEZUMI_2PP
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; setStyle(style) }

    fun setStyle(value: CameraStyle) {
        style = value
        bitmap = CameraArtworkCache.get(value)
        if (bitmap == null) CameraArtworkCache.load(context, value) { result ->
            if (style == value) { bitmap = result; invalidate() }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        bounds.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(image, null, bounds, paint)
    }
}

internal object CameraArtworkCache {
    private val cache = LruCache<CameraStyle, Bitmap>(6)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tovicam-ui-art").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun get(style: CameraStyle): Bitmap? = cache.get(style)

    fun load(context: Context, style: CameraStyle, onReady: (Bitmap?) -> Unit) {
        val resources = context.applicationContext.resources
        worker.execute {
            val image = cache.get(style) ?: runCatching {
                BitmapFactory.decodeResource(resources, when (style) {
                    CameraStyle.HARINEZUMI_2PP -> R.drawable.camera_art_dh_color
                    CameraStyle.HARINEZUMI_2PP_MONO -> R.drawable.camera_art_dh_mono
                    CameraStyle.DIGITAL_01 -> R.drawable.camera_art_digital
                    CameraStyle.PLASTIC_82 -> R.drawable.camera_art_plastic
                    CameraStyle.STREET_84 -> R.drawable.camera_art_street
                    CameraStyle.FISHEYE_05 -> R.drawable.camera_art_fisheye
                }, BitmapFactory.Options().apply { inSampleSize = 2 })
            }.getOrNull()?.also { cache.put(style, it) }
            main.post { onReady(image) }
        }
    }
}
