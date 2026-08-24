package com.phytoy.engine

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import java.io.Closeable
import java.io.File

/**
 * Owns one PhyToyEngine instance, a Camera2 PRIVATE input and a Vulkan GPU output surface.
 * Camera2 must target [inputSurface] only. Every displayed frame has passed through the complete
 * PhyToy profile graph; rendering and presentation run on a dedicated native worker.
 */
class PhyToyCameraSession private constructor(
    private var nativeHandle: Long,
    val inputSurface: Surface,
    private val normalProcessingFrameRate: Int,
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val outputRotationDegrees: Int,
) : Closeable {

    private val lifecycleLock = Any()
    private val captureCallLock = Any()
    @Volatile private var observedThermalStatus = THERMAL_STATUS_UNAVAILABLE
    @Volatile private var appliedProcessingFrameRate = -1
    private var thermalPowerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    data class Snapshot(
        val receivedFrames: Long,
        val renderedFrames: Long,
        val droppedFrames: Long,
        val errorFrames: Long,
        val lastLatencyUs: Long,
        val maximumLatencyUs: Long,
        val queueSubmissions: Long,
        val hardwareBufferImports: Long,
        val zeroCopyFrames: Long,
        val resourceAllocations: Long,
        val allocatedBytes: Long,
        val latencyP50Us: Long,
        val latencyP95Us: Long,
        val inputImageFormat: Long,
        val inputBufferFormat: Long,
        val inputBufferUsage: Long,
        val throttledFrames: Long,
        val targetProcessingFps: Int,
        val presentedFrames: Long,
        val swapchainRecreates: Long,
        val outputWidth: Int,
        val outputHeight: Int,
        val thermalStatus: Int,
    )

    /** One fully processed sRGB frame before preview crop/scale, with output rotation metadata. */
    data class CapturedFrame(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val argb8888: IntArray,
    )

    fun snapshot(): Snapshot {
        val values = synchronized(lifecycleLock) {
            if (nativeHandle == 0L) null else nativeSnapshot(nativeHandle)
        }
        if (values == null) return emptySnapshot()
        check(values.size == SNAPSHOT_FIELD_COUNT) { "Unexpected native snapshot size" }
        return Snapshot(
            receivedFrames = values[0],
            renderedFrames = values[1],
            droppedFrames = values[2],
            errorFrames = values[3],
            lastLatencyUs = values[4],
            maximumLatencyUs = values[5],
            queueSubmissions = values[6],
            hardwareBufferImports = values[7],
            zeroCopyFrames = values[8],
            resourceAllocations = values[9],
            allocatedBytes = values[10],
            latencyP50Us = values[11],
            latencyP95Us = values[12],
            inputImageFormat = values[13],
            inputBufferFormat = values[14],
            inputBufferUsage = values[15],
            throttledFrames = values[16],
            targetProcessingFps = values[17].toInt(),
            presentedFrames = values[18],
            swapchainRecreates = values[19],
            outputWidth = values[20].toInt(),
            outputHeight = values[21].toInt(),
            thermalStatus = observedThermalStatus,
        )
    }

    fun lastError(): String = synchronized(lifecycleLock) {
        nativeHandle.takeIf { it != 0L }?.let(::nativeLastError).orEmpty()
    }

    /**
     * Waits for the next admitted Camera2 frame and returns its final Digital 01 pixels.
     * The selected frame still uses one Vulkan submission and is presented to the preview;
     * only this explicitly requested frame performs a CPU readback.
     */
    fun captureNextFrame(timeoutMillis: Int = DEFAULT_CAPTURE_TIMEOUT_MILLIS): CapturedFrame {
        require(timeoutMillis in 250..MAXIMUM_CAPTURE_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 250..$MAXIMUM_CAPTURE_TIMEOUT_MILLIS"
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "captureNextFrame must run off the main thread"
        }
        val pixels = synchronized(captureCallLock) {
            val handle = synchronized(lifecycleLock) {
                check(nativeHandle != 0L) { "Camera session is closed" }
                nativeHandle
            }
            nativeCaptureNextFrame(handle, timeoutMillis)
        }
        check(pixels.size == captureWidth * captureHeight) {
            "Unexpected native capture size"
        }
        return CapturedFrame(
            width = captureWidth,
            height = captureHeight,
            rotationDegrees = outputRotationDegrees,
            argb8888 = pixels,
        )
    }

    override fun close() {
        detachThermalControl()
        val handle = synchronized(lifecycleLock) {
            nativeHandle.also { nativeHandle = 0L }
        }
        if (handle == 0L) return
        nativeCancelCapture(handle)
        synchronized(captureCallLock) {
            // Wait for a cancelled or just-completed JNI capture call before deletion.
        }
        inputSurface.release()
        nativeClose(handle)
    }

    private fun emptySnapshot() = Snapshot(
        receivedFrames = 0,
        renderedFrames = 0,
        droppedFrames = 0,
        errorFrames = 0,
        lastLatencyUs = 0,
        maximumLatencyUs = 0,
        queueSubmissions = 0,
        hardwareBufferImports = 0,
        zeroCopyFrames = 0,
        resourceAllocations = 0,
        allocatedBytes = 0,
        latencyP50Us = 0,
        latencyP95Us = 0,
        inputImageFormat = 0,
        inputBufferFormat = 0,
        inputBufferUsage = 0,
        throttledFrames = 0,
        targetProcessingFps = 0,
        presentedFrames = 0,
        swapchainRecreates = 0,
        outputWidth = 0,
        outputHeight = 0,
        thermalStatus = observedThermalStatus,
    )

    private fun attachThermalControl(context: Context, thermalAdaptive: Boolean) {
        if (!thermalAdaptive || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            applyThermalStatus(THERMAL_STATUS_UNAVAILABLE)
            return
        }
        attachThermalControlApi29(context)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun attachThermalControlApi29(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener(::applyThermalStatus)
        thermalPowerManager = powerManager
        thermalListener = listener
        applyThermalStatus(powerManager.currentThermalStatus)
        powerManager.addThermalStatusListener(context.mainExecutor, listener)
    }

    private fun detachThermalControl() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) detachThermalControlApi29()
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun detachThermalControlApi29() {
        val listener = thermalListener ?: return
        thermalPowerManager?.removeThermalStatusListener(listener)
        thermalListener = null
        thermalPowerManager = null
    }

    private fun applyThermalStatus(status: Int) {
        val targetFrameRate = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> 0
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 3
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 5
            status >= PowerManager.THERMAL_STATUS_LIGHT -> 10
            else -> normalProcessingFrameRate
        }
        synchronized(lifecycleLock) {
            observedThermalStatus = status
            if (nativeHandle != 0L && targetFrameRate != appliedProcessingFrameRate) {
                nativeSetProcessingFrameRate(nativeHandle, targetFrameRate)
                appliedProcessingFrameRate = targetFrameRate
            }
        }
    }

    companion object {
        private const val SNAPSHOT_FIELD_COUNT = 22
        private const val PROFILE_ASSET_DIRECTORY = "phytoy"
        private const val PROFILE_CACHE_VERSION = "0.3.1"
        private const val THERMAL_STATUS_UNAVAILABLE = -1
        private const val DEFAULT_PROCESSING_FRAME_RATE = 15
        private const val DEFAULT_CAPTURE_TIMEOUT_MILLIS = 3_000
        private const val MAXIMUM_CAPTURE_TIMEOUT_MILLIS = 10_000

        init {
            System.loadLibrary("phytoy_core")
            System.loadLibrary("phytoy_android")
        }

        @JvmStatic
        fun open(
            context: Context,
            width: Int,
            height: Int,
            outputSurface: Surface,
            outputRotationDegrees: Int,
            maxImages: Int = 4,
            processingFrameRateLimit: Int = DEFAULT_PROCESSING_FRAME_RATE,
            thermalAdaptive: Boolean = true,
            hostProfileAsset: String = "host_generic_srgb.ptp",
            toyProfileAsset: String = "toy_phytoy_digital_01_v1_1.ptp",
        ): PhyToyCameraSession {
            require(width > 0 && height > 0) { "Camera dimensions must be positive" }
            require(maxImages >= 3) { "maxImages must be at least 3 for asynchronous latest-frame processing" }
            require(processingFrameRateLimit in 1..60) {
                "processingFrameRateLimit must be in 1..60"
            }
            require(outputRotationDegrees in setOf(0, 90, 180, 270)) {
                "outputRotationDegrees must be 0, 90, 180 or 270"
            }
            val host = materializeProfile(context, hostProfileAsset)
            val toy = materializeProfile(context, toyProfileAsset)
            val handle = nativeCreate(
                host.absolutePath,
                toy.absolutePath,
                width,
                height,
                maxImages,
                outputSurface,
                outputRotationDegrees,
            )
            check(handle != 0L) { "Unable to create native PhyToy Camera2 session" }
            var session: PhyToyCameraSession? = null
            return try {
                val surface = checkNotNull(nativeInputSurface(handle)) {
                    "Native Camera2 input surface is unavailable"
                }
                PhyToyCameraSession(
                    handle,
                    surface,
                    processingFrameRateLimit,
                    width,
                    height,
                    outputRotationDegrees,
                ).also {
                    session = it
                    it.attachThermalControl(context.applicationContext, thermalAdaptive)
                }
            } catch (exception: Throwable) {
                session?.close() ?: nativeClose(handle)
                throw exception
            }
        }

        private fun materializeProfile(context: Context, name: String): File {
            require('/' !in name && '\\' !in name) { "Profile asset must be a file name" }
            val directory = File(context.noBackupFilesDir, "phytoy/$PROFILE_CACHE_VERSION")
            check(directory.exists() || directory.mkdirs()) { "Unable to create profile cache" }
            val target = File(directory, name)
            context.assets.open("$PROFILE_ASSET_DIRECTORY/$name").use { source ->
                target.outputStream().use(source::copyTo)
            }
            return target
        }

        @JvmStatic private external fun nativeCreate(
            hostProfilePath: String,
            toyProfilePath: String,
            width: Int,
            height: Int,
            maxImages: Int,
            outputSurface: Surface,
            outputRotationDegrees: Int,
        ): Long
        @JvmStatic private external fun nativeInputSurface(handle: Long): Surface?
        @JvmStatic private external fun nativeSnapshot(handle: Long): LongArray
        @JvmStatic private external fun nativeCaptureNextFrame(
            handle: Long,
            timeoutMillis: Int,
        ): IntArray
        @JvmStatic private external fun nativeCancelCapture(handle: Long)
        @JvmStatic private external fun nativeSetProcessingFrameRate(handle: Long, framesPerSecond: Int)
        @JvmStatic private external fun nativeLastError(handle: Long): String
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
