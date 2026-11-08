package com.minos2020.immichswipe.feature.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minos2020.immichswipe.core.AppLogger
import com.minos2020.immichswipe.data.repository.AlbumRepository
import com.minos2020.immichswipe.data.repository.LocalMediaRepository
import com.minos2020.immichswipe.data.repository.LocalTransferRepository
import com.minos2020.immichswipe.domain.model.Album
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class LocalOrganizerViewModel(
    private val media: LocalMediaRepository,
    private val transfer: LocalTransferRepository,
    private val albums: AlbumRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(LocalUiState())
    val uiState = mutable.asStateFlow()
    private var job: Job? = null
    private val deletionProofs = mutableMapOf<String, LocalUploadReceipt>()

    fun refresh() {
        if (mutable.value.busy || mutable.value.loading) return
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val photos = media.load(); val kept = media.kept()
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
    fun delete(selectionMode: Boolean) {
        val state = mutable.value; if (state.busy) return
        val items = state.targets(selectionMode); if (items.isEmpty()) return
        mutable.update { it.copy(pendingDeletion = LocalDeletion(UUID.randomUUID().toString(), items, false), message = null) }
    }
    fun upload(album: Album?, selectionMode: Boolean, newName: String? = null) {
        val state = mutable.value; if (state.busy) return
        val photos = state.targets(selectionMode); if (photos.isEmpty() || (album == null && newName.isNullOrBlank())) return
        deletionProofs.clear()
        mutable.update { it.copy(uploading = true, message = null, failedCount = 0, uploadIndex = 0, uploadTotal = photos.size, progress = 0f) }
        job = viewModelScope.launch {
            val verified = mutableListOf<LocalMedia>()
            val failed = mutableSetOf<String>()
            try {
                val target = album ?: albums.createAlbum(checkNotNull(newName))
                if (album == null) mutable.update { it.copy(showCreateAlbum = false, albumRefreshVersion = it.albumRefreshVersion + 1) }
                for ((index, photo) in photos.withIndex()) {
                    mutable.update { it.copy(uploadIndex = index + 1, progress = 0f) }
                    try {
                        val receipt = transfer.uploadToAlbum(photo, target.id) { value -> mutable.update { it.copy(progress = value) } }
                        transfer.verifyBeforeDeletion(photo, receipt)
                        deletionProofs[photo.key] = receipt
                        verified += photo
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLogger.e("Local", "Upload or album verification failed; source retained", e)
                        failed += photo.key
                    }
                }
                mutable.update { it.copy(uploading = false, showCreateAlbum = false,
                    failedCount = failed.size, message = LocalMessage.TRANSFER_FAILED.takeIf { failed.isNotEmpty() },
                    pendingDeletion = if (verified.isEmpty()) null else LocalDeletion(UUID.randomUUID().toString(), verified, true),
                    albumRefreshVersion = it.albumRefreshVersion + 1) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(uploading = false, message = LocalMessage.TRANSFER_FAILED, failedCount = photos.size) }
            }
        }
    }
    fun cancelUpload() {
        job?.cancel()
        deletionProofs.clear()
        mutable.update { it.copy(uploading = false, pendingDeletion = null, message = LocalMessage.CANCELLED, albumRefreshVersion = it.albumRefreshVersion + 1) }
    }
    fun cancelDeletion() { deletionFinished(emptySet(), true) }
    suspend fun validateDeletionChunk(items: List<LocalMedia>): List<LocalMedia> {
        val request = mutable.value.pendingDeletion ?: return emptyList()
        val validated = items.filter { photo ->
            try {
                if (request.afterUpload) transfer.verifyBeforeDeletion(photo, checkNotNull(deletionProofs[photo.key]))
                else check(media.unchanged(photo))
                true
            } catch (e: Exception) { if (e is CancellationException) throw e; false }
        }
        return validated.takeIf { mutable.value.pendingDeletion?.id == request.id } ?: emptyList()
    }
    fun deletionFinished(deletedKeys: Set<String>, cancelled: Boolean) {
        val request = mutable.value.pendingDeletion ?: return
        request.items.forEach { deletionProofs.remove(it.key) }
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
