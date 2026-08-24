package com.phytoy.sample

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates one bounded Camera2 autofocus/autoexposure gate before a still request. */
internal class Camera3AController(
    private val camera: CameraDevice,
    private val session: CameraCaptureSession,
    private val cameraHandler: Handler,
    private val previewRequest: () -> CaptureRequest.Builder,
    private val triggerAutofocus: Boolean,
    private val autoexposureLockAvailable: Boolean,
    private val autoWhiteBalanceLockAvailable: Boolean,
    private val isCameraCurrent: () -> Boolean,
    private val stageListener: (Stage, Int?, Int?, Int?) -> Unit = { _, _, _, _ -> },
) {
    enum class Stage {
        STARTING,
        WAITING_AF,
        WAITING_AE,
        WAITING_AE_PRECAPTURE,
        WAITING_AE_CONVERGED,
        WAITING_AWB,
        LOCKING_3A,
        COMPLETE,
    }

    enum class Status {
        READY,
        TIMED_OUT,
        FAILED,
        CANCELLED,
    }

    data class Outcome(
        val status: Status,
        val autofocusState: Int?,
        val autoexposureState: Int?,
        val autoWhiteBalanceState: Int?,
        val elapsedMillis: Long,
        val failureReason: String = "",
    ) {
        val canCapture: Boolean
            get() = status != Status.CANCELLED
    }

    private val startedAtMillis = SystemClock.elapsedRealtime()
    private val completed = AtomicBoolean(false)
    private val completion = CountDownLatch(1)
    @Volatile private var outcome: Outcome? = null
    @Volatile private var lastAutofocusState: Int? = null
    @Volatile private var lastAutoexposureState: Int? = null
    @Volatile private var lastAutoWhiteBalanceState: Int? = null
    private var stage = Stage.STARTING
    private var autoexposurePrecaptureRequested = false

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (completed.get()) return
            if (!isCameraCurrent()) {
                finish(Status.CANCELLED, "camera changed during 3A convergence")
                return
            }
            lastAutofocusState = result.get(CaptureResult.CONTROL_AF_STATE)
            lastAutoexposureState = result.get(CaptureResult.CONTROL_AE_STATE)
            lastAutoWhiteBalanceState = result.get(CaptureResult.CONTROL_AWB_STATE)
            when (stage) {
                Stage.STARTING -> Unit
                Stage.WAITING_AF -> {
                    if (isAutofocusTerminal(lastAutofocusState)) {
                        continueWithAutoexposure(
                            lastAutoexposureState,
                            lastAutoWhiteBalanceState,
                        )
                    }
                }
                Stage.WAITING_AE -> continueWithAutoexposure(
                    lastAutoexposureState,
                    lastAutoWhiteBalanceState,
                )
                Stage.WAITING_AE_PRECAPTURE -> {
                    when {
                        isAutoexposureReady(
                            lastAutoexposureState,
                            acceptFlashRequired = autoexposurePrecaptureRequested,
                        ) -> continueWithAutoWhiteBalance(lastAutoWhiteBalanceState)
                        lastAutoexposureState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> {
                            changeStage(Stage.WAITING_AE_CONVERGED)
                        }
                    }
                }
                Stage.WAITING_AE_CONVERGED -> {
                    if (lastAutoexposureState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE &&
                        lastAutoexposureState != CaptureResult.CONTROL_AE_STATE_SEARCHING
                    ) {
                        continueWithAutoWhiteBalance(lastAutoWhiteBalanceState)
                    }
                }
                Stage.WAITING_AWB -> {
                    if (isAutoWhiteBalanceReady(lastAutoWhiteBalanceState)) lock3A()
                }
                Stage.LOCKING_3A -> {
                    val autoexposureLocked = !autoexposureLockAvailable ||
                        lastAutoexposureState == CaptureResult.CONTROL_AE_STATE_LOCKED
                    val autoWhiteBalanceLocked = !autoWhiteBalanceLockAvailable ||
                        lastAutoWhiteBalanceState == CaptureResult.CONTROL_AWB_STATE_LOCKED
                    if (autoexposureLocked && autoWhiteBalanceLocked) finish(Status.READY)
                }
                Stage.COMPLETE -> Unit
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            finish(Status.FAILED, "Camera2 3A request failed: ${failure.reason}")
        }
    }

    fun await(timeoutMillis: Long): Outcome {
        require(timeoutMillis in 250L..10_000L) { "3A timeout must be in 250..10000 ms" }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Camera3AController.await must run off the main thread"
        }
        if (!cameraHandler.post(::start)) {
            finish(Status.CANCELLED, "camera thread is unavailable")
        }
        if (!completion.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            if (isCameraCurrent()) {
                finish(Status.TIMED_OUT, "3A convergence timed out")
            } else {
                finish(Status.CANCELLED, "camera changed during 3A convergence")
            }
        }
        return checkNotNull(outcome) { "3A controller completed without an outcome" }
    }

    private fun start() {
        if (completed.get()) return
        if (!isCameraCurrent()) {
            finish(Status.CANCELLED, "camera changed before 3A convergence")
            return
        }
        try {
            val builder = previewRequest()
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            if (triggerAutofocus) {
                changeStage(Stage.WAITING_AF)
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START,
                )
            } else {
                changeStage(Stage.WAITING_AE)
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE,
                )
            }
            session.capture(builder.build(), captureCallback, cameraHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (exception: Throwable) {
            finish(Status.FAILED, exception.message.orEmpty())
        }
    }

    private fun continueWithAutoexposure(
        autoexposureState: Int?,
        autoWhiteBalanceState: Int?,
    ) {
        if (isAutoexposureReady(autoexposureState, acceptFlashRequired = false)) {
            continueWithAutoWhiteBalance(autoWhiteBalanceState)
        } else {
            beginAutoexposurePrecapture()
        }
    }

    private fun continueWithAutoWhiteBalance(state: Int?) {
        if (isAutoWhiteBalanceReady(state)) {
            lock3A()
        } else {
            changeStage(Stage.WAITING_AWB)
        }
    }

    private fun lock3A() {
        if (completed.get() || stage == Stage.LOCKING_3A) return
        if (!autoexposureLockAvailable && !autoWhiteBalanceLockAvailable) {
            finish(Status.READY)
            return
        }
        try {
            changeStage(Stage.LOCKING_3A)
            val builder = previewRequest().apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                )
                if (autoexposureLockAvailable) set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (autoWhiteBalanceLockAvailable) {
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }
            session.capture(builder.build(), captureCallback, cameraHandler)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (exception: Throwable) {
            finish(Status.FAILED, exception.message.orEmpty())
        }
    }

    private fun beginAutoexposurePrecapture() {
        if (completed.get() || stage == Stage.WAITING_AE_CONVERGED) return
        try {
            autoexposurePrecaptureRequested = true
            changeStage(Stage.WAITING_AE_PRECAPTURE)
            val builder = previewRequest().apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START,
                )
            }
            session.capture(builder.build(), captureCallback, cameraHandler)
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (exception: Throwable) {
            finish(Status.FAILED, exception.message.orEmpty())
        }
    }

    private fun changeStage(value: Stage) {
        stage = value
        stageListener(
            value,
            lastAutofocusState,
            lastAutoexposureState,
            lastAutoWhiteBalanceState,
        )
    }

    private fun finish(status: Status, failureReason: String = "") {
        if (!completed.compareAndSet(false, true)) return
        stage = Stage.COMPLETE
        val result = Outcome(
            status = status,
            autofocusState = lastAutofocusState,
            autoexposureState = lastAutoexposureState,
            autoWhiteBalanceState = lastAutoWhiteBalanceState,
            elapsedMillis = SystemClock.elapsedRealtime() - startedAtMillis,
            failureReason = failureReason,
        )
        outcome = result
        completion.countDown()
        val notify = {
            stageListener(
                Stage.COMPLETE,
                result.autofocusState,
                result.autoexposureState,
                result.autoWhiteBalanceState,
            )
        }
        if (Looper.myLooper() == cameraHandler.looper) notify() else cameraHandler.post(notify)
    }

    private fun isAutofocusTerminal(state: Int?): Boolean =
        state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED

    private fun isAutoexposureReady(state: Int?, acceptFlashRequired: Boolean): Boolean =
        state == null ||
            state == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            state == CaptureResult.CONTROL_AE_STATE_LOCKED ||
            (acceptFlashRequired && state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)

    private fun isAutoWhiteBalanceReady(state: Int?): Boolean =
        state == null ||
            state == CaptureResult.CONTROL_AWB_STATE_INACTIVE ||
            state == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            state == CaptureResult.CONTROL_AWB_STATE_LOCKED
}
