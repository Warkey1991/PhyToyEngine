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
    val digital01Version: String,
    val capturedAtMillis: Long,
    val zoomRatio: Float,
    val exposureCompensation: Float,
    val flashMode: CameraFlashMode,
)
