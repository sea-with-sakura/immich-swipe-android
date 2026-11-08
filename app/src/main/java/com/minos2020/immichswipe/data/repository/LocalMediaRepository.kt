package com.minos2020.immichswipe.data.repository

import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.gson.Gson
import com.minos2020.immichswipe.feature.local.LocalMedia
import com.minos2020.immichswipe.feature.local.LocalUploadReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** MediaStore IDs never leave this device except as optional legacy upload identifiers. */
class LocalMediaRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences("local_photo_organizer", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun load(): List<LocalMedia> = withContext(Dispatchers.IO) {
        val result = mutableListOf<LocalMedia>()
        for ((uri, fallbackMime) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image/jpeg", MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video/mp4")) {
            try {
                resolver.query(uri, arrayOf("_id", "_display_name", "mime_type", "_size", "datetaken", "date_added", "date_modified"),
                    if (Build.VERSION.SDK_INT >= 29) "is_pending = 0" else null, null, "date_added DESC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val size = cursor.getLong(3)
                        if (size <= 0) continue
                        val added = cursor.getLong(5) * 1000
                        result += LocalMedia(ContentUris.withAppendedId(uri, cursor.getLong(0)).toString(),
                            cursor.getString(1) ?: "photo", cursor.getString(2) ?: fallbackMime, size,
                            cursor.getLong(4).takeIf { it > 0 } ?: added,
                            (cursor.getLong(6) * 1000).takeIf { it > 0 } ?: added)
                    }
                }
            } catch (e: SecurityException) {
                // Partial media access can grant images without videos, or vice versa.
            }
        }
        result
    }

    fun kept(): Set<String> = prefs.getStringSet("kept", emptySet())!!.toSet()
    suspend fun keep(keys: Set<String>, value: Boolean) = withContext(Dispatchers.IO) {
        val updated = if (value) kept() + keys else kept() - keys
        check(prefs.edit().putStringSet("kept", updated).commit())
    }
    fun deviceId(): String = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("device", it).commit())
    }
    private fun receiptKey(account: String, key: String): String = "upload:" + MessageDigest.getInstance("SHA-256")
        .digest("$account\n$key".toByteArray()).joinToString("") { "%02x".format(it) }
    fun receipt(account: String, media: LocalMedia): LocalUploadReceipt? = prefs.getString(receiptKey(account, media.key), null)?.let {
        runCatching { gson.fromJson(it, LocalUploadReceipt::class.java) }.getOrNull()
    }
    suspend fun saveReceipt(account: String, media: LocalMedia, receipt: LocalUploadReceipt) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString(receiptKey(account, media.key), gson.toJson(receipt)).commit())
    }
    fun open(media: LocalMedia): java.io.InputStream {
        val uri = Uri.parse(media.uri)
        val original = if (Build.VERSION.SDK_INT >= 29 &&
            app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
            MediaStore.setRequireOriginal(uri) else uri
        return checkNotNull(resolver.openInputStream(original)) { "Local file is unavailable" }
    }
    suspend fun checksum(media: LocalMedia): String = withContext(Dispatchers.IO) {
        val hash = MessageDigest.getInstance("SHA-1")
        open(media).use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        Base64.getEncoder().encodeToString(hash.digest())
    }
    suspend fun unchanged(media: LocalMedia): Boolean = withContext(Dispatchers.IO) {
        resolver.query(Uri.parse(media.uri), arrayOf("_size", "date_modified", "date_added"), null, null, null)?.use {
            it.moveToFirst() && it.getLong(0) == media.size &&
                ((it.getLong(1) * 1000).takeIf { modified -> modified > 0 } ?: it.getLong(2) * 1000) == media.modifiedAt
        } ?: false
    }
}
