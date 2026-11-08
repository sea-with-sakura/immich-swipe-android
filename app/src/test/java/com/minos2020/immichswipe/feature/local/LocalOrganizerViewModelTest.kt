package com.minos2020.immichswipe.feature.local

import com.minos2020.immichswipe.MainDispatcherRule
import com.minos2020.immichswipe.core.AppLogger
import com.minos2020.immichswipe.data.repository.*
import com.minos2020.immichswipe.domain.model.Album
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalOrganizerViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val media = mockk<LocalMediaRepository>(relaxed = true)
    private val transfer = mockk<LocalTransferRepository>(relaxed = true)
    private val albums = mockk<AlbumRepository>()
    private val photos = (1..3).map { LocalMedia("content://media/$it", "$it.jpg", "image/jpeg", 100, it.toLong(), it.toLong()) }
    private val album = Album("album", "Family", assetCount = 0, albumThumbnailAssetId = null)
    @Before fun setup() {
        mockkObject(AppLogger); every { AppLogger.e(any(), any(), any()) } just Runs
        coEvery { media.load() } returns photos
        every { media.kept() } returns emptySet()
        coEvery { media.unchanged(any()) } returns true
        coEvery { transfer.uploadToAlbum(any(), any(), any()) } answers { LocalUploadReceipt("remote", "hash", "album") }
    }
    @After fun cleanup() = unmockkObject(AppLogger)
    private suspend fun ready(): LocalOrganizerViewModel {
        val vm = LocalOrganizerViewModel(media, transfer, albums)
        vm.refresh(); dispatcher.testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }
    @Test fun `only verified uploads enter platform deletion and failures stay selected`() = runTest {
        val vm = ready()
        coEvery { transfer.uploadToAlbum(photos[1], any(), any()) } throws IllegalStateException("upload failed")
        vm.select(photos.map { it.key }.toSet()); vm.upload(album, true); testScheduler.advanceUntilIdle()
        assertEquals(setOf(photos[0].key, photos[2].key), vm.uiState.value.pendingDeletion!!.items.map { it.key }.toSet())
        assertEquals(3, vm.uiState.value.items.size)
        assertEquals(1, vm.uiState.value.failedCount)
        vm.deletionFinished(setOf(photos[0].key, photos[2].key), false)
        // The fake inventory has not actually been deleted, so verify the immediate state before refresh completes.
        assertEquals(setOf(photos[1].key), vm.uiState.value.selected)
    }
    @Test fun `failed final verification cannot open a delete confirmation`() = runTest {
        val vm = ready()
        coEvery { transfer.verifyBeforeDeletion(any(), any()) } throws IllegalStateException("cloud file unavailable")
        vm.select(photos.map { it.key }.toSet()); vm.upload(album, true); testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingDeletion)
        assertEquals(3, vm.uiState.value.items.size)
        assertEquals(3, vm.uiState.value.selected.size)
    }
    @Test fun `cancelled Android confirmation retains originals and selection for retry`() = runTest {
        val vm = ready(); vm.select(setOf(photos[0].key)); vm.upload(album, true); testScheduler.advanceUntilIdle()
        vm.deletionFinished(emptySet(), true); testScheduler.advanceUntilIdle()
        assertEquals(photos.size, vm.uiState.value.items.size)
        assertEquals(setOf(photos[0].key), vm.uiState.value.selected)
        assertEquals(LocalMessage.UPLOADED_NOT_DELETED, vm.uiState.value.message)
    }
    @Test fun `keep is local only and can be undone without cloud mutations`() = runTest {
        val vm = ready(); vm.select(setOf(photos[0].key)); vm.keep(true); testScheduler.advanceUntilIdle()
        assertTrue(photos[0].key in vm.uiState.value.kept)
        vm.undoKeep(); testScheduler.advanceUntilIdle()
        assertFalse(photos[0].key in vm.uiState.value.kept)
        coVerify(exactly = 0) { transfer.uploadToAlbum(any(), any(), any()) }
    }
    @Test fun `rapid upload taps cannot duplicate a pending transfer`() = runTest {
        val vm = ready(); val pending = CompletableDeferred<LocalUploadReceipt>()
        coEvery { transfer.uploadToAlbum(any(), any(), any()) } coAnswers { pending.await() }
        vm.select(setOf(photos[0].key)); vm.upload(album, true); runCurrent(); vm.upload(album, true); runCurrent()
        coVerify(exactly = 1) { transfer.uploadToAlbum(any(), any(), any()) }
        pending.complete(LocalUploadReceipt("remote", "hash", "album")); testScheduler.advanceUntilIdle()
    }
    @Test fun `cancelled upload never queues deletion`() = runTest {
        val vm = ready(); val pending = CompletableDeferred<LocalUploadReceipt>()
        coEvery { transfer.uploadToAlbum(any(), any(), any()) } coAnswers { pending.await() }
        vm.select(setOf(photos[0].key)); vm.upload(album, true); runCurrent(); vm.cancelUpload(); testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.uploading)
        assertEquals(photos.size, vm.uiState.value.items.size)
    }
}
