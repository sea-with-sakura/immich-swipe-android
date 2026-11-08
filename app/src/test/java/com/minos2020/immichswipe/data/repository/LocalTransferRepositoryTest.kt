package com.minos2020.immichswipe.data.repository

import com.minos2020.immichswipe.core.SessionConfig
import com.minos2020.immichswipe.data.api.*
import com.minos2020.immichswipe.domain.model.Album
import com.minos2020.immichswipe.feature.local.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class LocalTransferRepositoryTest {
    private val media = mockk<LocalMediaRepository>(relaxed = true)
    private val api = mockk<ImmichApi>()
    private val config = SessionConfig("https://example.test", "key", "user")
    private val repo = LocalTransferRepository(media, api, config)
    private val photo = LocalMedia("content://media/external/images/media/1", "photo.jpg", "image/jpeg", 100, 1700000000000, 1700000000000)
    private val checksum = "W6ph5Mm5Pz8GgiULbPgzG37mj9g="
    private val album = Album("album", "Family", assetCount = 0, albumThumbnailAssetId = null)
    @Before fun setup() {
        coEvery { media.unchanged(photo) } returns true
        coEvery { media.checksum(photo) } returns checksum
        every { media.receipt(any(), photo) } returns null
        every { media.deviceId() } returns "device"
        coEvery { api.uploadLocalAsset(any(), any()) } returns LocalUploadResponse("remote", "created")
        coEvery { api.verifyLocalUpload("remote") } returns LocalUploadVerification("remote", checksum, ownerId = "user")
        coEvery { api.addAssetsToAlbum("album", any()) } returns listOf(AlbumAssetResult("remote", true))
        coEvery { api.getAlbumsForAsset("remote") } returns listOf(album)
    }
    @Test fun `verified content in target album permits a deletion receipt`() = runTest {
        val receipt = repo.uploadToAlbum(photo, "album") {}
        assertEquals(LocalUploadReceipt("remote", checksum, "album"), receipt)
        repo.verifyBeforeDeletion(photo, receipt)
    }
    @Test fun `a different cloud checksum never permits deletion or album mutation`() = runTest {
        coEvery { api.verifyLocalUpload("remote") } returns LocalUploadVerification("remote", "different", ownerId = "user")
        assertTrue(runCatching { repo.uploadToAlbum(photo, "album") {} }.isFailure)
        coVerify(exactly = 0) { api.addAssetsToAlbum(any(), any()) }
    }
    @Test fun `denied album addition retains original despite successful upload`() = runTest {
        coEvery { api.addAssetsToAlbum("album", any()) } returns listOf(AlbumAssetResult("remote", false, "no_permission"))
        assertTrue(runCatching { repo.uploadToAlbum(photo, "album") {} }.isFailure)
    }
    @Test fun `missing album membership cannot authorize deletion`() = runTest {
        coEvery { api.getAlbumsForAsset("remote") } returns emptyList()
        assertTrue(runCatching { repo.uploadToAlbum(photo, "album") {} }.isFailure)
    }
    @Test fun `source modified during upload is retained`() = runTest {
        coEvery { media.checksum(photo) } returnsMany listOf(checksum, "changed")
        assertTrue(runCatching { repo.uploadToAlbum(photo, "album") {} }.isFailure)
    }
    @Test fun `retry reuses persisted upload and still checks its content and membership`() = runTest {
        every { media.receipt(any(), photo) } returns LocalUploadReceipt("remote", checksum, "album")
        coEvery { api.addAssetsToAlbum("album", any()) } returns listOf(AlbumAssetResult("remote", false, "duplicate"))
        assertEquals("remote", repo.uploadToAlbum(photo, "album") {}.assetId)
        coVerify(exactly = 0) { api.uploadLocalAsset(any(), any()) }
        coVerify { api.verifyLocalUpload("remote"); api.getAlbumsForAsset("remote") }
    }
    @Test fun `trashed or offline cloud copy cannot authorize deletion`() = runTest {
        coEvery { api.verifyLocalUpload("remote") } returns LocalUploadVerification("remote", checksum, isTrashed = true, ownerId = "user")
        assertTrue(runCatching { repo.uploadToAlbum(photo, "album") {} }.isFailure)
        coEvery { api.verifyLocalUpload("remote") } returns LocalUploadVerification("remote", checksum, isOffline = true, ownerId = "user")
        assertTrue(runCatching { repo.uploadToAlbum(photo, "album") {} }.isFailure)
    }
    @Test fun `legacy server missing device fields retries with compatible metadata`() = runTest {
        var calls = 0
        coEvery { api.uploadLocalAsset(any(), any()) } coAnswers {
            if (calls++ == 0) {
                assertFalse(firstArg<Map<String, okhttp3.RequestBody>>().containsKey("deviceId"))
                throw HttpException(Response.error<Any>(400, "deviceId must be a string".toResponseBody()))
            }
            assertTrue(firstArg<Map<String, okhttp3.RequestBody>>().keys.containsAll(listOf("deviceId", "deviceAssetId")))
            LocalUploadResponse("remote", "created")
        }
        assertEquals("remote", repo.uploadToAlbum(photo, "album") {}.assetId)
        assertEquals(2, calls)
    }
    @Test fun `a cloud copy removed after upload fails the final deletion gate`() = runTest {
        val receipt = repo.uploadToAlbum(photo, "album") {}
        coEvery { api.getAlbumsForAsset("remote") } returns emptyList()
        assertTrue(runCatching { repo.verifyBeforeDeletion(photo, receipt) }.isFailure)
    }
    @Test fun `shared media owned by another user cannot authorize local deletion`() = runTest {
        coEvery { api.verifyLocalUpload("remote") } returns LocalUploadVerification("remote", checksum, ownerId = "another-user")
        assertTrue(runCatching { repo.uploadToAlbum(photo, "album") {} }.isFailure)
    }

}
