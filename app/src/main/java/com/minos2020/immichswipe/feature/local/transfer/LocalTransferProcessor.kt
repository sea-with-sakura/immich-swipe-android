package com.minos2020.immichswipe.feature.local.transfer

import com.minos2020.immichswipe.data.repository.LocalMediaRepository
import com.minos2020.immichswipe.data.repository.LocalTransferRepository
import com.minos2020.immichswipe.feature.local.LocalMedia
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** Rechecks both the account and the task generation before any local deletion. */
class LocalTransferProcessor(
    private val dao: LocalTransferDao,
    private val media: LocalMediaRepository,
    private val transfer: LocalTransferRepository,
    private val account: String,
    private val sessionValid: suspend () -> Boolean,
    private val canDelete: () -> Boolean,
    private val delete: suspend (LocalMedia) -> Boolean
) {
    suspend fun run(deleteOnlyFilter: Boolean? = null): Boolean {
        var retry = false
        val visited = mutableSetOf<String>()
        while (true) {
            val task = dao.pending(account).firstOrNull { it.key !in visited &&
                (deleteOnlyFilter == null || it.deleteOnly == deleteOnlyFilter) } ?: break
            visited += task.key
            if (task.state == TransferState.CLEANUP && !canDelete()) continue
            if (!sessionValid()) break
            suspend fun owns(): Boolean = dao.get(task.key)?.let {
                it.generation == task.generation && it.state in TransferState.active
            } == true && sessionValid()
            if (!owns()) continue
            try {
                val photo = task.photo()
                if (task.deleteOnly) {
                    check(media.unchanged(photo))
                } else {
                    val receipt = if (task.state == TransferState.CLEANUP) {
                        checkNotNull(media.receipt(account, photo))
                    } else {
                        dao.update(task.key, task.generation, TransferState.UPLOADING, task.attempts)
                        transfer.uploadToAlbum(photo, task.albumId) {}
                    }
                    if (!owns()) continue
                    transfer.verifyBeforeDeletion(photo, receipt)
                }
                if (!owns()) continue
                // Missing special permission is deferred, never an automatic dialog.
                if (!canDelete()) {
                    dao.update(task.key, task.generation, TransferState.CLEANUP, task.attempts)
                    continue
                }
                if (!owns()) continue
                val deleted = delete(photo)
                dao.update(task.key, task.generation, if (deleted) TransferState.DONE else TransferState.CLEANUP, task.attempts)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!owns()) continue
                val transient = e is IOException || (e is HttpException && (e.code() >= 500 || e.code() == 429))
                val again = transient && task.attempts < 3
                dao.update(task.key, task.generation, if (again) TransferState.QUEUED else TransferState.FAILED, task.attempts + 1)
                retry = retry || again
            }
        }
        return retry
    }
}
