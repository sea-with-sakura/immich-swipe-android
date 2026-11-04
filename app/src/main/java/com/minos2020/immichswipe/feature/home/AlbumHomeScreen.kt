package com.minos2020.immichswipe.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.minos2020.immichswipe.R
import com.minos2020.immichswipe.core.SessionManager
import com.minos2020.immichswipe.core.SwipeSortOrder
import com.minos2020.immichswipe.data.repository.*
import com.minos2020.immichswipe.domain.model.Album
import com.minos2020.immichswipe.domain.model.Asset
import com.minos2020.immichswipe.feature.settings.*
import com.minos2020.immichswipe.feature.swipe.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Both tabs share a single queue, selection, destination order and undo history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumHomeScreen(
    homeViewModel: HomeViewModel,
    assetRepository: AssetRepository,
    swipeDecisionRepository: SwipeDecisionRepository,
    albumRepository: AlbumRepository,
    sessionKey: String,
    modifier: Modifier = Modifier
) {
    val home by homeViewModel.uiState.collectAsState()
    val unfiledName = stringResource(R.string.organize_unfiled)
    val organizer: SwipeViewModel = viewModel(key = "organizer-$sessionKey", factory = SwipeViewModelFactory(
        assetRepository, homeViewModel.getSessionRepository(), swipeDecisionRepository, albumRepository,
        Album(Album.VIRTUAL_ORPHANS_ID, unfiledName, null, 0, null)))
    val state by organizer.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val photos = state.unfiledAssets
    val groups = remember(photos) { photos.groupBy { it.fileCreatedAt.take(10) } }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePicker = rememberDatePickerState()
    val isSettings = home.currentTab == HomeTab.SETTINGS
    LaunchedEffect(sessionKey) { homeViewModel.loadUser() }
    LaunchedEffect(state.sortOrder, state.completedBatchAssignments) {
        // A completed batch must override the grid's retained first-visible photo.
        gridState.requestScrollToItem(0)
    }
    BackHandler(enabled = home.currentTab != HomeTab.HOME || state.selectedAssetIds.isNotEmpty()) {
        if (state.selectedAssetIds.isNotEmpty()) organizer.clearSelection() else homeViewModel.goBack()
    }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(if (isSettings) stringResource(R.string.settings_title) else stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                if (isSettings) IconButton(onClick = homeViewModel::goBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                }
            }, actions = {
                if (!isSettings) {
                    if (home.currentTab == HomeTab.HOME) IconButton(onClick = { showDatePicker = true }, enabled = photos.isNotEmpty()) {
                        Icon(Icons.Default.CalendarMonth, stringResource(R.string.organize_jump_date))
                    }
                    if (home.currentTab == HomeTab.SWIPE && state.deletedCount > 0) TextButton(onClick = { organizer.toggleSummary(true) }) {
                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(20.dp)); Text("${state.deletedCount}")
                    }
                    IconButton(onClick = { homeViewModel.toggleProfilePopup(true) }) {
                        Icon(Icons.Default.AccountCircle, stringResource(R.string.profile_connected_label))
                    }
                }
            })
    }, bottomBar = {
        if (!isSettings) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                .height(48.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                listOf(HomeTab.HOME, HomeTab.SWIPE).forEach { tab ->
                    val selected = home.currentTab == tab
                    Box(Modifier.weight(1f).fillMaxHeight().selectable(selected = selected, role = Role.Tab, onClick = {
                        if (tab == HomeTab.SWIPE) organizer.clearSelection()
                        homeViewModel.onTabSelected(tab)
                    }), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 64.dp, height = 32.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            Icon(if (tab == HomeTab.HOME) Icons.Default.PhotoLibrary else Icons.Default.Swipe,
                                contentDescription = stringResource(if (tab == HomeTab.HOME) R.string.nav_home else R.string.nav_swipe),
                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (home.currentTab) {
                HomeTab.HOME -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(unfiledName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.organize_photo_count, photos.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.selectedAssetIds.isNotEmpty()) IconButton(onClick = organizer::clearSelection) {
                            Icon(Icons.Default.Deselect, stringResource(R.string.organize_clear_selection))
                        }
                        TextButton(onClick = {
                            organizer.setSortOrder(if (state.sortOrder == SwipeSortOrder.DATE_ASC) SwipeSortOrder.DATE_DESC else SwipeSortOrder.DATE_ASC)
                        }, enabled = !state.isAddingToAlbum) {
                            Icon(if (state.sortOrder == SwipeSortOrder.DATE_ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(if (state.sortOrder == SwipeSortOrder.DATE_ASC) R.string.organize_oldest else R.string.organize_newest))
                        }
                    }
                    PullToRefreshBox(isRefreshing = state.isLoading, onRefresh = {
                        if (!state.isAddingToAlbum) { organizer.retryLoading(); organizer.loadAvailableAlbums() }
                    }, modifier = Modifier.weight(1f)) {
                        when {
                            state.error != null -> ErrorView(state.error!!, organizer::retryLoading)
                            photos.isEmpty() && !state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(stringResource(R.string.organize_all_filed), Modifier.padding(16.dp))
                                }
                            }
                            else -> LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState,
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                groups.forEach { (date, assets) ->
                                    item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                                        val ids = assets.map { it.id }.toSet()
                                        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(enabled = !state.isAddingToAlbum) { organizer.selectAssets(ids) },
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text(formatPhotoDate(date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                            Text("${assets.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Checkbox(checked = state.selectedAssetIds.containsAll(ids), onCheckedChange = { organizer.selectAssets(ids) }, enabled = !state.isAddingToAlbum)
                                        }
                                    }
                                    items(assets, key = { it.id }) { asset ->
                                        SelectablePhoto(asset, asset.id in state.selectedAssetIds, !state.isAddingToAlbum) { organizer.toggleSelection(asset.id) }
                                    }
                                }
                            }
                        }
                    }
                    AlbumOrganizingPanel(state, organizer::addSelectedAssetsToAlbum, organizer::setAlbumSearchQuery,
                        { organizer.setShowCreateAlbum(true) }, organizer::loadAvailableAlbums, organizer::undo,
                        {}, organizer::reorderAlbum, organizer::saveAlbumOrder, selectionMode = true)
                    if (state.showCreateAlbum) CreateAlbumDialog(state.isAddingToAlbum, state.albumActionError,
                        organizer::createAlbumAndAddCurrentAsset, { organizer.setShowCreateAlbum(false) })
                }
                HomeTab.SWIPE -> SwipeScreen(organizer)
                HomeTab.SETTINGS -> {
                    val settings: SettingsViewModel = viewModel(key = "settings-$sessionKey", factory = SettingsViewModelFactory(homeViewModel.getSessionRepository(), swipeDecisionRepository))
                    OrganizerSettingsScreen(settings)
                }
            }
        }
    }
    if (showDatePicker) DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
        TextButton(onClick = {
            datePicker.selectedDateMillis?.let { millis ->
                val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                val dates = groups.keys.toList()
                val selectedDay = dates.firstOrNull { if (state.sortOrder == SwipeSortOrder.DATE_ASC) it >= day else it <= day } ?: dates.lastOrNull()
                var index = 0
                for ((date, assets) in groups) {
                    if (date == selectedDay) break
                    index += 1 + assets.size
                }
                if (groups.isNotEmpty()) scope.launch { gridState.animateScrollToItem(index) }
            }
            showDatePicker = false
        }) { Text(stringResource(R.string.common_confirm)) }
    }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) } }) { DatePicker(datePicker) }

    if (home.showProfilePopup) ProfilePopup(home.user, home.connectionStatus,
        { homeViewModel.toggleProfilePopup(false) }, { homeViewModel.onTabSelected(HomeTab.SETTINGS) }, { homeViewModel.setShowLogoutConfirmation(true) })
    if (home.showLogoutConfirmation) AlertDialog(onDismissRequest = { homeViewModel.setShowLogoutConfirmation(false) },
        title = { Text(stringResource(R.string.profile_logout_confirm_title)) }, text = { Text(stringResource(R.string.profile_logout_confirm_msg)) },
        confirmButton = { TextButton(onClick = { homeViewModel.setShowLogoutConfirmation(false); homeViewModel.logout() }) { Text(stringResource(R.string.profile_logout_button)) } },
        dismissButton = { TextButton(onClick = { homeViewModel.setShowLogoutConfirmation(false) }) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun SelectablePhoto(asset: Asset, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val url = SessionManager.getBaseUrl()?.trimEnd('/')
    val key = SessionManager.getApiKey()
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
        .clickable(enabled = enabled, onClick = onClick)) {
        AsyncImage(model = remember(url, key, asset.id) { ImageRequest.Builder(context)
            .data("$url/api/assets/${asset.id}/thumbnail?format=WEBP&edited=true").addHeader("x-api-key", key.orEmpty()).crossfade(true).build() },
            contentDescription = asset.originalFileName ?: asset.fileCreatedAt, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (selected) 4.dp else 0.dp).clip(RoundedCornerShape(8.dp)))
        if (selected) Icon(Icons.Default.CheckCircle, stringResource(R.string.organize_photo_selected),
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)))
        if (asset.type == "VIDEO") Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp))
    }
}

private fun formatPhotoDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(date)
