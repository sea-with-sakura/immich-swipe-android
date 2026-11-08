package com.minos2020.immichswipe.feature.local

import com.minos2020.immichswipe.MainDispatcherRule
import com.minos2020.immichswipe.data.repository.*
import com.minos2020.immichswipe.domain.model.Album
import com.minos2020.immichswipe.feature.local.transfer.*
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalOrganizerViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val media = mockk<LocalMediaRepository>(relaxed = true)
    private val transfer = mockk<LocalTransferRepository>(relaxed = true)
    private val albums = mockk<AlbumRepository>()
    private val queue = mockk<LocalTransferQueue>(relaxed = true)
    private val rows = MutableStateFlow(emptyList<LocalTransferTask>())
    private val photos = (1..3).map { LocalMedia("content://media/$it", "$it.jpg", "image/jpeg", 100, it.toLong(), it.toLong()) }
    private val album = Album("album", "Family", assetCount = 0, albumThumbnailAssetId = null)
    @Before fun setup() {
        coEvery { media.load() } returns photos
        every { media.kept() } returns emptySet()
        every { queue.tasks } returns rows
        every { queue.account } returns "account"
        coEvery { queue.enqueue(any(), any(), any()) } coAnswers {
            rows.value = rows.value + firstArg<List<LocalMedia>>().map {
                LocalTransferTask(it.key, "gen", "account", Gson().toJson(it), secondArg(), thirdArg())
            }
        }
    }
    private suspend fun ready(): LocalOrganizerViewModel {
        val vm = LocalOrganizerViewModel(media, transfer, albums, queue)
        vm.refresh(); dispatcher.testDispatcher.scheduler.advanceUntilIdle(); return vm
    }
    @Test fun `upload durably queues selection and immediately permits next actions`() = runTest {
        val vm = ready(); vm.select(setOf(photos[0].key)); vm.upload(album, true); testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.pendingDeletion)
        assertEquals(2, vm.uiState.value.visible.size)
        assertTrue(vm.uiState.value.selected.isEmpty())
        vm.toggle(photos[1].key); vm.upload(album, true); testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.visible.size)
        assertEquals(2, vm.uiState.value.backgroundCount)
        coVerify(exactly = 0) { transfer.uploadToAlbum(any(), any(), any()) }
    }
    @Test fun `background upload and cleanup do not block mode changes or open delete dialogs`() = runTest {
        val vm = ready(); vm.upload(album, false); testScheduler.advanceUntilIdle()
        rows.value = rows.value.map { it.copy(state = TransferState.CLEANUP) }; testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.pendingDeletion)
        assertEquals(1, vm.uiState.value.cleanupCount)
        vm.toggleSort(); assertTrue(vm.uiState.value.oldestFirst)
    }
    @Test fun `failed transfer returns the original to pending photos`() = runTest {
        val vm = ready(); vm.upload(album, false); testScheduler.advanceUntilIdle()
        rows.value = rows.value.map { it.copy(state = TransferState.FAILED) }; testScheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.visible.size)
        assertEquals(1, vm.uiState.value.backgroundFailed)
        assertNull(vm.uiState.value.pendingDeletion)
    }
    @Test fun `restored durable task stays hidden and is resumed after recreation`() = runTest {
        val vm = ready(); vm.upload(album, false); testScheduler.advanceUntilIdle()
        val restored = ready()
        assertEquals(2, restored.uiState.value.visible.size)
        assertEquals(1, restored.uiState.value.backgroundCount)
        verify(atLeast = 2) { queue.resume() }
    }
    @Test fun `keep and undo remain local without transfer work`() = runTest {
        val vm = ready(); vm.select(setOf(photos[0].key)); vm.keep(true); testScheduler.advanceUntilIdle()
        assertTrue(photos[0].key in vm.uiState.value.kept)
        vm.undoKeep(); testScheduler.advanceUntilIdle()
        assertFalse(photos[0].key in vm.uiState.value.kept)
        coVerify(exactly = 0) { queue.enqueue(any(), any(), any()) }
    }
    @Test fun `direct deletion is queued without any automatic confirmation`() = runTest {
        val vm = ready(); vm.select(setOf(photos[0].key)); vm.delete(true); testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingDeletion)
        coVerify { queue.enqueue(listOf(photos[0]), "", true) }
    }
}
