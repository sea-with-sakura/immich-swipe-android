package com.minos2020.immichswipe.feature.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minos2020.immichswipe.data.repository.AssetRepository
import com.minos2020.immichswipe.core.AppLogger
import com.minos2020.immichswipe.core.CardDisplayMode
import com.minos2020.immichswipe.core.SessionManager
import com.minos2020.immichswipe.core.SwipeSortOrder
import com.minos2020.immichswipe.core.SwipeSortPriority
import com.minos2020.immichswipe.core.ImmichOpenMode
import com.minos2020.immichswipe.data.repository.SessionRepository
import com.minos2020.immichswipe.data.repository.SwipeDecisionRepository
import com.minos2020.immichswipe.data.local.entity.SwipeDecisionEntity
import com.minos2020.immichswipe.data.repository.AlbumRepository
import com.minos2020.immichswipe.domain.model.Album
import com.minos2020.immichswipe.domain.model.Asset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope

class SwipeViewModel(
    private val assetRepository: AssetRepository,
    private val sessionRepository: SessionRepository,
    private val swipeDecisionRepository: SwipeDecisionRepository,
    private val albumRepository: AlbumRepository,
    private val album: Album
) : ViewModel() {

    private val _uiState = MutableStateFlow(SwipeUiState(albumName = album.albumName))
    val uiState: StateFlow<SwipeUiState> = _uiState.asStateFlow()

    // Liste "Maître" contenant tous les assets chargés, dans leur ordre d'arrivée.
    private var masterWorkPile: List<Asset> = emptyList()
    private val randomSeed = SessionManager.appSessionSeed
    private var hasStartedSwiping: Boolean = false

    // On garde en mémoire les décisions qui étaient déjà synchronisées au début de la session
    private var initialSyncedDecisions = mapOf<String, SwipeDecision>()
    private var lastLoadedUserId: String? = null
    private var assetsJob: kotlinx.coroutines.Job? = null
    private val allAssetsFoundFlow = MutableStateFlow<List<Asset>>(emptyList())
    private val isAssetsLoadingFlow = MutableStateFlow(false)

    init {
        loadAssetsAndDecisions()
        observePlaybackBehavior()
        observeSwipeInversion()
        observeFullscreenButtonPosition()
        observeImmichButtonPosition()
        observeCardDisplayButtonPosition()
        observeMuteButtonPosition()
        observeShareButtonPosition()
        observeRotateButtonPosition()
        observeSkipLifespan()
        observeButtonVisibility()
        observeAutoNextOnFav()
        observeIncludeArchived()
        observeDefaultCardDisplayMode()
        observeSortSettings()
        loadAvailableAlbums()
    }

    private fun observeSortSettings() {
        viewModelScope.launch {
            sessionRepository.swipeSortOrder.collect { order ->
                val chronological = if (album.id == Album.VIRTUAL_ORPHANS_ID && order !in listOf(SwipeSortOrder.DATE_ASC, SwipeSortOrder.DATE_DESC)) SwipeSortOrder.DATE_DESC else order
                _uiState.value = _uiState.value.copy(sortOrder = chronological)
                if (masterWorkPile.isNotEmpty()) refreshSortedWorkPile(jumpToFirstUnprocessed = true)
            }
        }
        viewModelScope.launch {
            sessionRepository.swipeSortPriority.collect { priority ->
                _uiState.value = _uiState.value.copy(sortPriority = if (album.id == Album.VIRTUAL_ORPHANS_ID) SwipeSortPriority.NONE else priority)
                if (masterWorkPile.isNotEmpty()) refreshSortedWorkPile(jumpToFirstUnprocessed = true)
            }
        }
    }

    private fun observeDefaultCardDisplayMode() {
        viewModelScope.launch {
            sessionRepository.defaultCardDisplayMode.collect { mode ->
                _uiState.value = _uiState.value.copy(cardDisplayMode = mode)
            }
        }
    }

    private fun observeIncludeArchived() {
        viewModelScope.launch {
            sessionRepository.includeArchived.collect { include ->
                val changed = _uiState.value.includeArchived != include
                _uiState.value = _uiState.value.copy(includeArchived = include)
                if (changed && lastLoadedUserId != null) loadAssetsAndDecisions()
            }
        }
    }

    private fun observeAutoNextOnFav() {
        viewModelScope.launch {
            sessionRepository.autoNextOnFav.collect { autoNextOnFav ->
                _uiState.value = _uiState.value.copy(autoNextOnFav = autoNextOnFav)
            }
        }
    }

    private fun observeButtonVisibility() {
        viewModelScope.launch {
            sessionRepository.showFavoriteButton.collect { show ->
                _uiState.value = _uiState.value.copy(showFavoriteButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showArchiveButton.collect { show ->
                _uiState.value = _uiState.value.copy(showArchiveButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showLockButton.collect { show ->
                _uiState.value = _uiState.value.copy(showLockButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showKeepDeleteButtons.collect { show ->
                _uiState.value = _uiState.value.copy(showKeepDeleteButtons = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showAddToAlbumButton.collect { show ->
                _uiState.value = _uiState.value.copy(showAddToAlbumButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showShareButton.collect { show ->
                _uiState.value = _uiState.value.copy(showShareButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showFullscreenButton.collect { show ->
                _uiState.value = _uiState.value.copy(showFullscreenButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showImmichButton.collect { show ->
                _uiState.value = _uiState.value.copy(showImmichButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showCardDisplayButton.collect { show ->
                _uiState.value = _uiState.value.copy(showCardDisplayButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showMuteButton.collect { show ->
                _uiState.value = _uiState.value.copy(showMuteButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.showRotateButton.collect { show ->
                _uiState.value = _uiState.value.copy(showRotateButton = show)
            }
        }
        viewModelScope.launch {
            sessionRepository.syncRotate.collect { sync ->
                _uiState.value = _uiState.value.copy(syncRotate = sync)
            }
        }
        viewModelScope.launch {
            sessionRepository.immichOpenMode.collect { mode ->
                _uiState.value = _uiState.value.copy(immichOpenMode = mode)
            }
        }
        viewModelScope.launch {
            sessionRepository.immichLongPressWeb.collect { enabled ->
                _uiState.value = _uiState.value.copy(immichLongPressWeb = enabled)
            }
        }
    }

    private fun observeSkipLifespan() {
        viewModelScope.launch {
            sessionRepository.skipLifespanDays.collect { days ->
                _uiState.value = _uiState.value.copy(skipLifespanDays = days)
            }
        }
    }

    /**
     * Retente le chargement des données si une erreur a eu lieu.
     */
    fun retryLoading() {
        if (!_uiState.value.isLoading) {
            _uiState.value = _uiState.value.copy(error = null)
            // On ajoute un petit délai pour éviter de spammer le serveur en cas de crash en boucle
            viewModelScope.launch {
                delay(500)
                loadAssetsAndDecisions()
            }
        }
    }

    private fun observePlaybackBehavior() {
        viewModelScope.launch {
            sessionRepository.playbackBehavior.collect { behavior ->
                _uiState.value = _uiState.value.copy(playbackBehavior = behavior)
            }
        }
    }

    private fun observeSwipeInversion() {
        viewModelScope.launch {
            sessionRepository.swipeInverted.collect { inverted ->
                _uiState.value = _uiState.value.copy(isSwipeInverted = inverted)
            }
        }
    }

    private fun observeFullscreenButtonPosition() {
        viewModelScope.launch {
            sessionRepository.fullscreenButtonPosition.collect { pos ->
                _uiState.value = _uiState.value.copy(fullscreenButtonPosition = pos)
            }
        }
    }

    private fun observeImmichButtonPosition() {
        viewModelScope.launch {
            sessionRepository.immichButtonPosition.collect { pos ->
                _uiState.value = _uiState.value.copy(immichButtonPosition = pos)
            }
        }
    }

    private fun observeCardDisplayButtonPosition() {
        viewModelScope.launch {
            sessionRepository.cardDisplayButtonPosition.collect { pos ->
                _uiState.value = _uiState.value.copy(cardDisplayButtonPosition = pos)
            }
        }
    }

    private fun observeMuteButtonPosition() {
        viewModelScope.launch {
            sessionRepository.muteButtonPosition.collect { pos ->
                _uiState.value = _uiState.value.copy(muteButtonPosition = pos)
            }
        }
    }

    private fun observeShareButtonPosition() {
        viewModelScope.launch {
            sessionRepository.shareButtonPosition.collect { pos ->
                _uiState.value = _uiState.value.copy(shareButtonPosition = pos)
            }
        }
    }

    private fun observeRotateButtonPosition() {
        viewModelScope.launch {
            sessionRepository.rotateButtonPosition.collect { pos ->
                _uiState.value = _uiState.value.copy(rotateButtonPosition = pos)
            }
        }
    }

    private fun loadAssetsAndDecisions() {
        assetsJob?.cancel()
        assetsJob = viewModelScope.launch {
            try {
                val config = sessionRepository.sessionConfig.first() ?: return@launch
                
                // SECURITÉ ANTI-FUITE : Si on change d'utilisateur, on vide tout l'état précédent immédiatement
                if (lastLoadedUserId != null && lastLoadedUserId != config.userId) {
                    AppLogger.w("Swipe", "Changement d'utilisateur détecté, purge de l'état de tri")
                    _uiState.value = SwipeUiState(albumName = album.albumName)
                }
                lastLoadedUserId = config.userId

                _uiState.value = _uiState.value.copy(isLoading = true)
                hasStartedSwiping = false

                // On réinitialise les flux de chargement
                allAssetsFoundFlow.value = emptyList()
                isAssetsLoadingFlow.value = true

                val includeArchived = sessionRepository.includeArchived.first()
                val sortOrder = sessionRepository.swipeSortOrder.first()
                val isChronologicalSort = sortOrder == SwipeSortOrder.DATE_DESC || sortOrder == SwipeSortOrder.DATE_ASC

                // Lancement du chargement des assets en parallèle
                launch {
                    try {
                        assetRepository.getAssetsByAlbum(
                            albumId = album.id,
                            includeArchived = includeArchived,
                            userId = config.userId,
                            sortOrder = sortOrder,
                            isReactive = true // Permet de rester à jour sur les albums virtuels comme SKIPS
                        ).collect { 
                            allAssetsFoundFlow.value = it 
                            // Pour l'album virtuel SKIPS, le flux est désormais réactif.
                            // On libère le loading UI dès qu'on a reçu au moins un batch (ou une liste vide).
                            if (album.id == Album.VIRTUAL_SKIPPED_ID) {
                                isAssetsLoadingFlow.value = false
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _uiState.update { it.copy(error = e.message ?: "Could not load photos", isLoading = false) }
                    } finally {
                        isAssetsLoadingFlow.value = false
                    }
                }

                // On combine les deux flux pour une réactivité totale (Reset/Keep All du Home)
                combine(
                    swipeDecisionRepository.getDecisionsForAlbum(album.id, config.userId),
                    allAssetsFoundFlow,
                    isAssetsLoadingFlow,
                    sessionRepository.sessionConfig // On ajoute la config ici pour être réactif au changement d'utilisateur
                ) { localDecisions, allAssetsFound, isFetching, currentConfig ->
                    // SECURITÉ ANTI-FUITE : Si l'utilisateur a changé, on vide tout
                    if (currentConfig != null && lastLoadedUserId != null && lastLoadedUserId != currentConfig.userId) {
                         android.util.Log.w("Swipe", "Fuite détectée (UserId différent), purge forcée")
                         return@combine Triple(emptyList<SwipeDecisionEntity>(), emptyList<Asset>(), isFetching)
                    }
                    Triple(localDecisions, allAssetsFound, isFetching)
                }.collect { (localDecisions, allAssetsFound, isFetching) ->
                    // Re-vérification après le collect pour être absolument certain avant de mettre à jour le State
                    val currentConfig = sessionRepository.sessionConfig.first()
                    if (currentConfig != null && lastLoadedUserId != null && lastLoadedUserId != currentConfig.userId) {
                        android.util.Log.w("Swipe", "Purge finale déclenchée")
                        masterWorkPile = emptyList()
                        _uiState.value = SwipeUiState(albumName = album.albumName)
                        return@collect
                    }
                    val currentState = _uiState.value
                    
                    // 1. Mise à jour des décisions synchronisées
                    initialSyncedDecisions = localDecisions
                        .filter { it.isSynced || it.wasSyncedSkip }
                        .associate { entity ->
                            val decision = if (entity.wasSyncedSkip) SwipeDecision.SKIP 
                                          else try { SwipeDecision.valueOf(entity.decision) } catch (_: Exception) { SwipeDecision.SKIP }
                            entity.assetId to decision
                        }

                    // On met à jour l'ID de l'utilisateur chargé pour le prochain collect
                    lastLoadedUserId = currentConfig?.userId ?: lastLoadedUserId

                    // 2. Préparation des décisions locales et des tailles
                    val allLocalDecisions = mutableMapOf<String, SwipeDecision>()
                    val sizeMap = mutableMapOf<String, Long>()
                    localDecisions.forEach { entity ->
                        if (!entity.isSynced) {
                            try {
                                val decision = SwipeDecision.valueOf(entity.decision)
                                val ignoreOldReview = album.id == Album.VIRTUAL_ORPHANS_ID &&
                                    (decision == SwipeDecision.KEEP || (decision == SwipeDecision.SKIP && entity.assetId !in currentState.history))
                                if (!ignoreOldReview) allLocalDecisions[entity.assetId] = decision
                            } catch(_: Exception) {}
                        }
                        entity.fileSize?.let { sizeMap[entity.assetId] = it }
                    }

                    // 3. Filtrage de la pile de travail
                    val isVirtualSkipped = album.id == Album.VIRTUAL_SKIPPED_ID
                    val syncedIds = when {
                        isVirtualSkipped -> emptySet()
                        album.id == Album.VIRTUAL_ORPHANS_ID -> initialSyncedDecisions.filterValues { it != SwipeDecision.KEEP && it != SwipeDecision.SKIP }.keys
                        else -> initialSyncedDecisions.keys
                    }
                    val workPile = allAssetsFound.filter { !syncedIds.contains(it.id) }
                    val workPileIds = workPile.map { it.id }.toSet()

                    // On met à jour la liste maître (non triée)
                    masterWorkPile = workPile

                    // 4. Fusion avec les décisions de la session actuelle
                    val mergedDecisions = currentState.decisions.toMutableMap()
                    val mergedSizes = currentState.assetSizes.toMutableMap()

                    // On synchronise mergedDecisions avec la DB (localDecisions)
                    workPileIds.forEach { id ->
                        val dbDecision = allLocalDecisions[id]
                        if (dbDecision != null) {
                            mergedDecisions[id] = dbDecision
                        } else {
                            mergedDecisions.remove(id)
                        }
                        
                        if (!mergedSizes.containsKey(id)) {
                            sizeMap[id]?.let { mergedSizes[id] = it }
                        }
                    }

                    // Nettoyage : on ne garde que les décisions des assets présents dans la pile
                    mergedDecisions.keys.retainAll(workPileIds)

                    // GESTION DU CHARGEMENT :
                    // On garde le cercle de chargement tant que le réseau n'a pas fini (isFetching)
                    // ET qu'on est en tri non chronologique (complexité de tri).
                    val shouldKeepLoading = isFetching && (workPile.isEmpty() || (!isChronologicalSort && !hasStartedSwiping))

                    refreshSortedWorkPile(
                        jumpToFirstUnprocessed = currentState.assets.isEmpty(),
                        overrideDecisions = mergedDecisions,
                        overrideSizes = mergedSizes,
                        overrideIsLoading = shouldKeepLoading
                    )
                    
                    updateSummaryStats()

                    // Anticipation : charge les détails de l'asset actuel si besoin
                    val finalState = _uiState.value
                    if (!finalState.isLoading && finalState.currentIndex < finalState.assets.size) {
                        loadAssetDetail(finalState.assets[finalState.currentIndex].id, finalState.currentIndex)
                    }
                }
                
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Erreur lors du chargement de l'album", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Erreur lors du chargement des photos"
                )
            }
        }
    }

    /**
     * Recalcule les statistiques du résumé en une seule passe pour optimiser les performances.
     */
    private fun updateSummaryStats() {
        val state = _uiState.value
        val decisions = state.decisions
        val assetSizes = state.assetSizes
        val workPile = state.assets
        
        val counts = mutableMapOf<SwipeDecision, Int>()
        val sizes = mutableMapOf<SwipeDecision, Long>()
        val deletedAssets = mutableListOf<Asset>()

        // Calcul de la taille moyenne pour les assets dont le poids est inconnu
        val knownSizes = assetSizes.values.filter { it > 0 }
        val avgSize = if (knownSizes.isEmpty()) 0L else knownSizes.sum() / knownSizes.size

        workPile.forEach { asset ->
            val decision = decisions[asset.id] ?: return@forEach
            
            // Mise à jour des compteurs
            counts[decision] = (counts[decision] ?: 0) + 1
            
            // Mise à jour des tailles (on utilise la taille réelle ou la moyenne)
            val size = assetSizes[asset.id] ?: asset.exifInfo?.fileSizeInBytes ?: avgSize
            sizes[decision] = (sizes[decision] ?: 0L) + size

            // Liste spécifique pour la grille de suppression
            if (decision == SwipeDecision.DELETE) {
                deletedAssets.add(asset)
            }
        }

        _uiState.value = state.copy(
            summaryCounts = counts,
            summarySizes = sizes,
            summaryDeletedAssets = deletedAssets
        )
    }

    private fun refreshSortedWorkPile(
        jumpToFirstUnprocessed: Boolean = false,
        overrideDecisions: Map<String, SwipeDecision>? = null,
        overrideSizes: Map<String, Long>? = null,
        overrideIsLoading: Boolean? = null
    ) {
        val currentState = _uiState.value
        val decisions = overrideDecisions ?: currentState.decisions
        val assetSizes = overrideSizes ?: currentState.assetSizes
        val order = currentState.sortOrder
        val priority = currentState.sortPriority

        // 1. Tri par priorité de type
        var sorted = when (priority) {
            SwipeSortPriority.NONE -> masterWorkPile
            SwipeSortPriority.VIDEOS_FIRST -> masterWorkPile.sortedByDescending { it.type == "VIDEO" }
            SwipeSortPriority.PHOTOS_FIRST -> masterWorkPile.sortedByDescending { it.type == "IMAGE" }
        }

        // 2. Tri par critère (en respectant les groupes de priorité s'ils existent)
        val comparator = when (order) {
            SwipeSortOrder.DATE_DESC -> compareByDescending<Asset> { it.fileCreatedAt }
            SwipeSortOrder.DATE_ASC -> compareBy<Asset> { it.fileCreatedAt }
            SwipeSortOrder.SIZE_DESC -> compareByDescending<Asset> { assetSizes[it.id] ?: it.exifInfo?.fileSizeInBytes ?: 0L }
            SwipeSortOrder.SIZE_ASC -> compareBy<Asset> { assetSizes[it.id] ?: it.exifInfo?.fileSizeInBytes ?: 0L }
            SwipeSortOrder.RANDOM -> null
        }

        sorted = if (order == SwipeSortOrder.RANDOM) {
            // Pour le random, on groupe quand même par priorité si demandée
            if (priority == SwipeSortPriority.NONE) {
                sorted.shuffled(java.util.Random(randomSeed))
            } else {
                val groups = sorted.groupBy { 
                    when (priority) {
                        SwipeSortPriority.VIDEOS_FIRST -> it.type == "VIDEO"
                        SwipeSortPriority.PHOTOS_FIRST -> it.type == "IMAGE"
                        else -> true
                    }
                }
                // On mélange chaque groupe indépendamment
                val firstGroup = (groups[true] ?: emptyList()).shuffled(java.util.Random(randomSeed))
                val secondGroup = (groups[false] ?: emptyList()).shuffled(java.util.Random(randomSeed))
                firstGroup + secondGroup
            }
        } else if (comparator != null) {
            if (priority == SwipeSortPriority.NONE) {
                sorted.sortedWith(comparator)
            } else {
                // Tri stable au sein des groupes de priorité
                val groups = sorted.groupBy { 
                    when (priority) {
                        SwipeSortPriority.VIDEOS_FIRST -> it.type == "VIDEO"
                        SwipeSortPriority.PHOTOS_FIRST -> it.type == "IMAGE"
                        else -> true
                    }
                }
                val firstGroup = (groups[true] ?: emptyList()).sortedWith(comparator)
                val secondGroup = (groups[false] ?: emptyList()).sortedWith(comparator)
                firstGroup + secondGroup
            }
        } else {
            sorted
        }

        // 3. Calcul de l'index de destination
        val firstUnprocessed = sorted.indexOfFirst { it.id !in decisions && it.id !in currentState.albumAssignments }
        val previousId = currentState.currentAsset?.id
        val sameAssetIndex = sorted.indexOfFirst { it.id == previousId && !currentState.isProcessed(it.id) }
        val newIndex = if (jumpToFirstUnprocessed || currentState.assets.isEmpty()) {
            if (firstUnprocessed != -1) firstUnprocessed else sorted.size
        } else {
            // On reste sur l'index actuel (celui du swipe ou du clic timeline)
            if (album.id == Album.VIRTUAL_ORPHANS_ID) {
                if (sameAssetIndex >= 0) sameAssetIndex else if (firstUnprocessed >= 0) firstUnprocessed else sorted.size
            } else currentState.currentIndex.coerceAtMost(sorted.size)
        }

        val newIsLoading = overrideIsLoading ?: currentState.isLoading

        _uiState.value = currentState.copy(
            assets = sorted,
            currentIndex = newIndex,
            decisions = decisions,
            assetSizes = assetSizes,
            isLoading = newIsLoading
        )
    }

    fun setSortOrder(order: SwipeSortOrder) {
        viewModelScope.launch { sessionRepository.saveSwipeSortOrder(order) }
    }

    fun setSortPriority(priority: SwipeSortPriority) {
        viewModelScope.launch { sessionRepository.saveSwipeSortPriority(priority) }
    }

    private fun loadAssetDetail(assetId: String, index: Int) {
        viewModelScope.launch {
            try {
                val detail = assetRepository.getAssetDetail(assetId)
                
                // On met à jour la liste maître pour que le tri reste à jour avec les bonnes tailles
                masterWorkPile = masterWorkPile.map { if (it.id == assetId) detail else it }

                val currentAssets = _uiState.value.assets.toMutableList()
                if (index < currentAssets.size && currentAssets[index].id == assetId) {
                    currentAssets[index] = detail
                    val newSizes = _uiState.value.assetSizes.toMutableMap()
                    detail.exifInfo?.fileSizeInBytes?.let { newSizes[assetId] = it }
                    _uiState.value = _uiState.value.copy(
                        assets = currentAssets,
                        assetSizes = newSizes
                    )
                }
            } catch (e: Exception) {
                // Erreur silencieuse pour les détails
                android.util.Log.e("SWIPE_VM", "Erreur details asset: ${e.message}")
            }
        }
    }

    fun onSwipe(decision: SwipeDecision) {
        if (_uiState.value.isAddingToAlbum) return
        hasStartedSwiping = true
        val currentState = _uiState.value
        val currentAsset = currentState.currentAsset ?: return
        val currentSize = currentState.assetSizes[currentAsset.id] ?: currentAsset.exifInfo?.fileSizeInBytes

        // 1. Sauvegarde en base locale (Room)
        viewModelScope.launch {
            val config = sessionRepository.sessionConfig.first() ?: return@launch
            swipeDecisionRepository.saveDecision(
                assetId = currentAsset.id,
                albumId = album.id,
                userId = config.userId,
                decision = decision.name,
                fileSize = currentSize,
                isSynced = false // Toujours false au départ, même pour SKIP
            )
        }

        // 2. Mise à jour de l'UI
        val newDecisions = currentState.decisions.toMutableMap()
        newDecisions[currentAsset.id] = decision

        val newSizes = currentState.assetSizes.toMutableMap()
        currentSize?.let { newSizes[currentAsset.id] = it }

        val newHistory = currentState.history.toMutableList()
        newHistory.add(currentAsset.id)

        // Trouver le prochain asset à afficher
        val assets = currentState.assets
        var nextIndex = -1

        // 1. Chercher d'abord la prochaine photo NON TRAITÉE après l'actuelle
        for (i in (currentState.currentIndex + 1) until assets.size) {
            if (!newDecisions.containsKey(assets[i].id) && assets[i].id !in currentState.albumAssignments) {
                nextIndex = i
                break
            }
        }

        // 2. Si rien trouvé après, chercher une photo NON TRAITÉE depuis le début (boucle)
        if (nextIndex == -1) {
            for (i in 0 until currentState.currentIndex) {
                if (!newDecisions.containsKey(assets[i].id) && assets[i].id !in currentState.albumAssignments) {
                    nextIndex = i
                    break
                }
            }
        }

        // 3. Si TOUT est traité (Mode Revue), on passe simplement au suivant dans l'ordre de la liste
        if (nextIndex == -1 && album.id == Album.VIRTUAL_ORPHANS_ID) nextIndex = assets.size
        if (nextIndex == -1) {
            if (currentState.currentIndex + 1 < assets.size) {
                nextIndex = currentState.currentIndex + 1
            } else {
                nextIndex = assets.size // Fin réelle de l'album
            }
        }

        _uiState.value = currentState.copy(
            currentIndex = nextIndex,
            decisions = newDecisions,
            assetSizes = newSizes,
            history = newHistory
        )
        
        updateSummaryStats()

        // Anticipation : charge les détails du prochain
        if (nextIndex < assets.size) {
            loadAssetDetail(assets[nextIndex].id, nextIndex)
        }
    }

    fun toggleFavorite() {
        hasStartedSwiping = true
        val currentState = _uiState.value
        val currentAsset = currentState.currentAsset ?: return
        val newStatus = !currentState.isFavorite(currentAsset.id)
        
        // 1. Mise à jour locale immédiate (Optimisme UI)
        val newFavorites = currentState.localFavorites.toMutableMap()
        newFavorites[currentAsset.id] = newStatus
        _uiState.value = currentState.copy(localFavorites = newFavorites)
        
        if (currentState.autoNextOnFav && newStatus) {
            onSwipe(SwipeDecision.KEEP) // Avance à la suivante si demandé
        }

        // 2. Synchronisation immédiate avec le serveur
        viewModelScope.launch {
            try {
                assetRepository.updateAssets(listOf(currentAsset.id), isFavorite = newStatus)
                
                // Succès : On met à jour l'objet Asset dans nos listes pour que ce soit permanent
                val updatedAsset = currentAsset.copy(isFavorite = newStatus)
                masterWorkPile = masterWorkPile.map { if (it.id == currentAsset.id) updatedAsset else it }
                
                _uiState.update { state ->
                    val updatedAssets = state.assets.map { if (it.id == currentAsset.id) updatedAsset else it }
                    val finalLocalFavorites = state.localFavorites.toMutableMap()
                    // On peut retirer l'entrée locale car l'objet Asset a maintenant la bonne valeur
                    finalLocalFavorites.remove(currentAsset.id)
                    state.copy(assets = updatedAssets, localFavorites = finalLocalFavorites)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Échec de mise à jour du favori", e)
                // Échec : On annule l'optimisme (le retrait de la clé localFavorites ramène à la valeur du serveur)
                _uiState.update { state ->
                    val revertedLocalFavorites = state.localFavorites.toMutableMap()
                    revertedLocalFavorites.remove(currentAsset.id)
                    state.copy(localFavorites = revertedLocalFavorites)
                }
            }
        }
    }

    fun toggleArchive() {
        onSwipe(SwipeDecision.ARCHIVE)
    }

    fun toggleLock() {
        onSwipe(SwipeDecision.LOCK)
    }

    fun toggleDisplayMode() {
        val nextMode = if (_uiState.value.cardDisplayMode == com.minos2020.immichswipe.core.CardDisplayMode.FILL) {
            CardDisplayMode.FIT
        } else {
            CardDisplayMode.FILL
        }
        _uiState.value = _uiState.value.copy(cardDisplayMode = nextMode)
    }

    fun toggleMute() {
        _uiState.value = _uiState.value.copy(isMuted = !_uiState.value.isMuted)
    }

    // Liste des IDs d'assets dont nous avons initié l'édition pendant cette session
    private val assetsEditedThisSession = mutableSetOf<String>()

    fun rotateCurrentAsset() {
        val asset = _uiState.value.currentAsset ?: return
        val currentRotation = _uiState.value.localRotations[asset.id] ?: 0
        val newRotation = currentRotation + 90
        
        // 1. Mise à jour UI immédiate
        val newRotations = _uiState.value.localRotations.toMutableMap()
        newRotations[asset.id] = newRotation
        _uiState.value = _uiState.value.copy(localRotations = newRotations)
        
        // 2. Synchronisation serveur si activée
        if (_uiState.value.syncRotate) {
            // SECURITÉ : On ne synchronise QUE si l'image n'était pas déjà éditée sur Immich,
            // OU si c'est nous qui avons créé l'édition pendant cette session.
            // Cela évite d'écraser des crops/edits complexes faits sur le web.
            val canSync = !asset.isEdited || assetsEditedThisSession.contains(asset.id)

            if (canSync) {
                assetsEditedThisSession.add(asset.id)
                viewModelScope.launch {
                    try {
                        // On normalise la rotation pour le serveur (0, 90, 180, 270)
                        assetRepository.updateAssetEdits(asset.id, newRotation % 360)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLogger.e("Swipe", "Erreur synchro rotation", e)
                    }
                }
            } else {
                AppLogger.d("Swipe", "Sync rotation ignorée pour protéger les edits existants de ${asset.id}")
            }
        }
    }

    fun setShowResetConfirmation(show: Boolean) {
        _uiState.update { it.copy(showResetConfirmation = show) }
    }

    /**
     * Réinitialise toutes les décisions non synchronisées de la session actuelle.
     */
    fun resetSessionDecisions() {
        val currentState = _uiState.value
        val unsyncedIds = currentState.decisions.keys.toList()
        
        if (unsyncedIds.isEmpty()) {
            setShowResetConfirmation(false)
            return
        }

        viewModelScope.launch {
            try {
                val config = sessionRepository.sessionConfig.first() ?: return@launch
                // 1. Suppression base Room pour cet utilisateur
                swipeDecisionRepository.removeDecisions(unsyncedIds, config.userId)
                
                // 2. Mise à jour UI
                _uiState.update { state ->
                    state.copy(
                        decisions = emptyMap(),
                        history = emptyList(),
                        showResetConfirmation = false
                    )
                }
                
                // On réinitialise l'état de session si on a tout vidé
                hasStartedSwiping = false
                
                // 3. Rafraîchissement du tri pour revenir à la première photo
                refreshSortedWorkPile(jumpToFirstUnprocessed = true)
                updateSummaryStats()
                
                AppLogger.i("Swipe", "Session réinitialisée : ${unsyncedIds.size} décisions supprimées")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Erreur lors du reset de la session", e)
                setShowResetConfirmation(false)
            }
        }
    }

    /**
     * Met à jour la description d'un asset sur le serveur et localement.
     */
    fun updateAssetDescription(assetId: String, newDescription: String) {
        val currentState = _uiState.value
        val assets = currentState.assets.toMutableList()
        val index = assets.indexOfFirst { it.id == assetId }
        if (index == -1) return

        val oldAsset = assets[index]
        val oldDescription = oldAsset.exifInfo?.description ?: ""
        if (oldDescription == newDescription) return

        // 1. Mise à jour locale immédiate (Optimisme UI)
        val newExif = (oldAsset.exifInfo ?: com.minos2020.immichswipe.domain.model.ExifInfo()).copy(description = newDescription)
        val newAsset = oldAsset.copy(exifInfo = newExif)
        assets[index] = newAsset
        
        // On met aussi à jour la liste maître pour que le tri reste cohérent
        masterWorkPile = masterWorkPile.map { if (it.id == assetId) newAsset else it }
        
        _uiState.value = currentState.copy(assets = assets)

        // 2. Appel API en arrière-plan avec vérification
        viewModelScope.launch {
            try {
                assetRepository.updateAssets(listOf(assetId), description = newDescription)
                AppLogger.d("Swipe", "Description mise à jour pour l'asset $assetId")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Échec de mise à jour de la description", e)
                // Restauration de l'ancienne valeur en cas d'erreur pour garantir la cohérence
                val revertedState = _uiState.value
                val revertedAssets = revertedState.assets.toMutableList()
                val rIndex = revertedAssets.indexOfFirst { it.id == assetId }
                if (rIndex != -1) {
                    revertedAssets[rIndex] = oldAsset
                    _uiState.value = revertedState.copy(assets = revertedAssets)
                }
            }
        }
    }

    fun undo() {
        if (_uiState.value.isAddingToAlbum) return
        hasStartedSwiping = true
        val currentState = _uiState.value
        val lastAssetIdFromHistory = currentState.history.lastOrNull()
        val albumUndo = currentState.albumUndoHistory.lastOrNull { it.key == lastAssetIdFromHistory }
        if (albumUndo != null) {
            undoAlbumAssignment(albumUndo)
            return
        }

        viewModelScope.launch {
            try {
                val config = sessionRepository.sessionConfig.first() ?: return@launch
                if (lastAssetIdFromHistory != null) {
                    // 1. LOGIQUE DE SESSION (Historique présent)
                    swipeDecisionRepository.removeDecision(lastAssetIdFromHistory, config.userId)
                    
                    val newDecisions = currentState.decisions.toMutableMap()
                    newDecisions.remove(lastAssetIdFromHistory)

                    val newHistory = currentState.history.toMutableList()
                    newHistory.removeAt(newHistory.size - 1)

                    val previousIndex = currentState.assets.indexOfFirst { it.id == lastAssetIdFromHistory }

                    _uiState.value = currentState.copy(
                        currentIndex = if (previousIndex != -1) previousIndex else currentState.currentIndex,
                        decisions = newDecisions,
                        history = newHistory
                    )
                    
                    updateSummaryStats()
                    
                    if (previousIndex != -1) {
                        loadAssetDetail(lastAssetIdFromHistory, previousIndex)
                    }
                } else if (currentState.currentIndex > 0) {
                    // 2. LOGIQUE DE REMONTÉE (Historique vide, on recule manuellement)
                    // "annule l'asset affiché actuellement, puis passe au précédent"
                    val currentAsset = currentState.currentAsset
                    val previousIndex = currentState.currentIndex - 1
                    val previousAssetId = currentState.assets[previousIndex].id

                    // On nettoie UNIQUEMENT l'actuel
                    if (currentAsset != null) {
                        swipeDecisionRepository.removeDecision(currentAsset.id, config.userId)
                    }
                    
                    val newDecisions = currentState.decisions.toMutableMap()
                    if (currentAsset != null) {
                        newDecisions.remove(currentAsset.id)
                    }

                    _uiState.value = currentState.copy(
                        currentIndex = previousIndex,
                        decisions = newDecisions
                    )
                    
                    updateSummaryStats()
                    
                    loadAssetDetail(previousAssetId, previousIndex)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Échec de l'annulation", e)
            }
        }
    }

    /**
     * Permet de sauter directement à un asset précis (via la timeline).
     */
    fun onMoveToAsset(index: Int) {
        if (_uiState.value.isAddingToAlbum) return
        //hasStartedSwiping = true // NE COMPTE PLUS COMME DÉBUT DE SESSION
        if (index in 0 until _uiState.value.assets.size && _uiState.value.assets[index].id !in _uiState.value.albumAssignments) {
            _uiState.value = _uiState.value.copy(currentIndex = index)
            loadAssetDetail(_uiState.value.assets[index].id, index)
        }
    }

    /**
     * Affiche ou cache l'écran de résumé.
     */
    fun toggleSummary(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showSummary = visible)
    }

    /**
     * Annule une décision spécifique (utilisé depuis le résumé).
     */
    fun undoSpecificDecision(assetId: String) {
        val currentState = _uiState.value
        viewModelScope.launch {
            try {
                val config = sessionRepository.sessionConfig.first() ?: return@launch
                // 1. Suppression base Room
                swipeDecisionRepository.removeDecision(assetId, config.userId)
                
                // 2. Mise à jour UI
                val newDecisions = currentState.decisions.toMutableMap()
                newDecisions.remove(assetId)
                
                val newHistory = currentState.history.toMutableList()
                newHistory.remove(assetId)
                
                _uiState.value = currentState.copy(
                    decisions = newDecisions,
                    history = newHistory
                )
                updateSummaryStats()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Échec de l'annulation spécifique", e)
            }
        }
    }

    /**
     * Applique les décisions (Suppression sur Immich) et marque les assets comme traités localement.
     */
    fun applyChanges() {
        if (_uiState.value.isAddingToAlbum || _uiState.value.isSyncing) return
        val currentState = _uiState.value
        val decisions = currentState.decisions
        val assetSizes = currentState.assetSizes
        
        val toDelete = decisions.filter { it.value == SwipeDecision.DELETE }.keys.toList()
        val toArchive = decisions.filter { it.value == SwipeDecision.ARCHIVE }.keys.toList()
        val toLock = decisions.filter { it.value == SwipeDecision.LOCK }.keys.toList()
        val toKeep = decisions.filter { it.value == SwipeDecision.KEEP }.keys.toList()
        val toSkip = decisions.filter { it.value == SwipeDecision.SKIP }.keys.toList()

        viewModelScope.launch {
            AppLogger.i("Swipe", "Application des changements : DELETE(${toDelete.size}), ARCHIVE(${toArchive.size}), LOCK(${toLock.size}), KEEP(${toKeep.size}), SKIP(${toSkip.size})")
            _uiState.update { it.copy(isSyncing = true) }
            try {
                val config = sessionRepository.sessionConfig.first() ?: return@launch
                
                // 1. Appels API en parallèle pour plus de rapidité
                coroutineScope {
                    if (toDelete.isNotEmpty()) launch { assetRepository.deleteAssets(toDelete) }
                    if (toArchive.isNotEmpty()) launch { assetRepository.updateAssets(toArchive, visibility = "archive") }
                    if (toLock.isNotEmpty()) launch { assetRepository.updateAssets(toLock, visibility = "locked") }
                }

                // 2. Mise à jour de la base de données locale (On fait confiance au succès des appels API)
                val disappeared = (toDelete + toLock).toSet()
                val successfulKeeps = (toKeep + toArchive + toSkip).toList()

                if (disappeared.isNotEmpty()) {
                    swipeDecisionRepository.removeDecisions(disappeared.toList(), config.userId)
                }

                if (successfulKeeps.isNotEmpty()) {
                    swipeDecisionRepository.markAsSynced(successfulKeeps, config.userId)
                }

                // 3. Mise à jour de l'état en mémoire pour un rafraîchissement immédiat sans rechargement réseau
                if (disappeared.isNotEmpty()) {
                    allAssetsFoundFlow.update { list -> list.filter { !disappeared.contains(it.id) } }
                }

                // 4. Enregistrement dans l'historique avec calcul de DELTAS
                var deltaKeep = toKeep.size
                var deltaArchive = toArchive.size
                var deltaSkip = toSkip.size

                (toKeep + toArchive + toSkip + toDelete + toLock).forEach { id ->
                    val previous = initialSyncedDecisions[id]
                    if (previous != null) {
                        // L'asset avait déjà une décision synchronisée : on annule l'ancienne catégorie
                        when (previous) {
                            SwipeDecision.KEEP -> deltaKeep--
                            SwipeDecision.ARCHIVE -> deltaArchive--
                            SwipeDecision.SKIP -> deltaSkip--
                            else -> {}
                        }
                    }
                }

                swipeDecisionRepository.saveSyncHistory(
                    userId = config.userId,
                    deletedCount = toDelete.size,
                    bytesSaved = toDelete.sumOf { assetSizes[it] ?: 0L },
                    keptCount = deltaKeep,
                    archivedCount = deltaArchive,
                    lockedCount = toLock.size,
                    skippedCount = deltaSkip
                )

                AppLogger.i("Swipe", "Synchronisation réussie (Smooth Sync).")

                // 5. Feedback utilisateur
                _uiState.update { it.copy(
                    isSyncing = false,
                    showSummary = false,
                    showSuccessAnimation = true,
                    decisions = emptyMap() // On vide les décisions locales car elles sont maintenant synchronisées
                ) }
                
                delay(2500)
                _uiState.update { it.copy(showSuccessAnimation = false) }
                
                // Note : On ne rappelle plus loadAssetsAndDecisions() car la transition est gérée par la réactivité des Flow
                
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Échec lors de l'application des changements", e)
                _uiState.update { it.copy(
                    isSyncing = false,
                    error = "Erreur lors de la synchronisation : ${e.message}"
                ) }
            }
        }
    }

    /**
     * Calcule l'index du prochain asset à afficher en arrière-plan.
     * Priorité aux non-traités, sinon le suivant dans la liste.
     */
    fun getNextUnprocessedIndex(): Int {
        val state = _uiState.value
        val assets = state.assets
        val decisions = state.decisions
        val current = state.currentIndex

        // 1. Chercher le prochain non-traité après
        for (i in (current + 1) until assets.size) {
            if (!state.isProcessed(assets[i].id)) return i
        }
        
        // 2. Chercher le prochain non-traité avant
        for (i in 0 until current) {
            if (!state.isProcessed(assets[i].id)) return i
        }

        if (album.id == Album.VIRTUAL_ORPHANS_ID) return -1
        // 3. Si tout est traité, on affiche simplement la carte suivante dans la liste
        if (current + 1 < assets.size) {
            return current + 1
        }
        
        return -1
    }

    fun setAlbumSearchQuery(query: String) {
        _uiState.update { it.copy(albumSearchQuery = query) }
    }

    fun setShowCreateAlbum(show: Boolean) {
        if (!_uiState.value.isAddingToAlbum) {
            _uiState.update { it.copy(showCreateAlbum = show, albumActionError = null) }
        }
    }

    fun loadAvailableAlbums() {
        if (_uiState.value.isLoadingAlbums) return
        _uiState.update { it.copy(isLoadingAlbums = true, albumActionError = null) }
        viewModelScope.launch {
            val config = sessionRepository.sessionConfig.first()
            try {
                val albums = albumRepository.getAlbums().sortedBy { it.albumName.lowercase() }
                val order = config?.let { sessionRepository.getAlbumOrder(it) }.orEmpty()
                val positions = order.withIndex().associate { it.value to it.index }
                if (config != sessionRepository.sessionConfig.first()) return@launch
                _uiState.update { it.copy(availableAlbums = albums.sortedBy { a -> positions[a.id] ?: Int.MAX_VALUE }, isLoadingAlbums = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Could not load albums", e)
                if (config == sessionRepository.sessionConfig.first()) {
                    _uiState.update { it.copy(isLoadingAlbums = false, albumActionError = AlbumActionError.LOAD) }
                }
            }
        }
    }

    fun toggleSelection(assetId: String) {
        if (_uiState.value.isAddingToAlbum) return
        _uiState.update { state ->
            state.copy(selectedAssetIds = if (assetId in state.selectedAssetIds) state.selectedAssetIds - assetId else state.selectedAssetIds + assetId)
        }
    }

    fun selectAssets(ids: Set<String>) {
        if (_uiState.value.isAddingToAlbum) return
        _uiState.update { state ->
            state.copy(selectedAssetIds = if (state.selectedAssetIds.containsAll(ids)) state.selectedAssetIds - ids else state.selectedAssetIds + ids)
        }
    }

    fun clearSelection() {
        if (!_uiState.value.isAddingToAlbum) _uiState.update { it.copy(selectedAssetIds = emptySet()) }
    }

    fun reorderAlbum(fromId: String, toId: String) {
        if (fromId == toId || _uiState.value.isAddingToAlbum) return
        val albums = _uiState.value.availableAlbums.toMutableList()
        val from = albums.indexOfFirst { it.id == fromId }
        val to = albums.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return
        albums.add(to, albums.removeAt(from))
        _uiState.update { it.copy(availableAlbums = albums) }
    }

    fun saveAlbumOrder() {
        val ids = _uiState.value.availableAlbums.map { it.id }
        viewModelScope.launch {
            val config = sessionRepository.sessionConfig.first() ?: return@launch
            try {
                sessionRepository.saveAlbumOrder(config, ids)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (config == sessionRepository.sessionConfig.first()) _uiState.update { it.copy(albumActionError = AlbumActionError.ORDER) }
            }
        }
    }

    fun addCurrentAssetToAlbum(targetAlbum: Album) = assignAssets(targetAlbum = targetAlbum)

    fun addSelectedAssetsToAlbum(targetAlbum: Album) = assignAssets(targetAlbum = targetAlbum, selectedOnly = true)

    fun createAlbumAndAddCurrentAsset(name: String) {
        if (name.isBlank()) return
        assignAssets(newAlbumName = name.trim(), selectedOnly = _uiState.value.selectedAssetIds.isNotEmpty())
    }

    private fun assignAssets(targetAlbum: Album? = null, newAlbumName: String? = null, selectedOnly: Boolean = false) {
        val state = _uiState.value
        if (state.isAddingToAlbum || state.isSyncing) return
        val ids = (if (selectedOnly) state.selectedAssetIds.toList() else listOfNotNull(state.currentAsset?.id))
            .filter { it !in state.albumAssignments }.distinct()
        if (ids.isEmpty()) return
        _uiState.update { it.copy(isAddingToAlbum = true, albumActionError = null, lastAddedAlbumName = null) }
        viewModelScope.launch {
            val config = sessionRepository.sessionConfig.first()
            var destination = targetAlbum
            try {
                checkNotNull(config) { "No active session" }
                if (destination == null) {
                    destination = albumRepository.createAlbum(checkNotNull(newAlbumName))
                    if (config != sessionRepository.sessionConfig.first()) return@launch
                    val created = destination
                    _uiState.update { it.copy(availableAlbums = it.availableAlbums + created) }
                }
                val selected = destination
                val result = albumRepository.assignAssetsToAlbum(selected.id, ids, config.userId)
                if (config != sessionRepository.sessionConfig.first()) return@launch
                hasStartedSwiping = true
                val key = "album:" + java.util.UUID.randomUUID()
                _uiState.update { current ->
                    val assignments = current.albumAssignments + result.completedIds.associateWith { setOf(selected.id) }
                    val next = ((current.currentIndex until current.assets.size) + (0 until current.currentIndex))
                        .firstOrNull { current.assets[it].id !in assignments && current.assets[it].id !in current.decisions }
                        ?: current.assets.size
                    current.copy(
                        isAddingToAlbum = false, showCreateAlbum = false,
                        albumAssignments = assignments,
                        currentIndex = next,
                        selectedAssetIds = current.selectedAssetIds - result.completedIds,
                        history = if (result.changedIds.isEmpty()) current.history else current.history + key,
                        albumUndoHistory = if (result.changedIds.isEmpty()) current.albumUndoHistory else
                            current.albumUndoHistory + AlbumUndoEntry(key, selected.id, result.changedIds, current.assets.filter { it.id in result.changedIds }),
                        lastAddedAlbumName = selected.albumName.takeIf { result.completedIds.isNotEmpty() },
                        availableAlbums = current.availableAlbums.map { a -> if (a.id == selected.id)
                            a.copy(assetCount = a.assetCount + result.changedIds.size,
                                albumThumbnailAssetId = a.albumThumbnailAssetId ?: result.completedIds.firstOrNull()) else a },
                        albumActionError = AlbumActionError.ADD.takeIf { result.failedIds.isNotEmpty() }
                    )
                }
                _uiState.value.currentAsset?.let { loadAssetDetail(it.id, _uiState.value.currentIndex) }
                updateSummaryStats()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e("Swipe", "Could not add photos to album", e)
                if (config == sessionRepository.sessionConfig.first()) {
                    _uiState.update { it.copy(isAddingToAlbum = false,
                        showCreateAlbum = if (destination != null) false else it.showCreateAlbum,
                        albumActionError = if (destination == null) AlbumActionError.CREATE else AlbumActionError.ADD) }
                }
            }
        }
    }

    private fun undoAlbumAssignment(entry: AlbumUndoEntry) {
        _uiState.update { it.copy(isAddingToAlbum = true, albumActionError = null, lastAddedAlbumName = null) }
        viewModelScope.launch {
            val config = sessionRepository.sessionConfig.first() ?: return@launch
            try {
                val result = albumRepository.removeAssetsFromAlbum(entry.albumId, entry.assetIds.toList(), config.userId)
                val memberships = result.completedIds.associateWith { albumRepository.getAlbumIdsForAsset(it) }
                if (config != sessionRepository.sessionConfig.first()) return@launch
                val restoredAssets = entry.assets.filter { it.id in result.completedIds }
                val knownIds = masterWorkPile.map { it.id }.toSet()
                masterWorkPile = masterWorkPile + restoredAssets.filter { it.id !in knownIds }
                _uiState.update { state ->
                    val restored = (state.assets + restoredAssets).distinctBy { it.id }.let { list ->
                        if (state.sortOrder == SwipeSortOrder.DATE_ASC) list.sortedBy { it.fileCreatedAt } else list.sortedByDescending { it.fileCreatedAt }
                    }
                    val remaining = entry.assetIds - result.completedIds
                    val assignments = state.albumAssignments.toMutableMap()
                    result.completedIds.forEach { id ->
                        val albums = memberships[id].orEmpty()
                        if (albums.isEmpty()) assignments.remove(id) else assignments[id] = albums
                    }
                    state.copy(
                        isAddingToAlbum = false, albumAssignments = assignments, assets = restored,
                        currentIndex = restored.indexOfFirst { it.id in result.completedIds && it.id !in assignments }.takeIf { it >= 0 } ?: state.currentIndex,
                        history = if (remaining.isEmpty()) state.history - entry.key else state.history,
                        albumUndoHistory = state.albumUndoHistory.mapNotNull { e -> if (e.key != entry.key) e else
                            if (remaining.isEmpty()) null else e.copy(assetIds = remaining) },
                        availableAlbums = state.availableAlbums.map { a -> if (a.id == entry.albumId)
                            a.copy(assetCount = (a.assetCount - result.completedIds.size).coerceAtLeast(0),
                                albumThumbnailAssetId = a.albumThumbnailAssetId.takeUnless { it in result.completedIds }) else a },
                        albumActionError = AlbumActionError.UNDO.takeIf { remaining.isNotEmpty() }
                    )
                }
                allAssetsFoundFlow.value = (allAssetsFoundFlow.value + restoredAssets).distinctBy { it.id }
                _uiState.value.currentAsset?.let { loadAssetDetail(it.id, _uiState.value.currentIndex) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (config == sessionRepository.sessionConfig.first()) _uiState.update { it.copy(isAddingToAlbum = false, albumActionError = AlbumActionError.UNDO) }
            }
        }
    }

    fun shareCurrentAsset(context: android.content.Context) {
        val asset = _uiState.value.currentAsset ?: return
        viewModelScope.launch {
            try {
                val file = assetRepository.downloadAssetForSharing(context, asset) ?: return@launch
                
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                
                val mimeType = context.contentResolver.getType(uri) ?: "image/*"
                
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    // EXTRA_TITLE permet d'afficher un titre propre dans l'aperçu du sélecteur
                    putExtra(android.content.Intent.EXTRA_TITLE, asset.originalFileName ?: "Partager le média")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Ajout du ClipData pour activer l'aperçu visuel dans le sélecteur Android (API 29+)
                    clipData = android.content.ClipData.newRawUri(null, uri)
                }
                
                val chooser = android.content.Intent.createChooser(intent, asset.originalFileName ?: "Partager le média")
                context.startActivity(chooser)
            } catch (e: Exception) {
                AppLogger.e("Swipe", "Erreur lors du partage", e)
            }
        }
    }
}
