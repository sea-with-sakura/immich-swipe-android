package com.minos2020.immichswipe.feature.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minos2020.immichswipe.data.repository.AlbumRepository
import com.minos2020.immichswipe.data.repository.LocalMediaRepository
import com.minos2020.immichswipe.data.repository.LocalTransferRepository
import com.minos2020.immichswipe.domain.model.Album
import kotlinx.coroutines.CancellationException
import com.minos2020.immichswipe.feature.local.transfer.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class LocalOrganizerViewModel(
    private val media: LocalMediaRepository,
    private val transfer: LocalTransferRepository,
    private val albums: AlbumRepository,
    private val queue: LocalTransferQueue
) : ViewModel() {
    private val mutable = MutableStateFlow(LocalUiState())
    val uiState = mutable.asStateFlow()
    private var tasks = emptyList<LocalTransferTask>()
    init {
        queue.resume()
        viewModelScope.launch {
            queue.tasks.collect { rows ->
                val albumChanged = rows.any { row -> !row.deleteOnly && row.state in setOf(TransferState.CLEANUP, TransferState.DONE) &&
                    tasks.firstOrNull { it.key == row.key }?.state != row.state }
                tasks = rows
                val own = rows.filter { it.account == queue.account }
                val hidden = rows.filter { it.state in TransferState.active || it.state == TransferState.DONE }.map { it.key }.toSet()
                val done = rows.filter { it.state == TransferState.DONE }.map { it.key }.toSet()
                mutable.update { state -> state.copy(queuedKeys = hidden,
                    items = state.items.filterNot { it.key in done },
                    backgroundCount = own.count { it.state in setOf(TransferState.QUEUED, TransferState.UPLOADING) },
                    cleanupCount = own.count { it.state == TransferState.CLEANUP },
                    backgroundFailed = own.count { it.state == TransferState.FAILED },
                    albumRefreshVersion = state.albumRefreshVersion + if (albumChanged) 1 else 0) }
            }
        }
    }

    fun refresh() {
        if (mutable.value.busy || mutable.value.loading) return
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val photos = media.load(); val kept = media.kept()
                queue.prune(photos.map { it.key }.toSet())
                mutable.update { state -> state.copy(items = photos, kept = kept, loading = false,
                    selected = state.selected.intersect(photos.map { it.key }.toSet())) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(loading = false, message = LocalMessage.LOAD_FAILED) }
            }
        }
    }
    fun toggle(key: String) { if (!mutable.value.busy) mutable.update { it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key) } }
    fun select(keys: Set<String>) { if (!mutable.value.busy) mutable.update { it.copy(selected = if (it.selected.containsAll(keys)) it.selected - keys else it.selected + keys) } }
    fun clearSelection() { if (!mutable.value.busy) mutable.update { it.copy(selected = emptySet()) } }
    fun toggleSort() { if (!mutable.value.busy) mutable.update { it.copy(oldestFirst = !it.oldestFirst, currentIndex = 0) } }
    fun toggleKept() { if (!mutable.value.busy) mutable.update { it.copy(showKept = !it.showKept, selected = emptySet(), currentIndex = 0, refreshVersion = it.refreshVersion + 1) } }
    fun next() { if (!mutable.value.busy) mutable.update { it.copy(currentIndex = if (it.visible.isEmpty()) 0 else (it.currentIndex + 1) % it.visible.size) } }
    fun showCreate(show: Boolean) { if (!mutable.value.busy) mutable.update { it.copy(showCreateAlbum = show) } }

    fun keep(selectionMode: Boolean) {
        val state = mutable.value; if (state.busy) return
        val keys = state.targets(selectionMode).map { it.key }.toSet(); if (keys.isEmpty()) return
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                media.keep(keys, !state.showKept)
                mutable.update { it.copy(kept = if (state.showKept) it.kept - keys else it.kept + keys, selected = it.selected - keys,
                    currentIndex = 0, loading = false, undoKept = if (state.showKept) it.undoKept else (it.undoKept + listOf(keys)).takeLast(30), message = LocalMessage.KEPT) }
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(loading = false, message = LocalMessage.LOAD_FAILED) } }
        }
    }
    fun undoKeep() {
        val state = mutable.value; if (state.busy) return
        val keys = state.undoKept.lastOrNull() ?: return
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                media.keep(keys, false)
                mutable.update { it.copy(kept = it.kept - keys, undoKept = it.undoKept.dropLast(1), currentIndex = 0,
                    loading = false, refreshVersion = it.refreshVersion + 1, message = null) }
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(loading = false, message = LocalMessage.LOAD_FAILED) } }
        }
    }
    fun delete(selectionMode: Boolean, background: Boolean = true) {
        val state = mutable.value; if (state.busy) return
        val items = state.targets(selectionMode); if (items.isEmpty()) return
        if (!background) {
            mutable.update { it.copy(pendingDeletion = LocalDeletion(UUID.randomUUID().toString(), items, false), message = null) }
            return
        }
        enqueue(items, "", true)
    }
    private fun enqueue(photos: List<LocalMedia>, albumId: String, deleteOnly: Boolean = false) {
        mutable.update { it.copy(uploading = true, message = null) }
        viewModelScope.launch {
            try {
                queue.enqueue(photos, albumId, deleteOnly)
                mutable.update { it.copy(uploading = false, selected = it.selected - photos.map { photo -> photo.key }.toSet(),
                    currentIndex = 0, refreshVersion = it.refreshVersion + 1) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(uploading = false, message = LocalMessage.TRANSFER_FAILED) }
            }
        }
    }
    fun upload(album: Album?, selectionMode: Boolean, newName: String? = null) {
        val state = mutable.value; if (state.busy) return
        val photos = state.targets(selectionMode); if (photos.isEmpty() || (album == null && newName.isNullOrBlank())) return
        if (album != null) { enqueue(photos, album.id); return }
        mutable.update { it.copy(uploading = true, message = null) }
        viewModelScope.launch {
            try {
                val target = albums.createAlbum(checkNotNull(newName))
                queue.enqueue(photos, target.id)
                mutable.update { it.copy(uploading = false, showCreateAlbum = false,
                    selected = it.selected - photos.map { photo -> photo.key }.toSet(), currentIndex = 0,
                    refreshVersion = it.refreshVersion + 1, albumRefreshVersion = it.albumRefreshVersion + 1) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(uploading = false, message = LocalMessage.TRANSFER_FAILED) }
            }
        }
    }
    fun cancelUpload() { viewModelScope.launch { queue.cancel() } }
    fun retryBackground() { viewModelScope.launch { queue.retry() } }
    fun resumeBackground() { queue.resume() }
    fun cleanup() {
        if (mutable.value.busy) return
        val photos = tasks.filter { it.account == queue.account && it.state == TransferState.CLEANUP }.map { it.photo() }
        if (photos.isNotEmpty()) mutable.update { it.copy(pendingDeletion = LocalDeletion(UUID.randomUUID().toString(), photos, true)) }
    }
    fun cancelDeletion() { deletionFinished(emptySet(), true) }
    suspend fun validateDeletionChunk(items: List<LocalMedia>): List<LocalMedia> {
        val request = mutable.value.pendingDeletion ?: return emptyList()
        val validated = items.filter { photo ->
            try {
                if (request.afterUpload) {
                    val task = tasks.firstOrNull { it.key == photo.key && it.account == queue.account && it.state == TransferState.CLEANUP }
                    checkNotNull(task)
                    if (task.deleteOnly) check(media.unchanged(photo))
                    else transfer.verifyBeforeDeletion(photo, checkNotNull(media.receipt(queue.account, photo)))
                } else check(media.unchanged(photo))
                true
            } catch (e: Exception) { if (e is CancellationException) throw e; false }
        }
        return validated.takeIf { mutable.value.pendingDeletion?.id == request.id } ?: emptyList()
    }
    fun deletionFinished(deletedKeys: Set<String>, cancelled: Boolean) {
        val request = mutable.value.pendingDeletion ?: return
        if (request.afterUpload) viewModelScope.launch { queue.completed(deletedKeys) }
        mutable.update { state -> state.copy(items = state.items.filterNot { it.key in deletedKeys },
            selected = state.selected - deletedKeys, pendingDeletion = null, currentIndex = 0,
            undoKept = state.undoKept.map { it - deletedKeys }.filter { it.isNotEmpty() },
            refreshVersion = if (deletedKeys.isNotEmpty()) state.refreshVersion + 1 else state.refreshVersion,
            message = when {
                cancelled || deletedKeys.size != request.items.size -> if (request.afterUpload) LocalMessage.UPLOADED_NOT_DELETED else LocalMessage.DELETE_FAILED
                state.failedCount > 0 -> LocalMessage.TRANSFER_FAILED
                else -> LocalMessage.DELETED
            }) }
        refresh()
    }
}
