package com.minos2020.immichswipe.feature.local.transfer

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.google.gson.Gson
import com.minos2020.immichswipe.core.SessionConfig
import com.minos2020.immichswipe.feature.local.LocalMedia
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.TimeUnit

fun SessionConfig.localAccount(): String = baseUrl.trimEnd('/') + "\n" + userId

interface LocalTransferQueue {
    val account: String
    val tasks: Flow<List<LocalTransferTask>>
    suspend fun enqueue(photos: List<LocalMedia>, albumId: String, deleteOnly: Boolean = false)
    suspend fun retry()
    suspend fun cancel()
    suspend fun completed(keys: Set<String>)
    suspend fun prune(existing: Set<String>)
    fun resume()
}

class PersistentLocalTransferQueue(context: Context, override val account: String) : LocalTransferQueue {
    private val app = context.applicationContext
    private val db = LocalTransferDatabase.get(app)
    private val dao = db.tasks()
    override val tasks = dao.observe()
    override suspend fun enqueue(photos: List<LocalMedia>, albumId: String, deleteOnly: Boolean) {
        db.withTransaction {
            photos.forEach { photo ->
                val old = dao.get(photo.key)
                if (old?.state !in TransferState.active) dao.put(LocalTransferTask(photo.key,
                    UUID.randomUUID().toString(), account, Gson().toJson(photo), albumId, deleteOnly))
            }
        }
        // Start while the user's tap grants a foreground-service start window.
        // Permission preparation must never delay the actual background upload.
        resume()
        LocalMediaDeletion.prepareInBackground(app, photos)
    }
    override suspend fun retry() { dao.retry(account); resume() }
    override suspend fun cancel() {
        dao.cancel(account)
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork("local-photo-transfers")
        work.cancelUniqueWork("local-photo-deletions")
    }
    override suspend fun completed(keys: Set<String>) {
        keys.forEach { key -> dao.get(key)?.takeIf { it.account == account }?.let {
            dao.update(key, it.generation, TransferState.DONE, it.attempts)
        } }
    }
    override suspend fun prune(existing: Set<String>) {
        dao.done().filter { it.key !in existing }.map { it.key }.chunked(200).forEach { dao.prune(it) }
    }
    override fun resume() { schedule(false); schedule(true) }
    private fun schedule(localOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<LocalUploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf("account" to account, "localOnly" to localOnly))
            .setConstraints(Constraints.NONE)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        // Separate local deletion from network work, so offline deletes remain immediate.
        val name = if (localOnly) "local-photo-deletions" else "local-photo-transfers"
        WorkManager.getInstance(app).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
