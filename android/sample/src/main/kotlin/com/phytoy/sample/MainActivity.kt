package com.phytoy.sample

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.MotionEvent
import android.view.Gravity
import android.widget.FrameLayout
import com.phytoy.engine.PhyToyCameraSession
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private class CameraPreviewTextureView(context: Context) : TextureView(context) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var preview: TextureView
    private lateinit var previewViewport: CaptureViewport
    private lateinit var chrome: CameraChrome
    private lateinit var photoStore: PhotoStore
    private lateinit var shutterSound: MediaActionSound
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())
    private val photoExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PhyToyPhoto").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var engineSession: PhyToyCameraSession? = null
    private var engineSize: Size? = null
    private var stillSize: Size? = null
    private var stillSizeCandidates: List<Size> = emptyList()
    private var stillSizeCandidateIndex = 0
    private var cameraFpsRanges: List<Range<Int>> = emptyList()
    private var availableAfModes: Set<Int> = emptySet()
    private var sensorActiveArray: Rect? = null
    private var maximumAfRegions = 0
    private var maximumAeRegions = 0
    private var autoexposureLockAvailable = false
    private var autoWhiteBalanceLockAvailable = false
    private var exposureCompensationRange = Range(0, 0)
    private var exposureCompensationStep = 0f
    @Volatile private var exposureCompensationIndex = 0
    private var maximumZoomRatio = 1f
    @Volatile private var currentZoomRatio = 1f
    @Volatile private var currentCropRegion: Rect? = null
    private var flashAvailable = false
    @Volatile private var currentFlashMode = CameraFlashMode.OFF
    private var fallbackFocalLengthMillimeters: Float? = null
    private var currentLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var availableLensFacings: Set<Int> = emptySet()
    private var currentCameraId: String? = null
    private var currentOutputRotation = 0
    @Volatile private var meteringRegion: MeteringRectangle? = null
    @Volatile private var touchFocusActive = false
    @Volatile private var focusGeneration = 0L
    @Volatile private var focusResolvedGeneration = -1L
    @Volatile private var touchFocusSucceeded = false
    @Volatile private var touchFocusLockedAtMillis = 0L
    @Volatile private var activeCameraFpsRange: Range<Int>? = null
    @Volatile private var pendingCameraFpsRange: Range<Int>? = null
    private var cameraOpening = false
    @Volatile private var cameraReady = false
    @Volatile private var captureInProgress = false
    private var pendingCaptureAfterStoragePermission = false
    private var lastPhotoUri: Uri? = null
    @Volatile private var cameraGeneration = 0L
    private var gestureStartY = 0f
    private var gestureStartX = 0f
    private var gestureStartExposureIndex = 0
    private var gestureAdjustedExposure = false
    private var gestureUsedPinch = false
    @Volatile private var cameraControlUpdatePending = false
    private val previewTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        photoStore = PhotoStore(applicationContext)
        shutterSound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
        createContentView()
        preview.surfaceTextureListener = this
        restoreLastThumbnail()
    }

    override fun onResume() {
        super.onResume()
        if (cameraThread == null) {
            cameraThread = HandlerThread("PhyToyCamera2").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
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

    override fun onDestroy() {
        shutterSound.release()
        photoExecutor.shutdown()
        super.onDestroy()
    }

    private fun createContentView() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewViewport = CaptureViewport(this)
        preview = CameraPreviewTextureView(this).apply { isOpaque = true }
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    gestureUsedPinch = true
                    clearTouchFocusForControlChange()
                    return cameraReady && !captureInProgress && maximumZoomRatio > 1f
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    setZoomRatio(currentZoomRatio * detector.scaleFactor)
                    return true
                }
            },
        )
        preview.setOnTouchListener { _, event -> handlePreviewTouch(event) }
        previewViewport.addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            previewViewport,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        )

        chrome = CameraChrome(this).apply {
            setReady(false)
            shutter.setOnClickListener { capturePhoto() }
            thumbnail.setOnClickListener { openLastPhoto() }
            switchCamera.setOnClickListener { switchCamera() }
            flashMode.setOnClickListener { cycleFlashMode() }
            setLensSwitchAvailable(false)
            setFlashAvailable(false)
            showMessage(getString(R.string.status_waiting_permission))
        }
        root.addView(
            chrome,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
    }

    private fun restoreLastThumbnail() {
        val uri = photoStore.lastPhotoUri() ?: return
        lastPhotoUri = uri
        photoExecutor.execute {
            val thumbnail = photoStore.loadThumbnail(uri) ?: return@execute
            mainHandler.post {
                if (isDestroyed) thumbnail.recycle() else chrome.showThumbnail(thumbnail)
            }
        }
    }

    private fun ensurePermissionAndOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            chrome.showMessage(getString(R.string.status_waiting_permission))
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                } else {
                    chrome.showMessage(getString(R.string.status_permission_required))
                }
            }

            STORAGE_PERMISSION_REQUEST -> {
                val shouldCapture = pendingCaptureAfterStoragePermission
                pendingCaptureAfterStoragePermission = false
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    if (shouldCapture) capturePhoto()
                } else {
                    chrome.showMessage(getString(R.string.storage_permission_required), true)
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (cameraOpening || cameraDevice != null || !preview.isAvailable) return
        val handler = cameraHandler ?: return
        cameraOpening = true
        cameraReady = false
        chrome.setReady(false)
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val candidates = manager.cameraIdList.map { id ->
                id to manager.getCameraCharacteristics(id)
            }
            availableLensFacings = candidates.mapNotNull {
                it.second.get(CameraCharacteristics.LENS_FACING)
            }.filter {
                it == CameraCharacteristics.LENS_FACING_BACK ||
                    it == CameraCharacteristics.LENS_FACING_FRONT
            }.toSet()
            val selected = candidates.firstOrNull {
                it.second.get(CameraCharacteristics.LENS_FACING) == currentLensFacing
            } ?: checkNotNull(candidates.firstOrNull()) { "No camera is available" }
            val cameraId = selected.first
            val characteristics = selected.second
            currentLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: currentLensFacing
            currentCameraId = cameraId
            chrome.setLensFacing(currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT)
            chrome.setLensSwitchAvailable(
                CameraCharacteristics.LENS_FACING_BACK in availableLensFacings &&
                    CameraCharacteristics.LENS_FACING_FRONT in availableLensFacings
            )
            preview.scaleX = if (
                currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT
            ) -1f else 1f
            cameraFpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ).orEmpty().toList()
            availableAfModes = characteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
            )?.toSet().orEmpty()
            sensorActiveArray = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )
            maximumAfRegions = characteristics.get(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AF
            ) ?: 0
            maximumAeRegions = characteristics.get(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AE
            ) ?: 0
            autoexposureLockAvailable = characteristics.get(
                CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE
            ) == true
            autoWhiteBalanceLockAvailable = characteristics.get(
                CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
            ) == true
            exposureCompensationRange = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
            ) ?: Range(0, 0)
            exposureCompensationStep = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
            )?.toFloat() ?: 0f
            exposureCompensationIndex = 0
            maximumZoomRatio = (characteristics.get(
                CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
            ) ?: 1f).coerceAtLeast(1f)
            currentZoomRatio = 1f
            currentCropRegion = sensorActiveArray?.let(::Rect)
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            currentFlashMode = CameraFlashMode.OFF
            fallbackFocalLengthMillimeters = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull()
            chrome.setExposureCompensation(
                0f,
                exposureCompensationRange.lower != exposureCompensationRange.upper,
            )
            chrome.setZoomRatio(currentZoomRatio)
            chrome.setFlashAvailable(flashAvailable)
            chrome.setFlashMode(currentFlashMode)
            val map = checkNotNull(
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ) { "Camera has no stream configuration map" }
            stillSizeCandidates = chooseStillSizeCandidates(map)
            stillSizeCandidateIndex = 0
            val requestedCaptureSize = stillSizeCandidates.first()
            val size = chooseEngineSize(map, requestedCaptureSize)
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
            val outputRotation = relativeCameraRotation(characteristics, currentLensFacing)
            currentOutputRotation = outputRotation
            openEngineWithAvailableStillSize(size)
            val captureSize = checkNotNull(stillSize)
            chrome.setCaptureSize(captureSize.width, captureSize.height)
            val generation = ++cameraGeneration
            chrome.showMessage(
                getString(
                    R.string.status_opening,
                    getString(
                        if (currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                            R.string.front_camera
                        } else {
                            R.string.back_camera
                        }
                    ),
                    size.width,
                    size.height,
                    captureSize.width,
                    captureSize.height,
                )
            )
            manager.openCamera(cameraId, cameraStateCallback(generation), handler)
        } catch (exception: Throwable) {
            cameraOpening = false
            showFailure("Open failed", exception)
            engineSession?.close()
            engineSession = null
            previewSurface?.release()
            previewSurface = null
        }
    }

    private fun chooseEngineSize(map: StreamConfigurationMap, captureSize: Size): Size {
        val sizes = checkNotNull(map.getOutputSizes(android.graphics.ImageFormat.PRIVATE)) {
            "Camera has no PRIVATE output sizes"
        }
        val captureAspect = captureSize.width.toDouble() / captureSize.height
        val bounded = sizes.filter {
            it.width <= MAXIMUM_PREVIEW_WIDTH &&
                it.height <= MAXIMUM_PREVIEW_HEIGHT &&
                it.width.toLong() * it.height <= MAXIMUM_PREVIEW_PIXELS
        }
        val aspectMatched = bounded.filter {
            kotlin.math.abs(it.width.toDouble() / it.height - captureAspect) <=
                MAXIMUM_PREVIEW_ASPECT_ERROR
        }
        return aspectMatched.firstOrNull {
            it.width == TARGET_PREVIEW_WIDTH && it.height == TARGET_PREVIEW_HEIGHT
        }
            ?: aspectMatched.minByOrNull {
                kotlin.math.abs(
                    it.width.toLong() * it.height -
                        TARGET_PREVIEW_WIDTH.toLong() * TARGET_PREVIEW_HEIGHT
                )
            }
            ?: bounded.minWithOrNull(
                compareBy<Size> {
                    kotlin.math.abs(it.width.toDouble() / it.height - captureAspect)
                }.thenByDescending { it.width.toLong() * it.height }
            )
            ?: sizes.minBy { it.width.toLong() * it.height }
    }

    private fun chooseStillSizeCandidates(map: StreamConfigurationMap): List<Size> {
        val sizes = checkNotNull(map.getOutputSizes(android.graphics.ImageFormat.PRIVATE)) {
            "Camera has no PRIVATE still output sizes"
        }
        val bounded = sizes.filter {
            it.width.toLong() * it.height <= MAXIMUM_STILL_PIXELS
        }.ifEmpty { listOf(sizes.minBy { it.width.toLong() * it.height }) }
            .sortedByDescending { it.width.toLong() * it.height }
        val preferred = bounded.first()
        val preferredAspect = preferred.width.toDouble() / preferred.height
        val aspectMatched = bounded.filter {
            abs(it.width.toDouble() / it.height - preferredAspect) <=
                MAXIMUM_PREVIEW_ASPECT_ERROR
        }.ifEmpty { bounded }
        val maximumArea = preferred.width.toLong() * preferred.height
        val candidates = linkedSetOf<Size>()
        STILL_FALLBACK_AREA_RATIOS.forEach { ratio ->
            aspectMatched.firstOrNull {
                it.width.toLong() * it.height <= maximumArea * ratio
            }?.let(candidates::add)
        }
        candidates += aspectMatched.last()
        return candidates.toList()
    }

    private fun openEngineWithAvailableStillSize(size: Size) {
        var lastFailure: Throwable? = null
        while (stillSizeCandidateIndex < stillSizeCandidates.size) {
            val captureSize = stillSizeCandidates[stillSizeCandidateIndex]
            try {
                engineSession = openEngineSession(size, captureSize)
                stillSize = captureSize
                return
            } catch (exception: Throwable) {
                lastFailure = exception
                Log.w(
                    LOG_TAG,
                    "Engine rejected PRIVATE still ${captureSize.width}x${captureSize.height}; " +
                        "trying a smaller stream",
                    exception,
                )
                stillSizeCandidateIndex += 1
            }
        }
        throw IllegalStateException("No compatible PRIVATE still stream", lastFailure)
    }

    private fun openEngineSession(size: Size, captureSize: Size): PhyToyCameraSession =
        PhyToyCameraSession.open(
            context = this,
            width = size.width,
            height = size.height,
            stillWidth = captureSize.width,
            stillHeight = captureSize.height,
            outputSurface = checkNotNull(previewSurface),
            outputRotationDegrees = currentOutputRotation,
        )

    @Suppress("DEPRECATION")
    private fun relativeCameraRotation(
        characteristics: CameraCharacteristics,
        lensFacing: Int,
    ): Int {
        val displayDegrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorDegrees + displayDegrees) % 360
        } else {
            (sensorDegrees - displayDegrees + 360) % 360
        }
    }

    private fun cameraStateCallback(generation: Long) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (generation != cameraGeneration) {
                camera.close()
                return
            }
            cameraOpening = false
            cameraDevice = camera
            createCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            if (generation != cameraGeneration) {
                camera.close()
                return
            }
            cameraOpening = false
            cameraReady = false
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            runOnUiThread {
                chrome.setReady(false)
                chrome.showMessage(getString(R.string.status_disconnected))
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            if (generation != cameraGeneration) {
                camera.close()
                return
            }
            cameraOpening = false
            cameraReady = false
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            runOnUiThread {
                chrome.setReady(false)
                chrome.showMessage(getString(R.string.status_camera_error, error))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val engineSurface = checkNotNull(engineSession?.inputSurface)
            val stillSurface = checkNotNull(engineSession?.stillCaptureSurface)
            camera.createCaptureSession(
                listOf(engineSurface, stillSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== camera) {
                            session.close()
                            return
                        }
                        captureSession = session
                        submitRepeatingRequest(
                            camera,
                            session,
                            selectCameraFpsRange(),
                        )
                        cameraReady = true
                        runOnUiThread {
                            chrome.setReady(true)
                            chrome.showMessage(
                                getString(
                                    R.string.status_active_high_res,
                                    checkNotNull(stillSize).width,
                                    checkNotNull(stillSize).height,
                                ),
                                true,
                            )
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cameraReady = false
                        session.close()
                        if (cameraDevice !== camera || !configureNextStillSize(camera)) {
                            runOnUiThread {
                                chrome.setReady(false)
                                chrome.showMessage(getString(R.string.status_stream_rejected))
                            }
                        }
                    }
                },
                cameraHandler,
            )
        } catch (exception: Throwable) {
            showFailure("Session failed", exception)
        }
    }

    private fun configureNextStillSize(camera: CameraDevice): Boolean {
        val size = engineSize ?: return false
        engineSession?.close()
        engineSession = null
        while (++stillSizeCandidateIndex < stillSizeCandidates.size) {
            val captureSize = stillSizeCandidates[stillSizeCandidateIndex]
            try {
                engineSession = openEngineSession(size, captureSize)
                stillSize = captureSize
                Log.w(
                    LOG_TAG,
                    "Camera2 dual PRIVATE stream fallback to " +
                        "${captureSize.width}x${captureSize.height}",
                )
                runOnUiThread {
                    chrome.setCaptureSize(captureSize.width, captureSize.height)
                    chrome.showMessage(
                        getString(
                            R.string.status_stream_fallback,
                            captureSize.width,
                            captureSize.height,
                        ),
                        true,
                    )
                }
                createCaptureSession(camera)
                return true
            } catch (exception: Throwable) {
                Log.w(
                    LOG_TAG,
                    "Fallback PRIVATE still stream rejected by engine: " +
                        "${captureSize.width}x${captureSize.height}",
                    exception,
                )
                engineSession?.close()
                engineSession = null
            }
        }
        return false
    }

    private fun selectCameraFpsRange(): Range<Int>? {
        val target = CAMERA_PREVIEW_FPS
        return cameraFpsRanges.firstOrNull { it.lower == target && it.upper == target }
            ?: cameraFpsRanges.filter { it.contains(target) }
                .minByOrNull { it.upper - it.lower }
            ?: cameraFpsRanges.minByOrNull { kotlin.math.abs(it.upper - target) }
    }

    private fun updateCameraFrameRate() {
        val desired = selectCameraFpsRange() ?: return
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
        val focusToken = focusGeneration
        val request = buildPreviewRequest(camera, fpsRange).build()
        session.setRepeatingRequest(
            request,
            if (touchFocusActive) focusStateCallback(focusToken) else null,
            cameraHandler,
        )
        activeCameraFpsRange = fpsRange
        Log.i(LOG_TAG, "Camera2 FPS range changed to ${fpsRange ?: "device default"}")
    }

    private fun buildPreviewRequest(
        camera: CameraDevice,
        fpsRange: Range<Int>?,
    ): CaptureRequest.Builder {
        val engineSurface = checkNotNull(engineSession?.inputSurface)
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(engineSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, selectedAfMode())
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            set(CaptureRequest.CONTROL_AE_MODE, selectedAutoexposureMode())
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            if (autoexposureLockAvailable) {
                set(CaptureRequest.CONTROL_AE_LOCK, false)
            }
            if (autoWhiteBalanceLockAvailable) {
                set(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            applyExposureAndZoom(this)
            applyMeteringRegion(this)
        }
    }

    private fun selectedAutoexposureMode(): Int = when {
        !flashAvailable || currentFlashMode == CameraFlashMode.OFF -> {
            CaptureRequest.CONTROL_AE_MODE_ON
        }
        currentFlashMode == CameraFlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        else -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
    }

    private fun applyExposureAndZoom(builder: CaptureRequest.Builder) {
        if (exposureCompensationRange.lower != exposureCompensationRange.upper) {
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                exposureCompensationIndex.coerceIn(
                    exposureCompensationRange.lower,
                    exposureCompensationRange.upper,
                ),
            )
        }
        currentCropRegion?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
    }

    private fun handlePreviewTouch(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = event.x
                gestureStartY = event.y
                gestureStartExposureIndex = exposureCompensationIndex
                gestureAdjustedExposure = false
                gestureUsedPinch = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> gestureUsedPinch = true

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 &&
                    !scaleGestureDetector.isInProgress &&
                    exposureCompensationStep > 0f &&
                    exposureCompensationRange.lower != exposureCompensationRange.upper
                ) {
                    val verticalDistance = gestureStartY - event.y
                    if (abs(verticalDistance) > previewTouchSlop) {
                        if (!gestureAdjustedExposure) clearTouchFocusForControlChange()
                        gestureAdjustedExposure = true
                        val pixelsPerStep = EV_GESTURE_STEP_DP * resources.displayMetrics.density
                        setExposureCompensationIndex(
                            gestureStartExposureIndex +
                                (verticalDistance / pixelsPerStep).roundToInt(),
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val tap = !gestureUsedPinch &&
                    !gestureAdjustedExposure &&
                    abs(event.x - gestureStartX) <= previewTouchSlop &&
                    abs(event.y - gestureStartY) <= previewTouchSlop
                if (tap) {
                    preview.performClick()
                    focusAt(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun setExposureCompensationIndex(index: Int) {
        val clamped = index.coerceIn(
            exposureCompensationRange.lower,
            exposureCompensationRange.upper,
        )
        if (clamped == exposureCompensationIndex) return
        exposureCompensationIndex = clamped
        val ev = clamped * exposureCompensationStep
        chrome.setExposureCompensation(ev, true)
        requestCameraControlUpdate()
        Log.i(LOG_TAG, "Camera2 exposure compensation changed index=$clamped ev=$ev")
    }

    private fun setZoomRatio(ratio: Float) {
        val clamped = ratio.coerceIn(1f, maximumZoomRatio)
        if (abs(clamped - currentZoomRatio) < MINIMUM_ZOOM_CHANGE) return
        currentZoomRatio = clamped
        currentCropRegion = sensorActiveArray?.let { activeArray ->
            val width = (activeArray.width() / clamped).roundToInt().coerceAtLeast(2)
            val height = (activeArray.height() / clamped).roundToInt().coerceAtLeast(2)
            val left = activeArray.left + (activeArray.width() - width) / 2
            val top = activeArray.top + (activeArray.height() - height) / 2
            Rect(left, top, left + width, top + height)
        }
        chrome.setZoomRatio(clamped)
        requestCameraControlUpdate()
    }

    private fun cycleFlashMode() {
        if (!flashAvailable || !cameraReady || captureInProgress) return
        currentFlashMode = when (currentFlashMode) {
            CameraFlashMode.OFF -> CameraFlashMode.AUTO
            CameraFlashMode.AUTO -> CameraFlashMode.ON
            CameraFlashMode.ON -> CameraFlashMode.OFF
        }
        clearTouchFocusForControlChange()
        chrome.setFlashMode(currentFlashMode)
        requestCameraControlUpdate()
        Log.i(LOG_TAG, "Camera2 flash mode changed to $currentFlashMode")
    }

    private fun clearTouchFocusForControlChange() {
        focusGeneration += 1L
        focusResolvedGeneration = -1L
        touchFocusActive = false
        meteringRegion = null
        touchFocusSucceeded = false
        touchFocusLockedAtMillis = 0L
        chrome.clearFocusIndicator()
    }

    private fun requestCameraControlUpdate() {
        if (cameraControlUpdatePending) return
        val handler = cameraHandler ?: return
        cameraControlUpdatePending = true
        handler.postDelayed({
            cameraControlUpdatePending = false
            val camera = cameraDevice ?: return@postDelayed
            val session = captureSession ?: return@postDelayed
            if (!cameraReady || captureInProgress) return@postDelayed
            try {
                submitRepeatingRequest(camera, session, selectCameraFpsRange())
            } catch (exception: Throwable) {
                showFailure("Camera control update failed", exception)
            }
        }, CAMERA_CONTROL_UPDATE_DELAY_MILLIS)
    }

    private fun selectedAfMode(): Int = when {
        touchFocusActive && CaptureRequest.CONTROL_AF_MODE_AUTO in availableAfModes -> {
            CaptureRequest.CONTROL_AF_MODE_AUTO
        }
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in availableAfModes -> {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        CaptureRequest.CONTROL_AF_MODE_AUTO in availableAfModes -> {
            CaptureRequest.CONTROL_AF_MODE_AUTO
        }
        else -> CaptureRequest.CONTROL_AF_MODE_OFF
    }

    private fun applyMeteringRegion(builder: CaptureRequest.Builder) {
        val region = meteringRegion ?: return
        if (maximumAfRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        }
        if (maximumAeRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private fun focusAt(viewX: Float, viewY: Float) {
        if (!cameraReady || captureInProgress) return
        val activeArray = currentCropRegion ?: sensorActiveArray ?: return
        val previewSize = engineSize ?: return
        val viewWidth = preview.width.takeIf { it > 0 } ?: return
        val viewHeight = preview.height.takeIf { it > 0 } ?: return
        val source = mapPreviewPointToSensor(
            viewX = viewX,
            viewY = viewY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            sourceWidth = previewSize.width,
            sourceHeight = previewSize.height,
            rotationDegrees = currentOutputRotation,
            mirrored = currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT,
        )
        val sensorX = activeArray.left + (source.first * activeArray.width()).toInt()
        val sensorY = activeArray.top + (source.second * activeArray.height()).toInt()
        val side = (minOf(activeArray.width(), activeArray.height()) * METERING_REGION_FRACTION)
            .toInt().coerceAtLeast(1)
        val left = (sensorX - side / 2).coerceIn(activeArray.left, activeArray.right - side)
        val top = (sensorY - side / 2).coerceIn(activeArray.top, activeArray.bottom - side)
        val region = MeteringRectangle(
            Rect(left, top, left + side, top + side),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
        val previewLocation = IntArray(2)
        val chromeLocation = IntArray(2)
        preview.getLocationOnScreen(previewLocation)
        chrome.getLocationOnScreen(chromeLocation)
        chrome.showFocusIndicator(
            previewLocation[0] - chromeLocation[0] + viewX,
            previewLocation[1] - chromeLocation[1] + viewY,
        )
        val handler = cameraHandler ?: return
        handler.post {
            val camera = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val token = ++focusGeneration
            focusResolvedGeneration = -1L
            touchFocusSucceeded = false
            touchFocusLockedAtMillis = 0L
            meteringRegion = region
            touchFocusActive = true
            try {
                val builder = buildPreviewRequest(camera, selectCameraFpsRange())
                if (CaptureRequest.CONTROL_AF_MODE_AUTO in availableAfModes) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    builder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_START,
                    )
                }
                session.capture(builder.build(), null, handler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), focusStateCallback(token), handler)
                handler.postDelayed({ resetTouchFocus(token) }, TOUCH_FOCUS_HOLD_MILLIS)
                Log.i(
                    LOG_TAG,
                    "Touch AF/AE requested camera=$currentCameraId sensor=($sensorX,$sensorY) " +
                        "region=${region.rect}",
                )
            } catch (exception: Throwable) {
                touchFocusActive = false
                meteringRegion = null
                showFailure("Touch focus failed", exception)
            }
        }
    }

    private fun mapPreviewPointToSensor(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrored: Boolean,
    ): Pair<Float, Float> {
        var outputU = (viewX / viewWidth).coerceIn(0f, 1f)
        val outputV = (viewY / viewHeight).coerceIn(0f, 1f)
        if (mirrored) outputU = 1f - outputU
        val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
        val rotatedWidth = if (quarterTurn) sourceHeight else sourceWidth
        val rotatedHeight = if (quarterTurn) sourceWidth else sourceHeight
        val sourceAspect = rotatedWidth.toFloat() / rotatedHeight
        val outputAspect = viewWidth.toFloat() / viewHeight
        var rotatedU = outputU
        var rotatedV = outputV
        if (sourceAspect > outputAspect) {
            rotatedU = (rotatedU - 0.5f) * outputAspect / sourceAspect + 0.5f
        } else {
            rotatedV = (rotatedV - 0.5f) * sourceAspect / outputAspect + 0.5f
        }
        val mapped = when (rotationDegrees) {
            90 -> rotatedV to 1f - rotatedU
            180 -> 1f - rotatedU to 1f - rotatedV
            270 -> 1f - rotatedV to rotatedU
            else -> rotatedU to rotatedV
        }
        return mapped.first.coerceIn(0f, 1f) to mapped.second.coerceIn(0f, 1f)
    }

    private fun focusStateCallback(token: Long) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (token != focusGeneration || focusResolvedGeneration == token) return
                when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                        focusResolvedGeneration = token
                        touchFocusSucceeded = true
                        touchFocusLockedAtMillis = SystemClock.elapsedRealtime()
                        runOnUiThread { chrome.completeFocus(true) }
                        Log.i(LOG_TAG, "Touch AF locked")
                    }
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        focusResolvedGeneration = token
                        touchFocusSucceeded = false
                        touchFocusLockedAtMillis = 0L
                        runOnUiThread { chrome.completeFocus(false) }
                        Log.i(LOG_TAG, "Touch AF completed without lock")
                    }
                }
            }
        }

    private fun resetTouchFocus(token: Long) {
        if (token != focusGeneration || captureInProgress) return
        touchFocusActive = false
        meteringRegion = null
        touchFocusSucceeded = false
        touchFocusLockedAtMillis = 0L
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            submitRepeatingRequest(camera, session, selectCameraFpsRange())
            runOnUiThread { chrome.clearFocusIndicator() }
        } catch (exception: Throwable) {
            showFailure("Focus reset failed", exception)
        }
    }

    @Suppress("DEPRECATION")
    private fun capturePhoto() {
        if (!cameraReady || captureInProgress) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingCaptureAfterStoragePermission = true
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST,
            )
            return
        }

        val engine = engineSession ?: return
        val camera = cameraDevice ?: return
        val cameraSession = captureSession ?: return
        val generation = cameraGeneration
        captureInProgress = true
        chrome.setCapturing()
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        photoExecutor.execute {
            try {
                val metadataCollector = StillCaptureMetadataCollector(captureMetadataSnapshot())
                val frame = try {
                    val threeA = awaitCapture3A(camera, cameraSession, generation)
                    check(threeA.canCapture) {
                        threeA.failureReason.ifBlank { "Camera changed before still capture" }
                    }
                    mainHandler.post {
                        if (captureInProgress && generation == cameraGeneration) {
                            chrome.updateCaptureMessage(
                                getString(R.string.capturing_high_resolution)
                            )
                        }
                    }
                    engine.captureStillFrame(CAPTURE_TIMEOUT_MILLIS) {
                        submitStillCapture(
                            camera,
                            cameraSession,
                            generation,
                            lock3A = threeA.status == Camera3AController.Status.READY,
                            metadataCollector = metadataCollector,
                        )
                    }
                } finally {
                    restorePreviewAfterCapture(camera, cameraSession, generation)
                }
                mainHandler.post {
                    if (captureInProgress && generation == cameraGeneration) {
                        chrome.updateCaptureMessage(getString(R.string.processing_photo))
                    }
                }
                val metadata = metadataCollector.await(STILL_RESULT_TIMEOUT_MILLIS)
                val saved = photoStore.save(frame, metadata)
                val thumbnail = photoStore.loadThumbnail(saved.uri)
                mainHandler.post {
                    if (isDestroyed) {
                        thumbnail?.recycle()
                        return@post
                    }
                    lastPhotoUri = saved.uri
                    thumbnail?.let(chrome::showThumbnail)
                    chrome.flashCapture()
                    chrome.finishCapture()
                    captureInProgress = false
                    if (cameraReady && generation == cameraGeneration) chrome.setReady(true)
                    chrome.showMessage(
                        getString(
                            R.string.photo_saved,
                            saved.displayName,
                            saved.width,
                            saved.height,
                        ),
                        true,
                    )
                    Log.i(LOG_TAG, "Digital 01 photo saved: ${saved.uri}")
                }
            } catch (exception: Throwable) {
                Log.e(LOG_TAG, "Still capture failed", exception)
                mainHandler.post {
                    chrome.finishCapture()
                    captureInProgress = false
                    if (generation != cameraGeneration || isDestroyed) return@post
                    if (cameraReady && generation == cameraGeneration) chrome.setReady(true)
                    chrome.showMessage(
                        getString(R.string.photo_failed, exception.message.orEmpty()),
                        true,
                    )
                }
            }
        }
    }

    private fun awaitCapture3A(
        camera: CameraDevice,
        session: CameraCaptureSession,
        generation: Long,
    ): Camera3AController.Outcome {
        val handler = checkNotNull(cameraHandler) { "Camera thread is unavailable" }
        val touchFocusAgeMillis = SystemClock.elapsedRealtime() - touchFocusLockedAtMillis
        val reuseTouchFocus = touchFocusActive &&
            focusResolvedGeneration == focusGeneration &&
            touchFocusSucceeded &&
            touchFocusAgeMillis in 0L..RECENT_TOUCH_FOCUS_MILLIS
        if (reuseTouchFocus) {
            Log.i(LOG_TAG, "Camera2 3A reusing touch AF lock age_ms=$touchFocusAgeMillis")
        }
        val controller = Camera3AController(
            camera = camera,
            session = session,
            cameraHandler = handler,
            previewRequest = { buildPreviewRequest(camera, selectCameraFpsRange()) },
            triggerAutofocus =
                selectedAfMode() != CaptureRequest.CONTROL_AF_MODE_OFF && !reuseTouchFocus,
            autoexposureLockAvailable = autoexposureLockAvailable,
            autoWhiteBalanceLockAvailable = autoWhiteBalanceLockAvailable,
            isCameraCurrent = {
                generation == cameraGeneration &&
                    cameraDevice === camera &&
                    captureSession === session
            },
            stageListener = {
                    stage,
                    autofocusState,
                    autoexposureState,
                    autoWhiteBalanceState,
                ->
                Log.i(
                    LOG_TAG,
                    "Camera2 3A stage=$stage af=${autofocusState ?: "unknown"} " +
                        "ae=${autoexposureState ?: "unknown"} " +
                        "awb=${autoWhiteBalanceState ?: "unknown"}",
                )
                if (stage == Camera3AController.Stage.WAITING_AE_PRECAPTURE ||
                    stage == Camera3AController.Stage.WAITING_AE_CONVERGED
                ) {
                    mainHandler.post {
                        if (captureInProgress && generation == cameraGeneration) {
                            chrome.updateCaptureMessage(getString(R.string.metering_exposure))
                        }
                    }
                }
            },
        )
        val outcome = controller.await(THREE_A_TIMEOUT_MILLIS)
        Log.i(
            LOG_TAG,
            "Camera2 3A gate status=${outcome.status} " +
                "af=${outcome.autofocusState ?: "unknown"} " +
                "ae=${outcome.autoexposureState ?: "unknown"} " +
                "awb=${outcome.autoWhiteBalanceState ?: "unknown"} " +
                "elapsed_ms=${outcome.elapsedMillis}" +
                outcome.failureReason.takeIf { it.isNotBlank() }
                    .orEmpty().let { if (it.isEmpty()) "" else " reason=$it" },
        )
        return outcome
    }

    private fun restorePreviewAfterCapture(
        camera: CameraDevice,
        session: CameraCaptureSession,
        generation: Long,
    ) {
        val handler = cameraHandler ?: return
        handler.post {
            if (generation != cameraGeneration ||
                cameraDevice !== camera ||
                captureSession !== session
            ) return@post
            try {
                focusGeneration += 1L
                focusResolvedGeneration = -1L
                touchFocusActive = false
                meteringRegion = null
                touchFocusSucceeded = false
                touchFocusLockedAtMillis = 0L
                val cancelRequest = buildPreviewRequest(camera, selectCameraFpsRange()).apply {
                    set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL,
                    )
                    set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL,
                    )
                }
                session.capture(cancelRequest.build(), null, handler)
                submitRepeatingRequest(camera, session, selectCameraFpsRange())
                runOnUiThread { chrome.clearFocusIndicator() }
                Log.i(LOG_TAG, "Camera2 3A restored continuous preview")
            } catch (exception: Throwable) {
                Log.e(LOG_TAG, "Camera2 3A preview restore failed", exception)
            }
        }
    }

    private fun submitStillCapture(
        camera: CameraDevice,
        session: CameraCaptureSession,
        generation: Long,
        lock3A: Boolean,
        metadataCollector: StillCaptureMetadataCollector,
    ) {
        check(generation == cameraGeneration && cameraDevice === camera) {
            "Camera changed before still capture"
        }
        val stillSurface = checkNotNull(engineSession?.stillCaptureSurface)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(stillSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            set(CaptureRequest.CONTROL_AF_MODE, selectedAfMode())
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            set(CaptureRequest.CONTROL_AE_MODE, selectedAutoexposureMode())
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            if (autoexposureLockAvailable) {
                set(CaptureRequest.CONTROL_AE_LOCK, lock3A)
            }
            if (autoWhiteBalanceLockAvailable) {
                set(CaptureRequest.CONTROL_AWB_LOCK, lock3A)
            }
            applyExposureAndZoom(this)
            applyMeteringRegion(this)
        }.build()
        session.capture(
            request,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    metadataCollector.complete(result)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    metadataCollector.fail()
                    Log.w(LOG_TAG, "Still CaptureResult failed reason=${failure.reason}")
                }
            },
            cameraHandler,
        )
        Log.i(
            LOG_TAG,
            "Camera2 high-resolution PRIVATE capture submitted: " +
                "${stillSize?.width}x${stillSize?.height} lens=$currentLensFacing",
        )
    }

    private fun captureMetadataSnapshot(): CaptureMetadata = CaptureMetadata(
        iso = null,
        exposureTimeNanos = null,
        focalLengthMillimeters = fallbackFocalLengthMillimeters,
        lensFacing = if (currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            "FRONT"
        } else {
            "BACK"
        },
        digital01Version = DIGITAL_01_VERSION,
        capturedAtMillis = System.currentTimeMillis(),
        zoomRatio = currentZoomRatio,
        exposureCompensation = exposureCompensationIndex * exposureCompensationStep,
        flashMode = currentFlashMode,
    )

    private fun openLastPhoto() {
        val uri = lastPhotoUri
        if (uri == null) {
            chrome.showMessage(getString(R.string.no_photo_yet), true)
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: ActivityNotFoundException) {
            chrome.showMessage(getString(R.string.no_gallery_app), true)
        }
    }

    private fun switchCamera() {
        if (captureInProgress || cameraOpening) return
        val target = if (currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        if (target !in availableLensFacings) {
            chrome.showMessage(getString(R.string.camera_switch_unavailable), true)
            return
        }
        cameraReady = false
        chrome.setReady(false)
        chrome.showMessage(getString(R.string.status_switching_camera))
        closeCamera()
        currentLensFacing = target
        preview.scaleX = if (target == CameraCharacteristics.LENS_FACING_FRONT) -1f else 1f
        openCamera()
    }

    private val metricsUpdater = object : Runnable {
        override fun run() {
            val engine = engineSession
            if (engine != null) {
                try {
                    val value = engine.snapshot()
                    updateCameraFrameRate()
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
                    chrome.metrics.text = String.format(
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
                            if (verified) R.string.verification_yes
                            else R.string.verification_waiting
                        ),
                    )
                    if (value.errorFrames > 0 && !captureInProgress) {
                        chrome.showMessage(
                            getString(R.string.status_engine_error, engine.lastError())
                        )
                    }
                    if (value.renderedFrames > 0 && value.renderedFrames % 30L == 0L) {
                        Log.i(LOG_TAG, chrome.metrics.text.toString())
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
        cameraReady = false
        cameraGeneration += 1L
        focusGeneration += 1L
        focusResolvedGeneration = -1L
        touchFocusActive = false
        meteringRegion = null
        touchFocusSucceeded = false
        touchFocusLockedAtMillis = 0L
        chrome.setReady(false)
        chrome.clearFocusIndicator()
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
        stillSize = null
        stillSizeCandidates = emptyList()
        stillSizeCandidateIndex = 0
        cameraFpsRanges = emptyList()
        availableAfModes = emptySet()
        sensorActiveArray = null
        maximumAfRegions = 0
        maximumAeRegions = 0
        autoexposureLockAvailable = false
        autoWhiteBalanceLockAvailable = false
        currentCameraId = null
        activeCameraFpsRange = null
        pendingCameraFpsRange = null
        cameraControlUpdatePending = false
    }

    private fun showFailure(prefix: String, exception: Throwable) {
        Log.e(LOG_TAG, prefix, exception)
        runOnUiThread {
            chrome.setReady(false)
            chrome.showMessage(
                getString(R.string.status_failure, prefix, exception.message.orEmpty())
            )
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ensurePermissionAndOpen()
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private class StillCaptureMetadataCollector(fallback: CaptureMetadata) {
        private val latch = CountDownLatch(1)
        @Volatile private var metadata = fallback

        fun complete(result: TotalCaptureResult) {
            metadata = metadata.copy(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: metadata.iso,
                exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    ?: metadata.exposureTimeNanos,
                focalLengthMillimeters = result.get(CaptureResult.LENS_FOCAL_LENGTH)
                    ?: metadata.focalLengthMillimeters,
                capturedAtMillis = System.currentTimeMillis(),
            )
            latch.countDown()
        }

        fun fail() {
            latch.countDown()
        }

        fun await(timeoutMillis: Long): CaptureMetadata {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            return metadata
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 901
        private const val STORAGE_PERMISSION_REQUEST = 902
        private const val LOG_TAG = "PhyToySample"
        private const val GPU_SAMPLED_IMAGE_USAGE = 0x100L
        private const val MAXIMUM_BUFFER_IMPORTS = 16L
        private const val PRODUCT_ALPHA_P95_US = 65_000L
        private const val PREVIEW_OUTPUT_WIDTH = 720
        private const val TARGET_PREVIEW_WIDTH = 1_280
        private const val TARGET_PREVIEW_HEIGHT = 960
        private const val MAXIMUM_PREVIEW_WIDTH = 1_600
        private const val MAXIMUM_PREVIEW_HEIGHT = 1_200
        private const val MAXIMUM_PREVIEW_PIXELS = 1_600_000L
        private const val MAXIMUM_PREVIEW_ASPECT_ERROR = 0.015
        private const val CAMERA_PREVIEW_FPS = 15
        private const val CAPTURE_TIMEOUT_MILLIS = 10_000
        private const val STILL_RESULT_TIMEOUT_MILLIS = 1_500L
        private const val THREE_A_TIMEOUT_MILLIS = 3_000L
        private const val MAXIMUM_STILL_PIXELS = 12_500_000L
        private const val METERING_REGION_FRACTION = 0.12f
        private const val TOUCH_FOCUS_HOLD_MILLIS = 5_000L
        private const val RECENT_TOUCH_FOCUS_MILLIS = 5_000L
        private const val EV_GESTURE_STEP_DP = 32f
        private const val MINIMUM_ZOOM_CHANGE = 0.005f
        private const val CAMERA_CONTROL_UPDATE_DELAY_MILLIS = 24L
        private const val DIGITAL_01_VERSION = "1.2.0"
        private val STILL_FALLBACK_AREA_RATIOS = doubleArrayOf(1.0, 0.75, 0.5, 0.33, 0.2)
    }
}
