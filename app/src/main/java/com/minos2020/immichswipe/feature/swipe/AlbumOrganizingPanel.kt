package com.minos2020.immichswipe.feature.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.minos2020.immichswipe.R
import com.minos2020.immichswipe.core.SessionManager
import com.minos2020.immichswipe.domain.model.Album
import kotlinx.coroutines.delay

/** Shared destinations on the date grid and photo review. Long press to reorder. */
@Composable
fun AlbumOrganizingPanel(
    uiState: SwipeUiState,
    onAlbumSelected: (Album) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCreateAlbum: () -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
    onReorder: (String, String) -> Unit,
    onOrderSaved: () -> Unit,
    selectionMode: Boolean = false,
    titleOverride: String? = null,
    hintOverride: String? = null,
    uploadProgress: Float? = null
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val busy = uiState.isAddingToAlbum || uiState.isSyncing
    val currentAsset = uiState.currentAsset
    val enabled = !busy && !uiState.isLoading && if (selectionMode) uiState.selectedAssetIds.isNotEmpty()
        else currentAsset != null && currentAsset.id !in uiState.albumAssignments
    val albums = remember(uiState.availableAlbums, uiState.albumSearchQuery) {
        uiState.availableAlbums.filter { it.albumName.contains(uiState.albumSearchQuery, ignoreCase = true) }
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rowHeight = 48.dp * fontScale
    val grid = rememberLazyGridState()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    val reorder by rememberUpdatedState(onReorder)
    val saveOrder by rememberUpdatedState(onOrderSaved)
    val currentAlbums by rememberUpdatedState(albums)
    val reorderEnabled = !busy && uiState.albumSearchQuery.isBlank()

    fun reorderAtPointer() {
        val id = draggedId ?: return
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            pointer.x >= item.offset.x && pointer.x < item.offset.x + item.size.width &&
                pointer.y >= item.offset.y && pointer.y < item.offset.y + item.size.height
        } ?: return
        if (target.key != id) reorder(id, target.key as String)
    }

    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            val width = grid.layoutInfo.viewportSize.width
            val edge = 60f
            val scroll = when {
                pointer.x < edge -> -12f
                pointer.x > width - edge -> 12f
                else -> 0f
            }
            if (scroll != 0f) { grid.scrollBy(scroll); reorderAtPointer() }
            delay(24)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 2.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhotoAlbum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (selectionMode && uiState.selectedAssetIds.isNotEmpty()) stringResource(R.string.organize_selected, uiState.selectedAssetIds.size)
                    else titleOverride ?: stringResource(R.string.swipe_add_to_album), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) onSearchQueryChange("") }) {
                    Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, stringResource(R.string.swipe_add_to_album_placeholder))
                }
                TextButton(onClick = onCreateAlbum, enabled = enabled && !uiState.isLoadingAlbums) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.organize_new_album))
                }
            }
            if (showSearch) {
                OutlinedTextField(value = uiState.albumSearchQuery, onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    placeholder = { Text(stringResource(R.string.swipe_add_to_album_placeholder)) },
                    singleLine = true, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(rowHeight * 3 + 8.dp), contentAlignment = Alignment.Center) {
                // Fit complete columns on screen; leave horizontal scrolling for additional albums.
                val columns = (maxWidth / (88.dp * fontScale)).toInt().coerceIn(2, 6)
                val albumWidth = (maxWidth - 4.dp * (columns - 1)) / columns
                when {
                    uiState.isLoadingAlbums && albums.isEmpty() -> CircularProgressIndicator(Modifier.size(28.dp))
                    albums.isEmpty() -> Text(stringResource(if (uiState.availableAlbums.isEmpty()) R.string.organize_no_albums else R.string.organize_no_matches),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyHorizontalGrid(rows = GridCells.Fixed(3), state = grid,
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize().pointerInput(reorderEnabled) {
                            if (!reorderEnabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { position ->
                                    val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { i -> position.x >= i.offset.x && position.x < i.offset.x + i.size.width && position.y >= i.offset.y && position.y < i.offset.y + i.size.height }
                                    if (item != null) {
                                        draggedId = item.key as String
                                        pointer = position
                                        grabOffset = position - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                                    }
                                },
                                onDrag = { change, amount -> change.consume(); pointer += amount; reorderAtPointer() },
                                onDragEnd = { draggedId = null; saveOrder() },
                                onDragCancel = { draggedId = null; saveOrder() }
                            )
                        }) {
                        items(albums, key = { it.id }) { album ->
                            val dragging = draggedId == album.id
                            val item by remember(dragging, album.id) { derivedStateOf {
                                if (dragging) grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == album.id } else null
                            } }
                            val earlier = stringResource(R.string.organize_move_earlier)
                            val later = stringResource(R.string.organize_move_later)
                            Surface(onClick = { if (enabled && draggedId == null) onAlbumSelected(album) },
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled || dragging) 1f else 0.55f),
                                shape = RoundedCornerShape(8.dp), shadowElevation = if (dragging) 8.dp else 0.dp,
                                modifier = Modifier.width(albumWidth).fillMaxHeight().zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer {
                                        val position = item
                                        if (dragging && position != null) {
                                            translationX = pointer.x - grabOffset.x - position.offset.x
                                            translationY = pointer.y - grabOffset.y - position.offset.y
                                            scaleX = 1.04f; scaleY = 1.04f
                                        }
                                    }.semantics {
                                        customActions = if (!reorderEnabled) emptyList() else listOf(
                                            CustomAccessibilityAction(earlier) {
                                                val index = currentAlbums.indexOfFirst { it.id == album.id }
                                                if (index > 0) { reorder(album.id, currentAlbums[index - 1].id); saveOrder(); true } else false
                                            },
                                            CustomAccessibilityAction(later) {
                                                val index = currentAlbums.indexOfFirst { it.id == album.id }
                                                if (index in 0 until currentAlbums.lastIndex) { reorder(album.id, currentAlbums[index + 1].id); saveOrder(); true } else false
                                            })
                                    }) {
                                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AlbumCover(album, Modifier.size(26.dp))
                                    Text(album.albumName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp).weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 20.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    busy -> if (uploadProgress != null) LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    uiState.albumActionError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(albumErrorMessage(uiState.albumActionError)), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (uiState.albumActionError == AlbumActionError.LOAD) TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    }
                    uiState.lastAddedAlbumName != null -> Text(stringResource(R.string.organize_added_to, uiState.lastAddedAlbumName),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> Text(hintOverride ?: stringResource(if (selectionMode) R.string.organize_grid_hint else R.string.organize_drag_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onUndo, enabled = !busy && uiState.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.organize_undo))
                }
                Spacer(Modifier.weight(1f))
                if (!selectionMode) TextButton(onClick = onSkip, enabled = enabled) {
                    Text(stringResource(R.string.organize_next_photo)); Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.Forward, null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun AlbumCover(album: Album, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = SessionManager.getBaseUrl()?.trimEnd('/')
    val key = SessionManager.getApiKey()
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.PhotoAlbum, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        if (album.albumThumbnailAssetId != null) AsyncImage(
            model = remember(url, key, album.albumThumbnailAssetId) { ImageRequest.Builder(context)
                .data("$url/api/assets/${album.albumThumbnailAssetId}/thumbnail?format=WEBP")
                .addHeader("x-api-key", key.orEmpty()).crossfade(true).build() },
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun albumErrorMessage(error: AlbumActionError): Int = when (error) {
    AlbumActionError.LOAD -> R.string.organize_load_error
    AlbumActionError.ADD -> R.string.organize_add_error
    AlbumActionError.CREATE -> R.string.organize_create_error
    AlbumActionError.UNDO -> R.string.organize_undo_error
    AlbumActionError.ORDER -> R.string.organize_order_error
}

@Composable
fun CreateAlbumDialog(
    isSaving: Boolean,
    error: AlbumActionError?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
        title = { Text(stringResource(R.string.organize_create_and_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.organize_album_name)) },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isSaving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                if (error != null) Text(stringResource(albumErrorMessage(error)), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = !isSaving && name.isNotBlank()) {
                Text(stringResource(R.string.organize_create_and_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
