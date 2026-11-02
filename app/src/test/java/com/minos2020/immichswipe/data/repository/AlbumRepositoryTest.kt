package com.minos2020.immichswipe.data.repository

import com.minos2020.immichswipe.core.SessionManager
import com.minos2020.immichswipe.data.api.AlbumAssetResult
import com.minos2020.immichswipe.data.api.ImmichApi
import com.minos2020.immichswipe.data.local.dao.AlbumAssetDao
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AlbumRepositoryTest {
    private val api = mockk<ImmichApi>()
    private val dao = mockk<AlbumAssetDao>(relaxed = true)
    private val repository = AlbumRepository(dao)

    @Before
    fun setup() {
        mockkObject(SessionManager)
        every { SessionManager.api } returns api
    }

    @After
    fun tearDown() = unmockkObject(SessionManager)

    @Test
    fun `HTTP 200 with denied asset is not treated as successful`() = runTest {
        coEvery { api.addAssetsToAlbum(any(), any()) } returns listOf(AlbumAssetResult("photo", false, "no_permission"))
        val result = runCatching { repository.addAssetsToAlbum("album", listOf("photo"), "user") }
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.insertAlbumAssets(any()) }
    }

    @Test
    fun `Missing per asset result is not treated as successful`() = runTest {
        coEvery { api.addAssetsToAlbum(any(), any()) } returns emptyList()
        assertTrue(runCatching { repository.addAssetsToAlbum("album", listOf("photo"), "user") }.isFailure)
    }

    @Test
    fun `Already in album counts as success and updates local membership`() = runTest {
        coEvery { api.addAssetsToAlbum(any(), any()) } returns listOf(AlbumAssetResult("photo", false, "duplicate"))
        repository.addAssetsToAlbum("album", listOf("photo"), "user")
        coVerify(exactly = 1) { dao.insertAlbumAssets(match { it.single().assetId == "photo" && it.single().albumId == "album" }) }
    }
    @Test
    fun `Partial additions update cache only for confirmed memberships`() = runTest {
        coEvery { api.addAssetsToAlbum(any(), any()) } returns listOf(
            AlbumAssetResult("added", true), AlbumAssetResult("existing", false, "duplicate"),
            AlbumAssetResult("denied", false, "no_permission"))
        val result = repository.assignAssetsToAlbum("album", listOf("added", "existing", "denied", "missing"), "user")
        assertEquals(setOf("added"), result.changedIds)
        assertEquals(setOf("existing"), result.existingIds)
        assertEquals(setOf("denied", "missing"), result.failedIds)
        coVerify { dao.insertAlbumAssets(match { rows -> rows.map { it.assetId }.toSet() == setOf("added", "existing") }) }
    }

    @Test
    fun `Undo removes album membership only and reports partial failures for retry`() = runTest {
        coEvery { api.removeAssetsFromAlbum(any(), any()) } returns listOf(
            AlbumAssetResult("removed", true), AlbumAssetResult("gone", false, "not_found"),
            AlbumAssetResult("denied", false, "no_permission"))
        val result = repository.removeAssetsFromAlbum("album", listOf("removed", "gone", "denied"), "user")
        assertEquals(setOf("removed", "gone"), result.completedIds)
        assertEquals(setOf("denied"), result.failedIds)
        coVerify { dao.removeAlbumAssets("album", match { it.toSet() == setOf("removed", "gone") }, "user") }
        coVerify(exactly = 0) { api.deleteAssets(any()) }
    }

}
