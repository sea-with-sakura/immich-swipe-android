package com.minos2020.immichswipe.data.repository

import com.minos2020.immichswipe.core.SessionManager
import com.minos2020.immichswipe.data.api.AddAssetsToAlbumRequest
import com.minos2020.immichswipe.data.api.CreateAlbumRequest
import com.minos2020.immichswipe.data.api.ImmichApi
import com.minos2020.immichswipe.data.api.SearchAssetsRequest
import com.minos2020.immichswipe.data.local.dao.AlbumAssetDao
import com.minos2020.immichswipe.domain.model.Album
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Repository gérant la récupération des albums depuis le serveur Immich.
 */
class AlbumRepository(
    private val albumAssetDao: AlbumAssetDao? = null
) {
    private val api: ImmichApi get() = SessionManager.api ?: throw IllegalStateException("API not initialized")

    suspend fun getMappingCount(albumId: String, userId: String): Int {
        return albumAssetDao?.getMappingCountForAlbum(albumId, userId) ?: 0
    }

    suspend fun getAlbums(): List<Album> = api.getAlbums()

    suspend fun createAlbum(name: String): Album {
        require(name.isNotBlank())
        return api.createAlbum(CreateAlbumRequest(name.trim()))
    }

    /**
     * Rafraîchit la liste des albums depuis le serveur.
     * @param includeArchived Si vrai, inclut les photos archivées dans le compte total.
     *                        Si faux, soustrait les archives du compte total via search/statistics.
     */
    suspend fun refreshAlbums(includeArchived: Boolean = false): List<Album> {
        val albums = api.getAlbums()
        if (includeArchived) return albums

        return coroutineScope {
            albums.map { album ->
                async {
                    val archiveCount = try {
                        api.getSearchStatistics(
                            SearchAssetsRequest(albumIds = listOf(album.id), visibility = "archive")
                        ).total
                    } catch (_: Exception) {
                        0
                    }
                    album.copy(assetCount = (album.assetCount - archiveCount).coerceAtLeast(0))
                }
            }.awaitAll()
        }
    }

    /**
     * Ajoute des assets à un album.
     */
    suspend fun addAssetsToAlbum(albumId: String, assetIds: List<String>, userId: String? = null) {
        check(assignAssetsToAlbum(albumId, assetIds, userId).failedIds.isEmpty()) {
            "Immich did not add all requested assets to the album"
        }
    }

    /** Preserve individual results: a bulk request may succeed for only some photos. */
    suspend fun assignAssetsToAlbum(albumId: String, assetIds: List<String>, userId: String? = null): AlbumMutationResult {
        val results = api.addAssetsToAlbum(albumId, AddAssetsToAlbumRequest(assetIds)).associateBy { it.id }
        val added = assetIds.filter { id -> results[id]?.success == true }.toSet()
        val existing = assetIds.filter { id -> results[id]?.error == "duplicate" }.toSet() - added
        val completed = added + existing
        if (albumAssetDao != null && userId != null && completed.isNotEmpty()) {
            albumAssetDao.removeAlbumAssets(Album.VIRTUAL_ORPHANS_ID, completed.toList(), userId)
            albumAssetDao.insertAlbumAssets(completed.map { assetId ->
                com.minos2020.immichswipe.data.local.entity.AlbumAssetEntity(albumId, assetId, userId, false)
            })
        }
        return AlbumMutationResult(added, existing, assetIds.toSet() - completed)
    }

    suspend fun getAlbumIdsForAsset(assetId: String): Set<String> = api.getAlbumsForAsset(assetId).map { it.id }.toSet()

    /** Undo only removes album membership; it never deletes the underlying photos. */
    suspend fun removeAssetsFromAlbum(albumId: String, assetIds: List<String>, userId: String): AlbumMutationResult {
        val results = api.removeAssetsFromAlbum(albumId, AddAssetsToAlbumRequest(assetIds)).associateBy { it.id }
        val removed = assetIds.filter { id -> results[id]?.let { it.success || it.error == "not_found" } == true }.toSet()
        if (removed.isNotEmpty()) albumAssetDao?.removeAlbumAssets(albumId, removed.toList(), userId)
        return AlbumMutationResult(removed, emptySet(), assetIds.toSet() - removed)
    }
}

data class AlbumMutationResult(
    val changedIds: Set<String>,
    val existingIds: Set<String> = emptySet(),
    val failedIds: Set<String> = emptySet()
) {
    val completedIds: Set<String> get() = changedIds + existingIds
}
