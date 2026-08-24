package com.phytoy.engine

import android.content.Context
import android.view.Surface
import java.io.Closeable
import java.io.File

/**
 * Owns one PhyToyEngine instance and one NDK AImageReader configured for Camera2 PRIVATE input.
 * Camera2 must target [inputSurface]. The ImageReader callback only queues the latest frame;
 * rendering runs on a dedicated native worker so callers can poll [snapshot] without blocking
 * Camera2 or the UI thread.
 */
class PhyToyCameraSession private constructor(
    private var nativeHandle: Long,
    val inputSurface: Surface,
) : Closeable {

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
    )

    fun snapshot(): Snapshot {
        val handle = nativeHandle
        if (handle == 0L) {
            return Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        }
        val values = nativeSnapshot(handle)
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
        )
    }

    fun lastError(): String = nativeHandle.takeIf { it != 0L }?.let(::nativeLastError).orEmpty()

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        inputSurface.release()
        nativeClose(handle)
    }

    companion object {
        private const val SNAPSHOT_FIELD_COUNT = 16
        private const val PROFILE_ASSET_DIRECTORY = "phytoy"
        private const val PROFILE_CACHE_VERSION = "0.2.0"

        init {
            System.loadLibrary("phytoy_core")
            System.loadLibrary("phytoy_android")
        }

        @JvmStatic
        fun open(
            context: Context,
            width: Int,
            height: Int,
            maxImages: Int = 4,
            hostProfileAsset: String = "host_generic_srgb.ptp",
            toyProfileAsset: String = "toy_phytoy_digital_01_v1.ptp",
        ): PhyToyCameraSession {
            require(width > 0 && height > 0) { "Camera dimensions must be positive" }
            require(maxImages >= 3) { "maxImages must be at least 3 for asynchronous latest-frame processing" }
            val host = materializeProfile(context, hostProfileAsset)
            val toy = materializeProfile(context, toyProfileAsset)
            val handle = nativeCreate(host.absolutePath, toy.absolutePath, width, height, maxImages)
            check(handle != 0L) { "Unable to create native PhyToy Camera2 session" }
            return try {
                val surface = checkNotNull(nativeInputSurface(handle)) {
                    "Native Camera2 input surface is unavailable"
                }
                PhyToyCameraSession(handle, surface)
            } catch (exception: Throwable) {
                nativeClose(handle)
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
        ): Long
        @JvmStatic private external fun nativeInputSurface(handle: Long): Surface?
        @JvmStatic private external fun nativeSnapshot(handle: Long): LongArray
        @JvmStatic private external fun nativeLastError(handle: Long): String
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
