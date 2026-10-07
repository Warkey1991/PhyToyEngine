package com.phytoy.sample

import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
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
        val styleName: String,
        val styleCode: String,
    )

    data class SaveResult(
        val photo: SavedPhoto,
        val thumbnail: Bitmap,
        val review: Bitmap,
    )

    sealed class DeleteResult {
        data object Deleted : DeleteResult()
        data class ConsentRequired(val intentSender: IntentSender) : DeleteResult()
        data object Failed : DeleteResult()
    }

    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(
        frame: PhyToyCameraSession.CapturedFrame,
        metadata: CaptureMetadata,
        reviewMaximumWidth: Int,
        reviewMaximumHeight: Int,
    ): SaveResult {
        val allocated = mutableListOf<Bitmap>()
        fun own(bitmap: Bitmap): Bitmap = bitmap.also { allocated.add(it) }
        var retainedReview: Bitmap? = null
        var retainedThumbnail: Bitmap? = null
        try {
            val oriented = own(frame.toOrientedBitmap())
            val cropped = oriented.centerCrop(metadata.outputAspect).also {
                if (it !== oriented) oriented.recycle()
            }.let(::own)
            val bitmap = cropped.fitPixelBudget(metadata.maximumOutputPixels).also {
                if (it !== cropped) cropped.recycle()
            }.let(::own)
            val review = own(bitmap.scaledToFit(reviewMaximumWidth, reviewMaximumHeight))
            val thumbnail = ThumbnailUtils.extractThumbnail(
                review,
                THUMBNAIL_SIZE,
                THUMBNAIL_SIZE,
            ).let { extracted ->
                if (extracted !== review) extracted
                else checkNotNull(extracted.copy(Bitmap.Config.ARGB_8888, false))
            }.let(::own)
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
                resolver.openOutputStream(uri, "w").use { stream ->
                    checkNotNull(stream) { "Unable to open the new photo" }
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        "JPEG encoder failed"
                    }
                }
                writeExif(uri, metadata)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    check(resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    ) == 1) { "Unable to publish the new photo" }
                }
                val saved = SavedPhoto(
                    uri = uri,
                    displayName = name,
                    width = bitmap.width,
                    height = bitmap.height,
                    styleName = metadata.styleName,
                    styleCode = metadata.styleCode,
                )
                preferences.edit()
                    .putString(LAST_PHOTO_URI, uri.toString())
                    .putString(LAST_PHOTO_NAME, saved.displayName)
                    .putInt(LAST_PHOTO_WIDTH, saved.width)
                    .putInt(LAST_PHOTO_HEIGHT, saved.height)
                    .putString(LAST_PHOTO_STYLE_NAME, saved.styleName)
                    .putString(LAST_PHOTO_STYLE_CODE, saved.styleCode)
                    .apply()
                retainedReview = review
                retainedThumbnail = thumbnail
                return SaveResult(saved, thumbnail, review)
            } catch (exception: Throwable) {
                // Keep the actual encoding/storage failure if cleanup also fails.
                runCatching { resolver.delete(uri, null, null) }
                    .exceptionOrNull()?.let(exception::addSuppressed)
                throw exception
            }
        } finally {
            allocated.forEach { bitmap ->
                if (bitmap !== retainedReview && bitmap !== retainedThumbnail && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun writeExif(uri: Uri, metadata: CaptureMetadata) {
        val dateTime = EXIF_STAMP.format(Date(metadata.capturedAtMillis))
        resolver.openFileDescriptor(uri, "rw").use { descriptor ->
            checkNotNull(descriptor) { "Unable to reopen the new photo for EXIF" }
            ExifInterface(descriptor.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(ExifInterface.TAG_DATETIME, dateTime)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime)
                metadata.iso?.let {
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
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
                    "ToviCam / ${metadata.styleCode} ${metadata.styleVersion}",
                )
                setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "ToviCam ${metadata.styleCode} ${metadata.styleVersion}",
                )
                setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    "Lens=${metadata.lensFacing}; Style=${metadata.styleCode}; " +
                        "StyleVersion=${metadata.styleVersion}; " +
                        "Zoom=${String.format(Locale.US, "%.2f", metadata.zoomRatio)}x; " +
                        "EV=${String.format(Locale.US, "%+.2f", metadata.exposureCompensation)}; " +
                        "Flash=${metadata.flashMode}",
                )
                saveAttributes()
            }
        }
        resolver.openFileDescriptor(uri, "r").use { descriptor ->
            checkNotNull(descriptor) { "Unable to verify the new photo EXIF" }
            val verified = ExifInterface(descriptor.fileDescriptor)
            check(verified.getAttribute(ExifInterface.TAG_SOFTWARE)?.contains(
                metadata.styleVersion
            ) == true) { "PhyToy style EXIF verification failed" }
        }
        Log.i(
            LOG_TAG,
            "${metadata.styleCode} EXIF written iso=${metadata.iso ?: "unknown"} " +
                "exposure_ns=${metadata.exposureTimeNanos ?: "unknown"} " +
                "focal_mm=${metadata.focalLengthMillimeters ?: "unknown"} " +
                "lens=${metadata.lensFacing} version=${metadata.styleVersion}",
        )
    }

    fun lastPhoto(): SavedPhoto? {
        val uri = preferences.getString(LAST_PHOTO_URI, null)?.let(Uri::parse) ?: return null
        return SavedPhoto(
            uri = uri,
            displayName = preferences.getString(LAST_PHOTO_NAME, null).orEmpty(),
            width = preferences.getInt(LAST_PHOTO_WIDTH, 0),
            height = preferences.getInt(LAST_PHOTO_HEIGHT, 0),
            styleName = preferences.getString(LAST_PHOTO_STYLE_NAME, null)
                ?: "PHYTOY",
            styleCode = preferences.getString(LAST_PHOTO_STYLE_CODE, null)
                ?: "PT",
        )
    }

    fun forgetLastPhoto() {
        preferences.edit().clear().apply()
    }

    /** A temporary read grant for this MediaStore item; no broad storage permission. */
    fun createShareIntent(photo: SavedPhoto): Intent {
        require(isPhotoUri(photo.uri)) { "Only saved MediaStore photos can be shared" }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = JPEG_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            clipData = ClipData.newRawUri(photo.displayName, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return share
    }

    /** Call off the UI thread, after app confirmation. Platform consent is returned to the activity. */
    fun delete(photo: SavedPhoto): DeleteResult {
        if (!isPhotoUri(photo.uri)) return DeleteResult.Failed
        return try {
            // Zero rows also means success: a confirmed Android 11+ delete request has
            // already removed the item, and repeated completion must be idempotent.
            if (resolver.delete(photo.uri, null, null) < 0) DeleteResult.Failed
            else {
                if (lastPhoto()?.uri == photo.uri) forgetLastPhoto()
                DeleteResult.Deleted
            }
        } catch (exception: SecurityException) {
            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> DeleteResult.ConsentRequired(
                        MediaStore.createDeleteRequest(resolver, listOf(photo.uri)).intentSender,
                    )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exception is RecoverableSecurityException ->
                        DeleteResult.ConsentRequired(exception.userAction.actionIntent.intentSender)
                    else -> DeleteResult.Failed
                }
            } catch (_: Exception) { DeleteResult.Failed }
        } catch (_: Exception) { DeleteResult.Failed }
    }

    private fun isPhotoUri(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY &&
        uri.pathSegments.let { parts -> parts.size == 4 && parts[1] == "images" && parts[2] == "media" &&
            parts[3].toLongOrNull() != null }

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

    fun loadReview(uri: Uri, maximumWidth: Int, maximumHeight: Int,
                   cancellationSignal: CancellationSignal? = null): Bitmap? {
        return try {
            cancellationSignal?.throwIfCanceled()
            if (Thread.currentThread().isInterrupted) return null
            val width = maximumWidth.coerceAtLeast(1)
            val height = maximumHeight.coerceAtLeast(1)
            val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, Size(width, height), cancellationSignal)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri).use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
                cancellationSignal?.throwIfCanceled()
                if (Thread.currentThread().isInterrupted) return null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
                    sample *= 2
                }
                resolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
            if (cancellationSignal?.isCanceled == true || Thread.currentThread().isInterrupted) {
                decoded?.recycle()
                null
            } else decoded
        } catch (_: Throwable) {
            null
        }
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

    private fun Bitmap.centerCrop(targetAspect: Float): Bitmap {
        require(targetAspect > 0f)
        val sourceAspect = width.toFloat() / height
        if (kotlin.math.abs(sourceAspect - targetAspect) < 0.0001f) return this
        val cropWidth: Int
        val cropHeight: Int
        if (sourceAspect > targetAspect) {
            cropWidth = (height * targetAspect).roundToInt().coerceIn(1, width)
            cropHeight = height
        } else {
            cropWidth = width
            cropHeight = (width / targetAspect).roundToInt().coerceIn(1, height)
        }
        return Bitmap.createBitmap(
            this,
            (width - cropWidth) / 2,
            (height - cropHeight) / 2,
            cropWidth,
            cropHeight,
        )
    }

    private fun Bitmap.fitPixelBudget(maximumPixels: Long): Bitmap {
        require(maximumPixels > 0)
        val pixels = width.toLong() * height
        if (pixels <= maximumPixels) return this
        val scale = kotlin.math.sqrt(maximumPixels.toDouble() / pixels)
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun Bitmap.scaledToFit(maximumWidth: Int, maximumHeight: Int): Bitmap {
        val widthLimit = maximumWidth.coerceAtLeast(1)
        val heightLimit = maximumHeight.coerceAtLeast(1)
        val scale = minOf(
            widthLimit.toFloat() / width,
            heightLimit.toFloat() / height,
            1f,
        )
        if (scale >= 1f) {
            return checkNotNull(copy(Bitmap.Config.ARGB_8888, false))
        }
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "phytoy_camera"
        private const val LAST_PHOTO_URI = "last_photo_uri"
        private const val LAST_PHOTO_NAME = "last_photo_name"
        private const val LAST_PHOTO_WIDTH = "last_photo_width"
        private const val LAST_PHOTO_HEIGHT = "last_photo_height"
        private const val LAST_PHOTO_STYLE_NAME = "last_photo_style_name"
        private const val LAST_PHOTO_STYLE_CODE = "last_photo_style_code"
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
