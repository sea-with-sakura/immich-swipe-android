package com.minos2020.immichswipe.feature.swipe

import com.minos2020.immichswipe.MainDispatcherRule
import com.minos2020.immichswipe.data.repository.AssetRepository
import com.minos2020.immichswipe.data.repository.SessionRepository
import com.minos2020.immichswipe.data.repository.SwipeDecisionRepository
import com.minos2020.immichswipe.data.repository.AlbumMutationResult
import com.minos2020.immichswipe.data.repository.AlbumRepository
import com.minos2020.immichswipe.domain.model.Album
import com.minos2020.immichswipe.domain.model.Asset
import com.minos2020.immichswipe.data.local.entity.SwipeDecisionEntity
import com.minos2020.immichswipe.core.SessionConfig
import com.minos2020.immichswipe.core.SwipeSortOrder
import com.minos2020.immichswipe.core.SwipeSortPriority
import com.minos2020.immichswipe.core.CardDisplayMode
import com.minos2020.immichswipe.core.IconPosition
import com.minos2020.immichswipe.core.PlaybackBehavior
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class SwipeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val assetRepository: AssetRepository = mockk(relaxed = true)
    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val swipeDecisionRepository: SwipeDecisionRepository = mockk(relaxed = true)
    private val albumRepository: AlbumRepository = mockk(relaxed = true)
    
    private val testAlbum = Album(id = "album1", albumName = "Test Album", assetCount = 10, albumThumbnailAssetId = null)
    private val testUserId = "user1"

    // Sources de données stables pour les Mocks
    private val sessionConfigFlow = MutableStateFlow<SessionConfig?>(SessionConfig("http://url", "key", testUserId))
    private val includeArchivedFlow = MutableStateFlow(false)
    private val swipeSortOrderFlow = MutableStateFlow(SwipeSortOrder.DATE_DESC)
    private val swipeSortPriorityFlow = MutableStateFlow(SwipeSortPriority.NONE)
    private val decisionsFlow = MutableStateFlow<List<SwipeDecisionEntity>>(emptyList())
    private val assetsFlow = MutableStateFlow<List<Asset>>(emptyList())

    private val destination = Album("destination", "Family", assetCount = 0, albumThumbnailAssetId = null)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } answers { println("DEBUG: ${it.invocation.args[0]}: ${it.invocation.args[1]}"); 0 }
        every { Log.i(any(), any()) } answers { println("INFO: ${it.invocation.args[0]}: ${it.invocation.args[1]}"); 0 }
        every { Log.w(any<String>(), any<String>()) } answers { println("WARN: ${it.invocation.args[0]}: ${it.invocation.args[1]}"); 0 }
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } answers { println("WARN: ${it.invocation.args[0]}: ${it.invocation.args[1]}"); 0 }
        every { Log.e(any(), any(), any()) } answers { 
            val t = it.invocation.args[2] as? Throwable
            println("ERROR: ${it.invocation.args[0]}: ${it.invocation.args[1]} - ${t?.message}")
            0 
        }
        every { Log.e(any(), any()) } answers { println("ERROR: ${it.invocation.args[0]}: ${it.invocation.args[1]}"); 0 }

        coEvery { albumRepository.getAlbums() } returns listOf(destination)
        coEvery { sessionRepository.getAlbumOrder(any()) } returns emptyList()
        coEvery { albumRepository.getAlbumIdsForAsset(any()) } returns emptySet()
        coEvery { albumRepository.assignAssetsToAlbum(any(), any(), any()) } answers { AlbumMutationResult(secondArg<List<String>>().toSet()) }
        coEvery { albumRepository.removeAssetsFromAlbum(any(), any(), any()) } answers { AlbumMutationResult(secondArg<List<String>>().toSet()) }
        coEvery { assetRepository.getAssetDetail(any(), any()) } answers { createAsset(firstArg()) }

        every { sessionRepository.sessionConfig } returns sessionConfigFlow
        every { sessionRepository.includeArchived } returns includeArchivedFlow
        every { sessionRepository.swipeSortOrder } returns swipeSortOrderFlow
        every { sessionRepository.swipeSortPriority } returns swipeSortPriorityFlow
        every { sessionRepository.defaultCardDisplayMode } returns MutableStateFlow(CardDisplayMode.FILL)
        every { sessionRepository.swipeInverted } returns MutableStateFlow(false)
        every { sessionRepository.skipLifespanDays } returns MutableStateFlow(30L)
        
        every { sessionRepository.showFavoriteButton } returns MutableStateFlow(true)
        every { sessionRepository.showArchiveButton } returns MutableStateFlow(true)
        every { sessionRepository.showLockButton } returns MutableStateFlow(true)
        every { sessionRepository.showKeepDeleteButtons } returns MutableStateFlow(true)
        every { sessionRepository.showAddToAlbumButton } returns MutableStateFlow(true)
        every { sessionRepository.showShareButton } returns MutableStateFlow(true)
        every { sessionRepository.showFullscreenButton } returns MutableStateFlow(true)
        every { sessionRepository.showImmichButton } returns MutableStateFlow(true)
        every { sessionRepository.showCardDisplayButton } returns MutableStateFlow(true)
        every { sessionRepository.showMuteButton } returns MutableStateFlow(true)
        every { sessionRepository.showRotateButton } returns MutableStateFlow(true)
        every { sessionRepository.syncRotate } returns MutableStateFlow(true)
        every { sessionRepository.autoNextOnFav } returns MutableStateFlow(true)
        
        every { sessionRepository.fullscreenButtonPosition } returns MutableStateFlow(IconPosition.TOP_RIGHT)
        every { sessionRepository.immichButtonPosition } returns MutableStateFlow(IconPosition.TOP_LEFT)
        every { sessionRepository.cardDisplayButtonPosition } returns MutableStateFlow(IconPosition.TOP_RIGHT)
        every { sessionRepository.muteButtonPosition } returns MutableStateFlow(IconPosition.BOTTOM_RIGHT)
        every { sessionRepository.shareButtonPosition } returns MutableStateFlow(IconPosition.TOP_LEFT)
        every { sessionRepository.rotateButtonPosition } returns MutableStateFlow(IconPosition.BOTTOM_RIGHT)
        every { sessionRepository.playbackBehavior } returns MutableStateFlow(PlaybackBehavior.PAUSE_OTHERS)

        every { swipeDecisionRepository.getDecisionsForAlbum(testAlbum.id, testUserId) } returns decisionsFlow
        every { assetRepository.getAssetsByAlbum(any(), any(), any(), any(), any()) } returns assetsFlow
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun createAsset(id: String) = Asset(
        id = id,
        ownerId = "owner1",
        fileCreatedAt = "2024-01-01",
        type = "IMAGE",
        isFavorite = false,
        isArchived = false,
        isLocked = false,
        isTrashed = false,
        originalFileName = "file_$id",
        exifInfo = null,
        isEdited = false
    )

    @Test
    fun `Timeline size must equal Total assets minus Synced assets`() = runTest {
        // GIVEN : 10 photos au total, 5 traitées dont 3 synchronisées
        val allAssets = (1..10).map { createAsset("photo_$it") }
        assetsFlow.value = allAssets
        
        decisionsFlow.value = listOf(
            SwipeDecisionEntity("photo_1", testAlbum.id, testUserId, "KEEP", isSynced = true),
            SwipeDecisionEntity("photo_2", testAlbum.id, testUserId, "KEEP", isSynced = true),
            SwipeDecisionEntity("photo_3", testAlbum.id, testUserId, "KEEP", isSynced = true),
            SwipeDecisionEntity("photo_6", testAlbum.id, testUserId, "KEEP", isSynced = false),
            SwipeDecisionEntity("photo_8", testAlbum.id, testUserId, "KEEP", isSynced = false)
        )

        // WHEN
        val viewModel = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()

        // THEN
        assertEquals("La timeline doit contenir 7 photos (10 total - 3 synchro)", 7, viewModel.uiState.value.totalCount)
    }

    @Test
    fun `Remaining count must equal Total assets minus Treated assets (synced or not)`() = runTest {
        // GIVEN : 5 photos au total, 1 déjà synchronisée
        assetsFlow.value = (1..5).map { createAsset("photo_$it") }
        decisionsFlow.value = listOf(
            SwipeDecisionEntity("photo_1", testAlbum.id, testUserId, "KEEP", isSynced = true)
        )
        
        val viewModel = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()

        // WHEN: On swipe une nouvelle photo (photo_2) localement (unsynced)
        viewModel.onSwipe(SwipeDecision.KEEP)
        testScheduler.advanceUntilIdle()

        // THEN
        // Total (5) - Traités (1 synced + 1 unsynced) = 3 restant
        assertEquals("Il doit rester 3 photos à traiter (5 total - 2 traités)", 3, viewModel.uiState.value.remainingCount)
    }

    @Test
    fun `Progress must be 100 percent when all timeline assets have a decision`() = runTest {
        // GIVEN : 2 photos, 1 déjà synchronisée
        assetsFlow.value = listOf(createAsset("photo_1"), createAsset("photo_2"))
        decisionsFlow.value = listOf(
            SwipeDecisionEntity("photo_1", testAlbum.id, testUserId, "KEEP", isSynced = true)
        )
        
        val viewModel = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()

        // WHEN: On swipe la seule photo restante de la timeline (photo_2)
        viewModel.onSwipe(SwipeDecision.KEEP)
        testScheduler.advanceUntilIdle()

        // THEN
        assertEquals(1.0f, viewModel.uiState.value.progress)
        assertTrue("L'index doit être à la fin de la liste", 
            viewModel.uiState.value.currentIndex >= viewModel.uiState.value.assets.size)
        assertTrue("L'asset courant doit être null (Fin du tri)", 
            viewModel.uiState.value.currentAsset == null)
    }

    @Test
    fun `Progress must be 0 percent when no timeline assets have a decision`() = runTest {
        // GIVEN : 5 photos, aucune synchronisée
        assetsFlow.value = (1..5).map { createAsset("photo_$it") }
        decisionsFlow.value = emptyList()

        // WHEN
        val viewModel = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()

        // THEN
        assertEquals(0.0f, viewModel.uiState.value.progress)
    }

    @Test
    fun `SwipeViewModel must purge assets immediately when userId changes`() = runTest {
        // GIVEN : Le ViewModel est chargé avec Alice
        assetsFlow.value = listOf(createAsset("photo_alice"))
        val viewModel = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.assets.size)

        // WHEN : On change l'utilisateur dans la session
        sessionConfigFlow.value = SessionConfig("http://url", "key", "user_bob")
        
        // On déclenche manuellement une émission sur les autres flux pour forcer le combine
        decisionsFlow.value = emptyList()

        testScheduler.advanceUntilIdle()

        // THEN : Le ViewModel doit avoir détecté la fuite potentielle et vidé sa liste
        assertTrue("La liste d'assets doit être vidée lors du changement d'utilisateur", 
            viewModel.uiState.value.assets.isEmpty())
    }
    @Test
    fun `Adding to an album advances only after success without recording a keep decision`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"), createAsset("photo_2"))
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        assertEquals("photo_2", vm.uiState.value.currentAsset?.id)
        assertEquals(setOf(destination.id), vm.uiState.value.albumAssignments["photo_1"])
        assertEquals(1, vm.uiState.value.filedCount)
        assertEquals(1, vm.uiState.value.remainingCount)
        assertTrue(vm.uiState.value.decisions.isEmpty())
        coVerify(exactly = 0) { swipeDecisionRepository.saveDecision(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Failed album writes keep the photo visible and allow retry`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"), createAsset("photo_2"))
        coEvery { albumRepository.assignAssetsToAlbum(any(), any(), any()) } throws IllegalStateException("denied")
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        assertEquals("photo_1", vm.uiState.value.currentAsset?.id)
        assertTrue(vm.uiState.value.albumAssignments.isEmpty())
        assertEquals(AlbumActionError.ADD, vm.uiState.value.albumActionError)
        assertEquals(null, vm.uiState.value.error)
        coEvery { albumRepository.assignAssetsToAlbum(any(), any(), any()) } answers { AlbumMutationResult(secondArg<List<String>>().toSet()) }
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        assertEquals("photo_2", vm.uiState.value.currentAsset?.id)
    }

    @Test
    fun `Filed photos cannot be added twice or reopened from the old index`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"))
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        vm.onMoveToAsset(0)
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.uiState.value.currentAsset)
        assertTrue(vm.uiState.value.unfiledAssets.isEmpty())
        coVerify(exactly = 1) { albumRepository.assignAssetsToAlbum(any(), any(), any()) }
    }

    @Test
    fun `Rapid taps and swipes cannot assign the wrong photo while a write is pending`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"), createAsset("photo_2"))
        val pending = CompletableDeferred<Unit>()
        coEvery { albumRepository.assignAssetsToAlbum(any(), any(), any()) } coAnswers { pending.await(); AlbumMutationResult(secondArg<List<String>>().toSet()) }
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.addCurrentAssetToAlbum(destination)
        vm.addCurrentAssetToAlbum(destination)
        runCurrent()
        vm.onMoveToAsset(1)
        vm.onSwipe(SwipeDecision.DELETE)
        vm.undo()
        assertEquals("photo_1", vm.uiState.value.currentAsset?.id)
        assertTrue(vm.uiState.value.isAddingToAlbum)
        pending.complete(Unit)
        testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { albumRepository.assignAssetsToAlbum(destination.id, listOf("photo_1"), testUserId) }
        assertEquals("photo_2", vm.uiState.value.currentAsset?.id)
    }

    @Test
    fun `Created album remains available when adding fails so retry does not create duplicates`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"))
        val created = destination.copy(id = "new", albumName = "New album")
        coEvery { albumRepository.createAlbum("New album") } returns created
        coEvery { albumRepository.assignAssetsToAlbum(any(), any(), any()) } throws IllegalStateException("denied")
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.setShowCreateAlbum(true)
        vm.createAlbumAndAddCurrentAsset("  New album  ")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.availableAlbums.any { it.id == created.id })
        assertEquals(false, vm.uiState.value.showCreateAlbum)
        assertEquals("photo_1", vm.uiState.value.currentAsset?.id)
        assertEquals(AlbumActionError.ADD, vm.uiState.value.albumActionError)
        coEvery { albumRepository.assignAssetsToAlbum(any(), any(), any()) } answers { AlbumMutationResult(secondArg<List<String>>().toSet()) }
        vm.addCurrentAssetToAlbum(created)
        testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { albumRepository.createAlbum(any()) }
        assertEquals(null, vm.uiState.value.currentAsset)
    }

    @Test
    fun `Create and add completes on the final photo`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"))
        coEvery { albumRepository.createAlbum("Travel") } returns destination
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.createAlbumAndAddCurrentAsset("Travel")
        testScheduler.advanceUntilIdle()
        coVerify { albumRepository.assignAssetsToAlbum(destination.id, listOf("photo_1"), testUserId) }
        assertEquals(null, vm.uiState.value.currentAsset)
        assertEquals(1f, vm.uiState.value.progress)
        assertEquals(false, vm.uiState.value.isAddingToAlbum)
    }

    @Test
    fun `Batch assignment removes only confirmed photos and keeps failures selected`() = runTest {
        assetsFlow.value = (1..3).map { createAsset("photo_$it") }
        coEvery { albumRepository.assignAssetsToAlbum(any(), any(), any()) } returns AlbumMutationResult(
            setOf("photo_1"), setOf("photo_2"), setOf("photo_3"))
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.selectAssets(setOf("photo_1", "photo_2", "photo_3"))
        vm.addSelectedAssetsToAlbum(destination)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("photo_3"), vm.uiState.value.unfiledAssets.map { it.id })
        assertEquals(setOf("photo_3"), vm.uiState.value.selectedAssetIds)
        assertEquals(AlbumActionError.ADD, vm.uiState.value.albumActionError)
        vm.undo()
        testScheduler.advanceUntilIdle()
        coVerify { albumRepository.removeAssetsFromAlbum(destination.id, listOf("photo_1"), testUserId) }
        assertEquals(setOf("photo_1", "photo_3"), vm.uiState.value.unfiledAssets.map { it.id }.toSet())
        assertTrue("Pre-existing membership must not be undone", "photo_2" in vm.uiState.value.albumAssignments)
    }

    @Test
    fun `Failed undo keeps history for retry and never restores the photo prematurely`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"))
        coEvery { albumRepository.removeAssetsFromAlbum(any(), any(), any()) } throws IllegalStateException("denied")
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        vm.undo()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.unfiledAssets.isEmpty())
        assertTrue(vm.uiState.value.canUndo)
        assertEquals(AlbumActionError.UNDO, vm.uiState.value.albumActionError)
        coEvery { albumRepository.removeAssetsFromAlbum(any(), any(), any()) } returns AlbumMutationResult(setOf("photo_1"))
        vm.undo()
        testScheduler.advanceUntilIdle()
        assertEquals("photo_1", vm.uiState.value.currentAsset?.id)
        assertEquals(1, vm.uiState.value.unfiledAssets.size)
        assertEquals(false, vm.uiState.value.canUndo)
    }

    @Test
    fun `Undo restores the original photo even after refreshing the server queue`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"), createAsset("photo_2"))
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        assetsFlow.value = listOf(createAsset("photo_2"))
        testScheduler.advanceUntilIdle()
        vm.undo()
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("photo_1", "photo_2"), vm.uiState.value.unfiledAssets.map { it.id }.toSet())
        assertEquals("photo_1", vm.uiState.value.currentAsset?.id)
    }

    @Test
    fun `Undo does not show photos filed in another album by another client`() = runTest {
        assetsFlow.value = listOf(createAsset("photo_1"))
        coEvery { albumRepository.getAlbumIdsForAsset("photo_1") } returns setOf("another-album")
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        vm.undo()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.unfiledAssets.isEmpty())
    }

    @Test
    fun `Album order is loaded moved and saved without changing destinations`() = runTest {
        val second = destination.copy(id = "second", albumName = "Travel")
        val third = destination.copy(id = "third", albumName = "Zoo")
        coEvery { albumRepository.getAlbums() } returns listOf(destination, second, third)
        coEvery { sessionRepository.getAlbumOrder(any()) } returns listOf("third", "destination", "deleted-album")
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, testAlbum)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("third", "destination", "second"), vm.uiState.value.availableAlbums.map { it.id })
        vm.reorderAlbum("second", "third")
        vm.saveAlbumOrder()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("second", "third", "destination"), vm.uiState.value.availableAlbums.map { it.id })
        coVerify { sessionRepository.saveAlbumOrder(any(), listOf("second", "third", "destination")) }
    }

    @Test
    fun `Orphan review includes previously kept photos and never falls back to filed photos`() = runTest {
        val orphanAlbum = testAlbum.copy(id = Album.VIRTUAL_ORPHANS_ID)
        every { swipeDecisionRepository.getDecisionsForAlbum(orphanAlbum.id, testUserId) } returns decisionsFlow
        assetsFlow.value = listOf(createAsset("photo_1"), createAsset("photo_2"))
        decisionsFlow.value = listOf(SwipeDecisionEntity("photo_1", orphanAlbum.id, testUserId, "KEEP", isSynced = true))
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, orphanAlbum)
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.assets.size)
        vm.addCurrentAssetToAlbum(destination)
        testScheduler.advanceUntilIdle()
        vm.onSwipe(SwipeDecision.SKIP)
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.uiState.value.currentAsset)
        assertEquals(listOf("photo_2"), vm.uiState.value.unfiledAssets.map { it.id })
    }

    @Test
    fun `Orphan photos use date order even when previous settings used size or type priority`() = runTest {
        val orphanAlbum = testAlbum.copy(id = Album.VIRTUAL_ORPHANS_ID)
        every { swipeDecisionRepository.getDecisionsForAlbum(orphanAlbum.id, testUserId) } returns decisionsFlow
        swipeSortOrderFlow.value = SwipeSortOrder.SIZE_DESC
        swipeSortPriorityFlow.value = SwipeSortPriority.VIDEOS_FIRST
        assetsFlow.value = listOf(
            createAsset("old").copy(fileCreatedAt = "2024-01-01T12:00:00Z", type = "VIDEO"),
            createAsset("new").copy(fileCreatedAt = "2026-01-01T12:00:00Z"))
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, orphanAlbum)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("new", "old"), vm.uiState.value.unfiledAssets.map { it.id })
        swipeSortOrderFlow.value = SwipeSortOrder.DATE_ASC
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("old", "new"), vm.uiState.value.unfiledAssets.map { it.id })
    }

    @Test
    fun `A previous session skip does not hide an orphan on launch`() = runTest {
        val orphanAlbum = testAlbum.copy(id = Album.VIRTUAL_ORPHANS_ID)
        every { swipeDecisionRepository.getDecisionsForAlbum(orphanAlbum.id, testUserId) } returns decisionsFlow
        assetsFlow.value = listOf(createAsset("photo_1"))
        decisionsFlow.value = listOf(SwipeDecisionEntity("photo_1", orphanAlbum.id, testUserId, "SKIP", isSynced = false))
        val vm = SwipeViewModel(assetRepository, sessionRepository, swipeDecisionRepository, albumRepository, orphanAlbum)
        testScheduler.advanceUntilIdle()
        assertEquals("photo_1", vm.uiState.value.currentAsset?.id)
        assertEquals(1, vm.uiState.value.remainingCount)
    }

}
