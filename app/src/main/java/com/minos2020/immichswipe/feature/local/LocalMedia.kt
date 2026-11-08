package com.minos2020.immichswipe.feature.local

import java.time.Instant
import java.time.ZoneId

data class LocalMedia(
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val takenAt: Long,
    val modifiedAt: Long
) {
    val key: String get() = "$uri:$size:$modifiedAt"
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val date: String get() = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

data class LocalUploadReceipt(val assetId: String, val checksum: String, val albumId: String = "")
data class LocalDeletion(val id: String, val items: List<LocalMedia>, val afterUpload: Boolean)

enum class LocalMessage { LOAD_FAILED, TRANSFER_FAILED, UPLOADED_NOT_DELETED, DELETE_FAILED, DELETED, KEPT, CANCELLED }

data class LocalUiState(
    val items: List<LocalMedia> = emptyList(),
    val kept: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
    val currentIndex: Int = 0,
    val showKept: Boolean = false,
    val oldestFirst: Boolean = false,
    val loading: Boolean = false,
    val uploading: Boolean = false,
    val progress: Float = 0f,
    val queuedKeys: Set<String> = emptySet(),
    val backgroundCount: Int = 0,
    val cleanupCount: Int = 0,
    val backgroundFailed: Int = 0,
    val uploadIndex: Int = 0,
    val uploadTotal: Int = 0,
    val pendingDeletion: LocalDeletion? = null,
    val message: LocalMessage? = null,
    val failedCount: Int = 0,
    val undoKept: List<Set<String>> = emptyList(),
    val refreshVersion: Int = 0,
    val albumRefreshVersion: Int = 0,
    val showCreateAlbum: Boolean = false
) {
    val busy: Boolean get() = loading || uploading || pendingDeletion != null
    val visible: List<LocalMedia> get() = items.filter { it.key !in queuedKeys && (it.key in kept) == showKept }
        .let { if (oldestFirst) it.sortedBy { photo -> photo.takenAt } else it.sortedByDescending { photo -> photo.takenAt } }
    val current: LocalMedia? get() = visible.getOrNull(currentIndex)
    fun targets(selectionMode: Boolean): List<LocalMedia> = if (selectionMode) visible.filter { it.key in selected } else listOfNotNull(current)
}
