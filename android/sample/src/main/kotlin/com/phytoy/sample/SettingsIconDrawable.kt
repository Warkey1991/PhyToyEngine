package com.phytoy.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal enum class SettingsRowIcon {
    CAMERA, HISTORY, PHOTO, GRID, SOUND, HAPTICS, REVIEW, PURCHASES, INFO, PRIVACY, LICENSE, HELP, RESET, CHEVRON,
}

/** Small vector-style settings icons. No text or bitmap assets are baked into the controls. */
internal class SettingsIconDrawable(context: Context, private val icon: SettingsRowIcon) : Drawable() {
    private val size = (40 * context.resources.displayMetrics.density).toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ToviTheme.PRIMARY
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF252527.toInt() }

    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size

    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
        if (icon == SettingsRowIcon.CHEVRON) {
            stroke.color = ToviTheme.MUTED
            path(canvas, 12f, 11f, 34f, 24f, 12f, 37f)
            canvas.restoreToCount(checkpoint)
            return
        }
        canvas.drawCircle(24f, 24f, 23f, fill)
        stroke.color = ToviTheme.PRIMARY
        when (icon) {
            SettingsRowIcon.CAMERA -> {
                canvas.drawRoundRect(12f, 18f, 36f, 33f, 3f, 3f, stroke)
                path(canvas, 17f, 18f, 19f, 14f, 27f, 14f, 29f, 18f)
                canvas.drawCircle(24f, 25.5f, 5f, stroke)
                canvas.drawPoint(32f, 21f, stroke)
            }
            SettingsRowIcon.HISTORY -> {
                canvas.drawArc(14f, 14f, 35f, 35f, -90f, 285f, false, stroke)
                path(canvas, 10f, 20f, 14f, 27f, 21f, 23f)
                path(canvas, 24f, 18f, 24f, 25f, 29f, 28f)
            }
            SettingsRowIcon.PHOTO -> {
                canvas.drawRoundRect(13f, 13f, 35f, 35f, 3f, 3f, stroke)
                canvas.drawCircle(20f, 20f, 2f, stroke)
                path(canvas, 14f, 31f, 22f, 24f, 27f, 28f, 31f, 24f, 35f, 29f)
            }
            SettingsRowIcon.GRID -> {
                canvas.drawLine(19f, 12f, 19f, 36f, stroke)
                canvas.drawLine(29f, 12f, 29f, 36f, stroke)
                canvas.drawLine(12f, 19f, 36f, 19f, stroke)
                canvas.drawLine(12f, 29f, 36f, 29f, stroke)
            }
            SettingsRowIcon.SOUND -> {
                val speaker = Path().apply {
                    moveTo(12f, 20f); lineTo(17f, 20f); lineTo(23f, 15f)
                    lineTo(23f, 33f); lineTo(17f, 28f); lineTo(12f, 28f); close()
                }
                canvas.drawPath(speaker, stroke)
                canvas.drawArc(21f, 19f, 31f, 29f, -65f, 130f, false, stroke)
                canvas.drawArc(19f, 14f, 38f, 34f, -60f, 120f, false, stroke)
            }
            SettingsRowIcon.HAPTICS -> {
                for (x in listOf(16f, 24f, 32f)) {
                    path(canvas, x + 2f, 13f, x - 2f, 20f, x + 2f, 27f, x - 2f, 35f)
                }
            }
            SettingsRowIcon.REVIEW -> {
                val eye = Path().apply {
                    moveTo(11f, 24f); quadTo(24f, 9f, 37f, 24f); quadTo(24f, 39f, 11f, 24f)
                }
                canvas.drawPath(eye, stroke)
                canvas.drawCircle(24f, 24f, 4f, stroke)
            }
            SettingsRowIcon.PURCHASES -> {
                path(canvas, 11f, 13f, 15f, 13f, 19f, 29f, 32f, 29f, 36f, 18f, 17f, 18f)
                canvas.drawCircle(21f, 35f, 1.3f, stroke)
                canvas.drawCircle(31f, 35f, 1.3f, stroke)
            }
            SettingsRowIcon.INFO -> {
                canvas.drawCircle(24f, 24f, 12f, stroke)
                canvas.drawPoint(24f, 18f, stroke)
                path(canvas, 22f, 23f, 24f, 23f, 24f, 31f)
                canvas.drawLine(21f, 31f, 27f, 31f, stroke)
            }
            SettingsRowIcon.PRIVACY -> {
                canvas.drawRoundRect(15f, 22f, 33f, 35f, 2f, 2f, stroke)
                canvas.drawArc(18f, 12f, 30f, 28f, 180f, 180f, false, stroke)
                canvas.drawLine(18f, 20f, 18f, 22f, stroke)
                canvas.drawLine(30f, 20f, 30f, 22f, stroke)
                canvas.drawLine(24f, 27f, 24f, 30f, stroke)
            }
            SettingsRowIcon.LICENSE -> {
                canvas.drawRoundRect(16f, 12f, 33f, 36f, 2f, 2f, stroke)
                canvas.drawLine(20f, 19f, 29f, 19f, stroke)
                canvas.drawLine(20f, 24f, 29f, 24f, stroke)
                canvas.drawLine(20f, 29f, 27f, 29f, stroke)
            }
            SettingsRowIcon.HELP -> {
                canvas.drawCircle(24f, 24f, 12f, stroke)
                canvas.drawArc(20f, 17f, 28f, 24f, 180f, 240f, false, stroke)
                canvas.drawLine(25.5f, 23f, 24f, 27f, stroke)
                canvas.drawPoint(24f, 31f, stroke)
            }
            SettingsRowIcon.RESET -> {
                canvas.drawArc(14f, 14f, 35f, 35f, -150f, 300f, false, stroke)
                path(canvas, 13f, 13f, 13f, 21f, 21f, 21f)
            }
            SettingsRowIcon.CHEVRON -> Unit
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun path(canvas: Canvas, vararg points: Float) {
        val line = Path().apply {
            moveTo(points[0], points[1])
            for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
        }
        canvas.drawPath(line, stroke)
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
