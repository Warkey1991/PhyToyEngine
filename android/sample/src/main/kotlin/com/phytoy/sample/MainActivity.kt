package com.phytoy.sample

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.phytoy.engine.PhyToyCameraSession
import java.util.Locale

class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var engineSession: PhyToyCameraSession? = null
    private var engineSize: Size? = null
    private var cameraFpsRanges: List<Range<Int>> = emptyList()
    @Volatile private var activeCameraFpsRange: Range<Int>? = null
    @Volatile private var pendingCameraFpsRange: Range<Int>? = null
    private var cameraOpening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createContentView()
        preview.surfaceTextureListener = this
    }

    override fun onResume() {
        super.onResume()
        cameraThread = HandlerThread("PhyToyCamera2").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        if (preview.isAvailable) ensurePermissionAndOpen()
        mainHandler.post(metricsUpdater)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(metricsUpdater)
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread?.join()
        cameraThread = null
        cameraHandler = null
        super.onPause()
    }

    private fun createContentView() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val overlay = FrameLayout(this).apply { setBackgroundColor(0x66000000) }
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            densityPixels(280),
            Gravity.BOTTOM,
        ))

        status = TextView(this).apply {
            setTextColor(0xFFF4B942.toInt())
            textSize = 17f
            text = getString(R.string.status_waiting_permission)
            setPadding(densityPixels(16), densityPixels(12), densityPixels(16), 0)
        }
        overlay.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            densityPixels(52),
        ))

        metrics = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(densityPixels(16), 0, densityPixels(16), densityPixels(12))
            text = getString(R.string.metrics_waiting)
        }
        overlay.addView(metrics, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { topMargin = densityPixels(52) })

        setContentView(root)
    }

    private fun densityPixels(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun ensurePermissionAndOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            status.text = getString(R.string.status_permission_required)
        }
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (cameraOpening || cameraDevice != null || !preview.isAvailable) return
        val handler = cameraHandler ?: return
        cameraOpening = true
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.first { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val characteristics = manager.getCameraCharacteristics(cameraId)
            cameraFpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ).orEmpty().toList()
            val map = checkNotNull(
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ) { "Camera has no stream configuration map" }
            val size = chooseEngineSize(map)
            engineSize = size
            val texture = checkNotNull(preview.surfaceTexture)
            val outputWidth = preview.width.coerceIn(1, PREVIEW_OUTPUT_WIDTH)
            val outputHeight = (
                preview.height.coerceAtLeast(1).toLong() * outputWidth /
                    preview.width.coerceAtLeast(1)
                ).toInt().coerceAtLeast(1)
            texture.setDefaultBufferSize(outputWidth, outputHeight)
            val processedPreviewSurface = Surface(texture)
            previewSurface = processedPreviewSurface
            val outputRotation = relativeCameraRotation(characteristics)
            engineSession = PhyToyCameraSession.open(
                context = this,
                width = size.width,
                height = size.height,
                outputSurface = processedPreviewSurface,
                outputRotationDegrees = outputRotation,
            )
            status.text = getString(R.string.status_opening, size.width, size.height)
            manager.openCamera(cameraId, cameraStateCallback, handler)
        } catch (exception: Throwable) {
            cameraOpening = false
            showFailure("Open failed", exception)
            engineSession?.close()
            engineSession = null
            previewSurface?.release()
            previewSurface = null
        }
    }

    private fun chooseEngineSize(map: StreamConfigurationMap): Size {
        val sizes = checkNotNull(map.getOutputSizes(android.graphics.ImageFormat.PRIVATE)) {
            "Camera has no PRIVATE output sizes"
        }
        return sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minBy { it.width.toLong() * it.height }
    }

    @Suppress("DEPRECATION")
    private fun relativeCameraRotation(characteristics: CameraCharacteristics): Int {
        val displayDegrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorDegrees = characteristics.get(
            CameraCharacteristics.SENSOR_ORIENTATION
        ) ?: 0
        return (sensorDegrees - displayDegrees + 360) % 360
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpening = false
            cameraDevice = camera
            createCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpening = false
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            runOnUiThread { status.text = getString(R.string.status_disconnected) }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpening = false
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            runOnUiThread { status.text = getString(R.string.status_camera_error, error) }
        }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val engineSurface = checkNotNull(engineSession?.inputSurface)
            camera.createCaptureSession(
                listOf(engineSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== camera) return
                        captureSession = session
                        submitRepeatingRequest(
                            camera,
                            session,
                            selectCameraFpsRange(engineSession?.snapshot()?.thermalStatus ?: -1),
                        )
                        runOnUiThread {
                            status.text = getString(R.string.status_active)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread { status.text = getString(R.string.status_stream_rejected) }
                    }
                },
                cameraHandler,
            )
        } catch (exception: Throwable) {
            showFailure("Session failed", exception)
        }
    }

    private fun selectCameraFpsRange(thermalStatus: Int): Range<Int>? {
        // The processed GPU Surface is now the only preview. Producing camera frames faster
        // than the engine can display wastes Camera HAL/ISP power without improving motion.
        val target = CAMERA_PREVIEW_FPS
        return cameraFpsRanges.firstOrNull { it.lower == target && it.upper == target }
            ?: cameraFpsRanges.filter { it.contains(target) }
                .minByOrNull { it.upper - it.lower }
            ?: cameraFpsRanges.minByOrNull { kotlin.math.abs(it.upper - target) }
    }

    private fun updateCameraFrameRate(thermalStatus: Int) {
        val desired = selectCameraFpsRange(thermalStatus) ?: return
        if (desired == activeCameraFpsRange || desired == pendingCameraFpsRange) return
        val handler = cameraHandler ?: return
        pendingCameraFpsRange = desired
        handler.post {
            val camera = cameraDevice
            val session = captureSession
            if (camera == null || session == null) {
                pendingCameraFpsRange = null
                return@post
            }
            try {
                submitRepeatingRequest(camera, session, desired)
            } catch (exception: Throwable) {
                showFailure("Camera FPS update failed", exception)
            } finally {
                pendingCameraFpsRange = null
            }
        }
    }

    private fun submitRepeatingRequest(
        camera: CameraDevice,
        session: CameraCaptureSession,
        fpsRange: Range<Int>?,
    ) {
        val engineSurface = checkNotNull(engineSession?.inputSurface)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(engineSurface)
            set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }.build()
        session.setRepeatingRequest(request, null, cameraHandler)
        activeCameraFpsRange = fpsRange
        Log.i(LOG_TAG, "Camera2 thermal FPS range changed to ${fpsRange ?: "device default"}")
    }

    private val metricsUpdater = object : Runnable {
        override fun run() {
            val engine = engineSession
            if (engine != null) {
                try {
                    val value = engine.snapshot()
                    updateCameraFrameRate(value.thermalStatus)
                    val latencyMs = value.lastLatencyUs / 1000.0
                    val verified = value.renderedFrames > 0 &&
                        value.errorFrames == 0L &&
                        value.zeroCopyFrames == value.renderedFrames &&
                        value.queueSubmissions == value.renderedFrames &&
                        value.presentedFrames == value.renderedFrames &&
                        value.inputImageFormat == android.graphics.ImageFormat.PRIVATE.toLong() &&
                        value.inputBufferUsage and GPU_SAMPLED_IMAGE_USAGE != 0L &&
                        value.hardwareBufferImports in 1L..MAXIMUM_BUFFER_IMPORTS &&
                        value.latencyP95Us <= PRODUCT_ALPHA_P95_US &&
                        (value.thermalStatus < 0 ||
                            value.thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE)
                    metrics.text = String.format(
                        Locale.US,
                        getString(R.string.metrics_format),
                        value.receivedFrames,
                        value.renderedFrames,
                        value.droppedFrames,
                        value.errorFrames,
                        value.throttledFrames,
                        value.targetProcessingFps,
                        value.thermalStatus,
                        activeCameraFpsRange?.let { "${it.lower}-${it.upper}" } ?: "-",
                        latencyMs,
                        value.maximumLatencyUs / 1000.0,
                        value.latencyP50Us / 1000.0,
                        value.latencyP95Us / 1000.0,
                        value.queueSubmissions,
                        value.hardwareBufferImports,
                        value.zeroCopyFrames,
                        value.presentedFrames,
                        value.swapchainRecreates,
                        value.outputWidth,
                        value.outputHeight,
                        value.inputImageFormat,
                        value.inputBufferFormat,
                        value.inputBufferUsage,
                        value.resourceAllocations,
                        value.allocatedBytes / (1024.0 * 1024.0),
                        getString(
                            if (verified) R.string.verification_yes else R.string.verification_waiting
                        ),
                    )
                    if (value.errorFrames > 0) {
                        status.text = getString(R.string.status_engine_error, engine.lastError())
                    }
                    if (value.renderedFrames > 0 && value.renderedFrames % 30L == 0L) {
                        Log.i(LOG_TAG, metrics.text.toString())
                    }
                } catch (exception: Throwable) {
                    showFailure("Metrics failed", exception)
                }
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    private fun closeCamera() {
        cameraOpening = false
        try {
            captureSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        engineSession?.close()
        engineSession = null
        previewSurface?.release()
        previewSurface = null
        engineSize = null
        cameraFpsRanges = emptyList()
        activeCameraFpsRange = null
        pendingCameraFpsRange = null
    }

    private fun showFailure(prefix: String, exception: Throwable) {
        Log.e(LOG_TAG, prefix, exception)
        runOnUiThread {
            status.text = getString(R.string.status_failure, prefix, exception.message.orEmpty())
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ensurePermissionAndOpen()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 901
        private const val LOG_TAG = "PhyToySample"
        private const val GPU_SAMPLED_IMAGE_USAGE = 0x100L
        private const val MAXIMUM_BUFFER_IMPORTS = 8L
        private const val PRODUCT_ALPHA_P95_US = 65_000L
        private const val PREVIEW_OUTPUT_WIDTH = 720
        private const val CAMERA_PREVIEW_FPS = 15
    }
}
