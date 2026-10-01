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
 * Owns one PhyToyEngine instance, Camera2 PRIVATE preview/still inputs and a Vulkan GPU output.
 * Camera2 must target [inputSurface] for repeating preview requests and [stillCaptureSurface] for
 * explicit high-resolution captures. Every displayed and captured frame passes through the same
 * PhyToy profile graph; rendering and presentation run on a dedicated native worker.
 */
class PhyToyCameraSession private constructor(
    private var nativeHandle: Long,
    val inputSurface: Surface,
    val stillCaptureSurface: Surface,
    private val normalProcessingFrameRate: Int,
    private val previewWidth: Int,
    private val previewHeight: Int,
    private val stillCaptureWidth: Int,
    private val stillCaptureHeight: Int,
    private val outputRotationDegrees: Int,
) : Closeable {

    private val lifecycleLock = Any()
    private val captureCallLock = Any()
    @Volatile private var observedThermalStatus = THERMAL_STATUS_UNAVAILABLE
    @Volatile private var appliedProcessingFrameRate = -1
    private var processingFrameRateBudget = normalProcessingFrameRate
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
     * Waits for the next admitted Camera2 frame and returns its final active-profile pixels.
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
        check(pixels.size == previewWidth * previewHeight) {
            "Unexpected native capture size"
        }
        return CapturedFrame(
            width = previewWidth,
            height = previewHeight,
            rotationDegrees = outputRotationDegrees,
            argb8888 = pixels,
        )
    }

    /**
     * Arms the high-resolution reader, invokes [trigger] to submit one Camera2 still request, then
     * waits for that exact PRIVATE frame to finish the active Vulkan profile graph. [trigger] runs on
     * the caller's background thread after the native capture request is ready, avoiding races
     * between Camera2 delivery and readback registration.
     */
    fun captureStillFrame(
        timeoutMillis: Int = DEFAULT_CAPTURE_TIMEOUT_MILLIS,
        trigger: () -> Unit,
    ): CapturedFrame {
        require(timeoutMillis in 250..MAXIMUM_CAPTURE_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 250..$MAXIMUM_CAPTURE_TIMEOUT_MILLIS"
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "captureStillFrame must run off the main thread"
        }
        val pixels = synchronized(captureCallLock) {
            val handle = synchronized(lifecycleLock) {
                check(nativeHandle != 0L) { "Camera session is closed" }
                nativeHandle
            }
            val requestId = nativeBeginStillCapture(handle)
            try {
                trigger()
                nativeAwaitCapture(handle, requestId, timeoutMillis)
            } catch (exception: Throwable) {
                nativeCancelCapture(handle)
                throw exception
            }
        }
        check(pixels.size == stillCaptureWidth * stillCaptureHeight) {
            "Unexpected native high-resolution capture size"
        }
        return CapturedFrame(
            width = stillCaptureWidth,
            height = stillCaptureHeight,
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
        stillCaptureSurface.release()
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

    /** Cap preview cadence to measured device throughput; thermal limits still take priority. */
    fun setPreviewFrameRateBudget(framesPerSecond: Int) {
        require(framesPerSecond in 1..normalProcessingFrameRate)
        synchronized(lifecycleLock) {
            processingFrameRateBudget = framesPerSecond
            applyThermalStatus(observedThermalStatus)
        }
    }

    private fun applyThermalStatus(status: Int) {
        val thermalFrameRate = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> 0
            status >= PowerManager.THERMAL_STATUS_SEVERE -> minOf(normalProcessingFrameRate, 5)
            status >= PowerManager.THERMAL_STATUS_MODERATE -> minOf(normalProcessingFrameRate, 15)
            status >= PowerManager.THERMAL_STATUS_LIGHT -> minOf(normalProcessingFrameRate, 24)
            else -> normalProcessingFrameRate
        }
        synchronized(lifecycleLock) {
            val targetFrameRate = minOf(thermalFrameRate, processingFrameRateBudget)
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
        private const val MAXIMUM_IMAGE_DIMENSION = 8_192
        private const val MAXIMUM_IMAGE_PIXELS = 16_777_216L

        init {
            System.loadLibrary("phytoy_core")
            System.loadLibrary("phytoy_android")
        }

        @JvmStatic
        fun open(
            context: Context,
            width: Int,
            height: Int,
            stillWidth: Int = width,
            stillHeight: Int = height,
            outputSurface: Surface,
            outputRotationDegrees: Int,
            maxImages: Int = 4,
            processingFrameRateLimit: Int = DEFAULT_PROCESSING_FRAME_RATE,
            thermalAdaptive: Boolean = true,
            hostProfileAsset: String = "host_generic_srgb.ptp",
            toyProfileAsset: String = "toy_phytoy_digital_01_v1_2.ptp",
        ): PhyToyCameraSession {
            validateDimensions(width, height, "Preview")
            validateDimensions(stillWidth, stillHeight, "Still capture")
            require(maxImages in 3..16) { "maxImages must be in 3..16 for bounded asynchronous processing" }
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
                stillWidth,
                stillHeight,
                maxImages,
                outputSurface,
                outputRotationDegrees,
            )
            check(handle != 0L) { "Unable to create native PhyToy Camera2 session" }
            var session: PhyToyCameraSession? = null
            var input: Surface? = null
            var stillInput: Surface? = null
            return try {
                val surface = checkNotNull(nativeInputSurface(handle)) {
                    "Native Camera2 input surface is unavailable"
                }.also { input = it }
                val stillSurface = checkNotNull(nativeStillCaptureSurface(handle)) {
                    "Native Camera2 still-capture surface is unavailable"
                }.also { stillInput = it }
                PhyToyCameraSession(
                    handle,
                    surface,
                    stillSurface,
                    processingFrameRateLimit,
                    width,
                    height,
                    stillWidth,
                    stillHeight,
                    outputRotationDegrees,
                ).also {
                    session = it
                    it.attachThermalControl(context.applicationContext, thermalAdaptive)
                }
            } catch (exception: Throwable) {
                if (session != null) session.close()
                else {
                    input?.release()
                    stillInput?.release()
                    nativeClose(handle)
                }
                throw exception
            }
        }

        private fun validateDimensions(width: Int, height: Int, label: String) {
            require(width in 1..MAXIMUM_IMAGE_DIMENSION && height in 1..MAXIMUM_IMAGE_DIMENSION &&
                width.toLong() * height <= MAXIMUM_IMAGE_PIXELS
            ) { "$label dimensions exceed the supported 8192-side / 16MP memory budget" }
        }

        private fun materializeProfile(context: Context, name: String): File {
            require(name.isNotBlank() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name
            ) { "Profile asset must be a file name" }
            val directory = File(context.noBackupFilesDir, "phytoy/$PROFILE_CACHE_VERSION")
            check(directory.exists() || directory.mkdirs()) { "Unable to create profile cache" }
            val target = File(directory, name)
            // Parallel SDK clients cannot observe a half-written profile.
            val temporary = File.createTempFile("profile-", ".tmp", directory)
            try {
                context.assets.open("$PROFILE_ASSET_DIRECTORY/$name").use { source ->
                    temporary.outputStream().use(source::copyTo)
                }
                check(temporary.renameTo(target)) { "Unable to install profile cache" }
            } finally {
                temporary.delete()
            }
            return target
        }

        @JvmStatic private external fun nativeCreate(
            hostProfilePath: String,
            toyProfilePath: String,
            width: Int,
            height: Int,
            stillWidth: Int,
            stillHeight: Int,
            maxImages: Int,
            outputSurface: Surface,
            outputRotationDegrees: Int,
        ): Long
        @JvmStatic private external fun nativeInputSurface(handle: Long): Surface?
        @JvmStatic private external fun nativeStillCaptureSurface(handle: Long): Surface?
        @JvmStatic private external fun nativeSnapshot(handle: Long): LongArray
        @JvmStatic private external fun nativeCaptureNextFrame(
            handle: Long,
            timeoutMillis: Int,
        ): IntArray
        @JvmStatic private external fun nativeBeginStillCapture(handle: Long): Long
        @JvmStatic private external fun nativeAwaitCapture(
            handle: Long,
            requestId: Long,
            timeoutMillis: Int,
        ): IntArray
        @JvmStatic private external fun nativeCancelCapture(handle: Long)
        @JvmStatic private external fun nativeSetProcessingFrameRate(handle: Long, framesPerSecond: Int)
        @JvmStatic private external fun nativeLastError(handle: Long): String
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
