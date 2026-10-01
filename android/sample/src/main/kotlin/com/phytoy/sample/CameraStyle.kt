package com.phytoy.sample

import android.content.Context

internal enum class CameraStyle(
    val titleRes: Int,
    val descriptionRes: Int,
    val shortCode: String,
    val displayVersion: String,
    val profileVersion: String,
    val toyProfileAsset: String,
    /** Saved and visible portrait width divided by height. */
    val portraitAspect: Float,
    /** Capture policy; independent of the host sensor ISO and resolution. */
    val maximumCapturePixels: Long = 12_500_000L,
    val preferredInputAspect: Float? = null,
) {
    HARINEZUMI_2PP(
        titleRes = R.string.style_harinezumi_2pp,
        descriptionRes = R.string.style_harinezumi_2pp_description,
        shortCode = "DH2",
        displayVersion = "0.4",
        profileVersion = "0.4.0",
        toyProfileAsset = "toy_harinezumi_2pp_daylight_v0_4.ptp",
        portraitAspect = 3f / 4f,
        maximumCapturePixels = 3_200_000L,
        preferredInputAspect = 4f / 3f,
    ),
    HARINEZUMI_2PP_MONO(
        titleRes = R.string.style_harinezumi_2pp_mono,
        descriptionRes = R.string.style_harinezumi_2pp_mono_description,
        shortCode = "DH/M",
        displayVersion = "0.2",
        profileVersion = "0.2.0",
        toyProfileAsset = "toy_harinezumi_2pp_mono_v0_2.ptp",
        portraitAspect = 3f / 4f,
        maximumCapturePixels = 3_200_000L,
        preferredInputAspect = 4f / 3f,
    ),
    DIGITAL_01(
        titleRes = R.string.style_digital_01,
        descriptionRes = R.string.style_digital_01_description,
        shortCode = "D01",
        displayVersion = "1.2",
        profileVersion = "1.2.0",
        toyProfileAsset = "toy_phytoy_digital_01_v1_2.ptp",
        portraitAspect = 3f / 4f,
    ),
    PLASTIC_82(
        titleRes = R.string.style_plastic_82,
        descriptionRes = R.string.style_plastic_82_description,
        shortCode = "P82",
        displayVersion = "1.0",
        profileVersion = "1.0.0",
        toyProfileAsset = "toy_phytoy_plastic_82_v1.ptp",
        portraitAspect = 1f,
    ),
    STREET_84(
        titleRes = R.string.style_street_84,
        descriptionRes = R.string.style_street_84_description,
        shortCode = "S84",
        displayVersion = "1.0",
        profileVersion = "1.0.0",
        toyProfileAsset = "toy_phytoy_street_84_v1.ptp",
        portraitAspect = 2f / 3f,
    ),
    FISHEYE_05(
        titleRes = R.string.style_fisheye_05,
        descriptionRes = R.string.style_fisheye_05_description,
        shortCode = "F05",
        displayVersion = "1.0",
        profileVersion = "1.0.0",
        toyProfileAsset = "toy_phytoy_fisheye_05_v1.ptp",
        portraitAspect = 1f,
    );

    fun name(context: Context): String = context.getString(titleRes)
}
