package com.phytoy.sample

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Shared presentation tokens. Camera and persistence policies do not depend on this theme. */
internal object ToviTheme {
    const val SURFACE = 0xFF0B0B0C.toInt()
    const val CARD = 0xFF171719.toInt()
    const val PRIMARY = 0xFFFFC629.toInt()
    const val TEXT = 0xFFF5F5F5.toInt()
    const val MUTED = 0xFFA5A5AC.toInt()
    const val BORDER = 0xFF303034.toInt()

    fun card(context: Context, radiusDp: Int = 20, color: Int = CARD): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setStroke(maxOf(1, context.resources.displayMetrics.density.toInt()), BORDER)
        }

    fun actionBackground(context: Context, primary: Boolean = false): Drawable {
        val shape = card(context, 28, if (primary) PRIMARY else CARD)
        if (primary) shape.setStroke(0, PRIMARY)
        return RippleDrawable(ColorStateList.valueOf(if (primary) 0x22000000 else 0x25FFFFFF), shape, null)
    }
}

/** Public labels are presentation only; stored camera/profile identifiers stay unchanged. */
internal fun CameraStyle.uiName(context: Context): String = when (this) {
    CameraStyle.HARINEZUMI_2PP -> context.getString(R.string.camera_style_color_ui)
    CameraStyle.HARINEZUMI_2PP_MONO -> context.getString(R.string.camera_style_mono_ui)
    else -> name(context)
}
