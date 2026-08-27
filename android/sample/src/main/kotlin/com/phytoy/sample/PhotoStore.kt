package com.phytoy.sample

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.util.Log
import com.phytoy.engine.PhyToyCameraSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal class PhotoStore(private val context: Context) {
    data class SavedPhoto(
        val uri: Uri,
        val displayName: String,
        val width: Int,
        val height: Int,
    )

    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(
        frame: PhyToyCameraSession.CapturedFrame,
        metadata: CaptureMetadata,
    ): SavedPhoto {
        val bitmap = frame.toOrientedBitmap()
        var temporaryJpeg: File? = null
        try {
            val capturedAt = Date(metadata.capturedAtMillis)
            val name = "PT_${FILE_STAMP.format(capturedAt)}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, JPEG_MIME_TYPE)
                put(MediaStore.Images.Media.WIDTH, bitmap.width)
                put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                put(MediaStore.Images.Media.DATE_TAKEN, metadata.capturedAtMillis)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    val directory = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                        ),
                        ALBUM_NAME,
                    )
                    check(directory.exists() || directory.mkdirs()) {
                        "Unable to create the PhyToy album"
                    }
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
                }
            }
            val uri = checkNotNull(
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ) { "MediaStore rejected the new photo" }
            try {
                temporaryJpeg = File.createTempFile("phytoy_capture_", ".jpg", context.cacheDir)
                temporaryJpeg.outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        "JPEG encoder failed"
                    }
                }
                writeExif(temporaryJpeg, metadata)
                resolver.openOutputStream(uri, "w").use { stream ->
                    checkNotNull(stream) { "Unable to open the new photo" }
                    temporaryJpeg.inputStream().use { source -> source.copyTo(stream) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
                preferences.edit().putString(LAST_PHOTO_URI, uri.toString()).apply()
                return SavedPhoto(uri, name, bitmap.width, bitmap.height)
            } catch (exception: Throwable) {
                resolver.delete(uri, null, null)
                throw exception
            }
        } finally {
            temporaryJpeg?.delete()
            bitmap.recycle()
        }
    }

    private fun writeExif(file: File, metadata: CaptureMetadata) {
        val dateTime = EXIF_STAMP.format(Date(metadata.capturedAtMillis))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_DATETIME, dateTime)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
            setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime)
            metadata.iso?.let {
                setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString())
            }
            metadata.exposureTimeNanos?.let {
                setAttribute(
                    ExifInterface.TAG_EXPOSURE_TIME,
                    String.format(Locale.US, "%.9f", it / 1_000_000_000.0),
                )
            }
            metadata.focalLengthMillimeters?.let {
                setAttribute(
                    ExifInterface.TAG_FOCAL_LENGTH,
                    "${(it * EXIF_RATIONAL_SCALE).roundToInt()}/$EXIF_RATIONAL_SCALE",
                )
            }
            setAttribute(
                ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                "${(metadata.exposureCompensation * EXIF_RATIONAL_SCALE).roundToInt()}/" +
                    EXIF_RATIONAL_SCALE,
            )
            setAttribute(
                ExifInterface.TAG_SOFTWARE,
                "PhyToy Camera / Digital 01 ${metadata.digital01Version}",
            )
            setAttribute(
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                "PhyToy Digital 01 ${metadata.digital01Version}",
            )
            setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "Lens=${metadata.lensFacing}; Digital01=${metadata.digital01Version}; " +
                    "Zoom=${String.format(Locale.US, "%.2f", metadata.zoomRatio)}x; " +
                    "EV=${String.format(Locale.US, "%+.2f", metadata.exposureCompensation)}; " +
                    "Flash=${metadata.flashMode}",
            )
            saveAttributes()
        }
        val verified = ExifInterface(file.absolutePath)
        check(verified.getAttribute(ExifInterface.TAG_SOFTWARE)?.contains(
            metadata.digital01Version
        ) == true) { "Digital 01 EXIF verification failed" }
        Log.i(
            LOG_TAG,
            "Digital 01 EXIF written iso=${metadata.iso ?: "unknown"} " +
                "exposure_ns=${metadata.exposureTimeNanos ?: "unknown"} " +
                "focal_mm=${metadata.focalLengthMillimeters ?: "unknown"} " +
                "lens=${metadata.lensFacing} version=${metadata.digital01Version}",
        )
    }

    fun lastPhotoUri(): Uri? = preferences.getString(LAST_PHOTO_URI, null)?.let(Uri::parse)

    fun loadThumbnail(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
        } else {
            resolver.openInputStream(uri).use { stream ->
                val source = BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = 4 },
                ) ?: return null
                ThumbnailUtils.extractThumbnail(source, THUMBNAIL_SIZE, THUMBNAIL_SIZE).also {
                    if (it !== source) source.recycle()
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun PhyToyCameraSession.CapturedFrame.toOrientedBitmap(): Bitmap {
        val source = Bitmap.createBitmap(argb8888, width, height, Bitmap.Config.ARGB_8888)
        if (rotationDegrees == 0) return source
        val result = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
        if (result !== source) source.recycle()
        return result
    }

    companion object {
        private const val PREFERENCES_NAME = "phytoy_camera"
        private const val LAST_PHOTO_URI = "last_photo_uri"
        private const val ALBUM_NAME = "PhyToy"
        private const val JPEG_MIME_TYPE = "image/jpeg"
        private const val JPEG_QUALITY = 95
        private const val THUMBNAIL_SIZE = 192
        private const val EXIF_RATIONAL_SCALE = 1_000
        private const val LOG_TAG = "PhyToyPhotoStore"
        private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private val EXIF_STAMP = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}
