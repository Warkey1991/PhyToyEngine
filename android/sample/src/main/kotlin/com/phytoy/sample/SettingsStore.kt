package com.phytoy.sample

import android.content.Context

/** Output ceilings; an individual style or camera may impose a smaller limit. */
internal enum class PhotoQuality(val maxPixels: Long, val titleRes: Int) {
    HIGH(12_500_000L, R.string.settings_quality_high),
    BALANCED(5_000_000L, R.string.settings_quality_balanced),
    COMPACT(3_200_000L, R.string.settings_quality_compact),
}

internal data class SettingsSnapshot(
    val defaultStyle: CameraStyle = CameraStyle.HARINEZUMI_2PP,
    val rememberLastStyle: Boolean = true,
    val photoQuality: PhotoQuality = PhotoQuality.BALANCED,
    val gridEnabled: Boolean = false,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val reviewAfterCapture: Boolean = true,
)

/** User-controlled preferences only. Existing capture/gallery state is kept separately. */
internal class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "phytoy_settings_v1", Context.MODE_PRIVATE,
    )

    fun load(): SettingsSnapshot = SettingsSnapshot(
        defaultStyle = CameraStyle.entries.firstOrNull {
            it.name == preferences.getString("default_style", null)
        } ?: CameraStyle.HARINEZUMI_2PP,
        rememberLastStyle = preferences.getBoolean("remember_last_style", true),
        photoQuality = PhotoQuality.entries.firstOrNull {
            it.name == preferences.getString("photo_quality", null)
        } ?: PhotoQuality.BALANCED,
        gridEnabled = preferences.getBoolean("grid_enabled", false),
        soundEnabled = preferences.getBoolean("sound_enabled", true),
        hapticsEnabled = preferences.getBoolean("haptics_enabled", true),
        reviewAfterCapture = preferences.getBoolean("review_after_capture", true),
    )

    fun update(settings: SettingsSnapshot) {
        preferences.edit()
            .putString("default_style", settings.defaultStyle.name)
            .putBoolean("remember_last_style", settings.rememberLastStyle)
            .putString("photo_quality", settings.photoQuality.name)
            .putBoolean("grid_enabled", settings.gridEnabled)
            .putBoolean("sound_enabled", settings.soundEnabled)
            .putBoolean("haptics_enabled", settings.hapticsEnabled)
            .putBoolean("review_after_capture", settings.reviewAfterCapture)
            .apply()
    }

    fun reset(): SettingsSnapshot = SettingsSnapshot().also(::update)

    fun resolveStartupStyle(lastStyle: CameraStyle?): CameraStyle {
        val settings = load()
        return if (settings.rememberLastStyle) lastStyle ?: settings.defaultStyle else settings.defaultStyle
    }
}
