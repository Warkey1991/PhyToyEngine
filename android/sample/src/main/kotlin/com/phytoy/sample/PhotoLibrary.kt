package com.phytoy.sample

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Locale

/** Private index of PhyToy captures. Nothing here deletes a MediaStore photo. */
internal class PhotoLibrary(context: Context) {
    data class Entry(val photo: PhotoStore.SavedPhoto, val capturedAtMillis: Long)

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("phytoy_photo_library", Context.MODE_PRIVATE)

    /** An indexing failure never turns a successfully saved photo into a failed save. */
    fun record(photo: PhotoStore.SavedPhoto, capturedAtMillis: Long = 0L): Boolean = try {
        synchronized(LOCK) {
            val entries = readEntries().associateBy { it.photo.uri.toString() }.toMutableMap()
            entries[photo.uri.toString()] = Entry(
                photo,
                capturedAtMillis.takeIf { it > 0 } ?: timestampFromName(photo.displayName)
                    ?: System.currentTimeMillis(),
            )
            writeEntries(entries.values.toList(), missingUris() - photo.uri.toString())
        }
    } catch (_: Exception) {
        false
    }

    fun migrateLastPhoto(photo: PhotoStore.SavedPhoto?): Boolean {
        if (photo == null) {
            preferences.edit().putBoolean("last_photo_migrated", true).apply()
            return true
        }
        synchronized(LOCK) {
            if (photo.uri.toString() in missingUris() || readEntries().any { it.photo.uri == photo.uri }) {
                preferences.edit().putBoolean("last_photo_migrated", true).apply()
                return true
            }
        }
        return record(photo).also { migrated ->
            if (migrated) preferences.edit().putBoolean("last_photo_migrated", true).apply()
        }
    }

    fun list(): List<PhotoStore.SavedPhoto> = entries().map { it.photo }

    /** Call off the UI thread. Discovery is restricted to Pictures/PhyToy and PT_ files. */
    fun entries(): List<Entry> {
        if (!preferences.getBoolean("last_photo_migrated", false)) {
            migrateLastPhoto(PhotoStore(appContext).lastPhoto())
        }
        val discovered = discoverOwnAlbum()
        return synchronized(LOCK) {
            val indexed = readEntries().associateBy { it.photo.uri.toString() }.toMutableMap()
            val previousMissing = missingUris()
            // A deleted provider row no longer needs a tombstone. A failed scan preserves them.
            val confirmedMissing = discovered?.let { found ->
                previousMissing.intersect(found.map { it.photo.uri.toString() }.toSet())
            } ?: previousMissing
            val readableCandidates = discovered.orEmpty().filterNot { it.photo.uri.toString() in confirmedMissing }
            val discoveredNewPhoto = readableCandidates.any { it.photo.uri.toString() !in indexed }
            readableCandidates.forEach { candidate -> indexed.putIfAbsent(candidate.photo.uri.toString(), candidate) }
            val result = indexed.values.sortedWith(compareByDescending<Entry> { it.capturedAtMillis }
                .thenByDescending { it.photo.displayName })
            if (discoveredNewPhoto || confirmedMissing != previousMissing) writeEntries(result, confirmedMissing)
            result
        }
    }

    /** Remove only missing index entries; permission/provider failures preserve the index. */
    fun prune(): Int {
        val snapshot = synchronized(LOCK) { readEntries() }
        val missing = snapshot.filter { entry ->
            try {
                resolver.openFileDescriptor(entry.photo.uri, "r").use { it == null }
            } catch (_: FileNotFoundException) {
                true
            } catch (_: SecurityException) {
                false
            } catch (_: Exception) {
                false
            }
        }.map { it.photo.uri.toString() }.toSet()
        if (missing.isEmpty()) return 0
        return synchronized(LOCK) {
            val current = readEntries()
            val retained = current.filterNot { it.photo.uri.toString() in missing }
            if (writeEntries(retained, missingUris() + missing)) current.size - retained.size else 0
        }
    }

    private fun discoverOwnAlbum(): List<Entry>? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val selection: String
        val arguments: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?" +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    " AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
                } else ""
            arguments = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                arrayOf("Pictures/PhyToy/", "PT_%", appContext.packageName)
            } else arrayOf("Pictures/PhyToy/", "PT_%")
        } else {
            @Suppress("DEPRECATION")
            val album = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhyToy")
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            arguments = arrayOf(album.absolutePath + "/%", "PT_%")
        }
        return try {
            val found = mutableListOf<Entry>()
            val queried = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, arguments, null)
                ?: return null
            queried.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1).orEmpty()
                        if (!name.startsWith("PT_")) continue
                        val photo = PhotoStore.SavedPhoto(
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)),
                            displayName = name,
                            width = cursor.getInt(2),
                            height = cursor.getInt(3),
                            styleName = "PHYTOY",
                            styleCode = "PT",
                        )
                        val taken = cursor.getLong(4).takeIf { it > 0 }
                            ?: timestampFromName(name) ?: cursor.getLong(5) * 1_000L
                        found.add(Entry(photo, taken))
                    }
                }
            found
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            // Some OEM providers omit album/owner columns. Existing private entries stay usable.
            null
        }
    }

    private fun readEntries(): List<Entry> {
        val raw = preferences.getString("photos_v1", null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri"))
                check(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
                Entry(
                    PhotoStore.SavedPhoto(uri, item.optString("name"), item.optInt("width"), item.optInt("height"),
                        item.optString("styleName", "PHYTOY"), item.optString("styleCode", "PT")),
                    item.optLong("capturedAtMillis"),
                )
            }.getOrNull()
        }.distinctBy { it.photo.uri.toString() }
    }

    private fun missingUris(): Set<String> = preferences.getStringSet("missing_uris", emptySet()).orEmpty().toSet()

    private fun writeEntries(entries: List<Entry>, missing: Set<String>? = null): Boolean {
        val array = JSONArray()
        entries.sortedByDescending { it.capturedAtMillis }.forEach { entry ->
            array.put(JSONObject().apply {
                put("uri", entry.photo.uri.toString())
                put("name", entry.photo.displayName)
                put("width", entry.photo.width)
                put("height", entry.photo.height)
                put("styleName", entry.photo.styleName)
                put("styleCode", entry.photo.styleCode)
                put("capturedAtMillis", entry.capturedAtMillis)
            })
        }
        val edit = preferences.edit().putString("photos_v1", array.toString())
        if (missing != null) edit.putStringSet("missing_uris", missing.toList().takeLast(MAX_MISSING_URIS).toSet())
        return edit.commit()
    }

    private fun timestampFromName(name: String): Long? = runCatching {
        if (!name.startsWith("PT_")) return@runCatching null
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply { isLenient = false }
            .parse(name.removePrefix("PT_").substringBeforeLast('.'))?.time
    }.getOrNull()

    companion object {
        private val LOCK = Any()
        private const val MAX_MISSING_URIS = 4_096
    }
}
