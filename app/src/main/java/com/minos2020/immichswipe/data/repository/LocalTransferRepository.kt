package com.minos2020.immichswipe.data.repository

import com.minos2020.immichswipe.core.SessionConfig
import com.minos2020.immichswipe.data.api.AddAssetsToAlbumRequest
import com.minos2020.immichswipe.data.api.ImmichApi
import com.minos2020.immichswipe.feature.local.LocalMedia
import com.minos2020.immichswipe.feature.local.LocalUploadReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.HttpException
import java.time.Instant

/** A deletion receipt is returned only after bytes, album membership and local source are verified. */
class LocalTransferRepository(
    private val media: LocalMediaRepository,
    private val api: ImmichApi,
    private val config: SessionConfig
) {
    private val account = config.baseUrl.trimEnd('/') + "\n" + config.userId

    suspend fun uploadToAlbum(photo: LocalMedia, albumId: String, onProgress: (Float) -> Unit): LocalUploadReceipt = withContext(Dispatchers.IO) {
        check(media.unchanged(photo)) { "Local file changed" }
        val checksum = media.checksum(photo)
        var receipt = media.receipt(account, photo)?.takeIf { it.checksum == checksum }
        if (receipt == null) {
            fun fields(legacy: Boolean): Map<String, RequestBody> {
                val values = linkedMapOf("fileCreatedAt" to Instant.ofEpochMilli(photo.takenAt).toString(),
                    "fileModifiedAt" to Instant.ofEpochMilli(photo.modifiedAt).toString())
                if (legacy) {
                    values["deviceId"] = media.deviceId()
                    values["deviceAssetId"] = photo.key
                }
                return values.mapValues { (_, value) -> value.toRequestBody("text/plain".toMediaType()) }
            }
            val coroutine = currentCoroutineContext()
            val body = object : RequestBody() {
                override fun contentType() = photo.mimeType.toMediaType()
                override fun contentLength() = photo.size
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    media.open(photo).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var written = 0L
                        var lastUpdate = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            sink.write(buffer, 0, n); written += n
                            val now = System.nanoTime()
                            if (now - lastUpdate > 200_000_000L || written == photo.size) {
                                onProgress((written.toFloat() / photo.size).coerceIn(0f, 1f)); lastUpdate = now
                            }
                        }
                    }
                }
            }
            val file = MultipartBody.Part.createFormData("assetData", photo.name.replace('"', '_').replace('\n', '_').replace('\r', '_'), body)
            val uploaded = try {
                api.uploadLocalAsset(fields(false), file)
            } catch (e: HttpException) {
                val message = e.response()?.errorBody()?.string().orEmpty()
                if (e.code() != 400 || !("deviceId" in message || "deviceAssetId" in message)) throw e
                // Older Immich versions require device fields removed from newer servers.
                api.uploadLocalAsset(fields(true), file)
            }
            check(uploaded.id.isNotBlank() && uploaded.status in setOf("created", "duplicate", "replaced")) { "Unconfirmed upload" }
            receipt = LocalUploadReceipt(uploaded.id, checksum)
            media.saveReceipt(account, photo, receipt)
        }
        verifyRemote(receipt)
        val added = api.addAssetsToAlbum(albumId, AddAssetsToAlbumRequest(listOf(receipt.assetId)))
            .firstOrNull { it.id == receipt.assetId }
        check(added != null && (added.success || added.error == "duplicate")) { "Album assignment failed" }
        check(api.getAlbumsForAsset(receipt.assetId).any { it.id == albumId }) { "Album membership not confirmed" }
        check(media.unchanged(photo) && media.checksum(photo) == checksum) { "Local file changed during upload" }
        receipt.copy(albumId = albumId).also { media.saveReceipt(account, photo, it) }
    }

    suspend fun verifyBeforeDeletion(photo: LocalMedia, receipt: LocalUploadReceipt) = withContext(Dispatchers.IO) {
        check(receipt.albumId.isNotBlank())
        verifyRemote(receipt)
        check(api.getAlbumsForAsset(receipt.assetId).any { it.id == receipt.albumId })
        check(media.unchanged(photo) && media.checksum(photo) == receipt.checksum)
    }

    private suspend fun verifyRemote(receipt: LocalUploadReceipt) {
        val remote = api.verifyLocalUpload(receipt.assetId)
        check(config.userId.isNotBlank() && remote.ownerId == config.userId && remote.id == receipt.assetId &&
            !remote.isTrashed && !remote.isOffline && remote.checksum == receipt.checksum) {
            "Cloud file verification failed; local original retained"
        }
    }
}
