package com.phytoy.sample

internal enum class CameraFlashMode {
    OFF,
    AUTO,
    ON,
}

internal data class CaptureMetadata(
    val iso: Int?,
    val exposureTimeNanos: Long?,
    val focalLengthMillimeters: Float?,
    val lensFacing: String,
    val styleName: String,
    val styleCode: String,
    val styleVersion: String,
    val outputAspect: Float,
    val maximumOutputPixels: Long,
    val capturedAtMillis: Long,
    val zoomRatio: Float,
    val exposureCompensation: Float,
    val flashMode: CameraFlashMode,
)
