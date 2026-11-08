package com.minos2020.immichswipe.feature.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.minos2020.immichswipe.R
import com.minos2020.immichswipe.feature.swipe.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    Build.VERSION.SDK_INT <= 28 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
private fun hasMediaAccess(context: Context): Boolean = mediaPermissions().filter { it != Manifest.permission.WRITE_EXTERNAL_STORAGE }
    .any { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
private fun openPhotoSettings(context: Context) {
    context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalOrganizerScreen(
    viewModel: LocalOrganizerViewModel,
    albums: SwipeUiState,
    cloudOrganizer: SwipeViewModel,
    swipeMode: Boolean,
    jumpDate: Boolean,
    closeDatePicker: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(hasMediaAccess(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = hasMediaAccess(context); if (access) viewModel.refresh()
    }
    LaunchedEffect(Unit) { if (access) viewModel.refresh() else permission.launch(mediaPermissions()) }
    val busy by rememberUpdatedState(state.busy)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && !busy) {
            access = hasMediaAccess(context); if (access) viewModel.refresh()
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.refreshVersion, state.oldestFirst) { grid.requestScrollToItem(0) }
    val photos = remember(state.items, state.kept, state.showKept, state.oldestFirst) { state.visible }
    val current = photos.getOrNull(state.currentIndex)
    val groups = remember(photos) { photos.groupBy { it.date } }
    val loader = remember(context) { ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build() }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    var fullscreen by remember { mutableStateOf<LocalMedia?>(null) }
    if (!access) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.local_permission_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.local_permission_body), Modifier.padding(vertical = 16.dp))
            Button(onClick = { permission.launch(mediaPermissions()) }) { Text(stringResource(R.string.local_grant)) }
            TextButton(onClick = { openPhotoSettings(context) }) { Text(stringResource(R.string.local_settings)) }
        }
    } else Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (state.showKept) R.string.local_kept_title else R.string.local_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.organize_remaining, photos.size), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = viewModel::toggleKept, enabled = !state.busy) {
                Text(stringResource(if (state.showKept) R.string.local_pending else R.string.local_kept,
                    state.items.count { it.key in state.kept }))
            }
            IconButton(onClick = viewModel::toggleSort, enabled = !state.busy) {
                Icon(if (state.oldestFirst) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    stringResource(if (state.oldestFirst) R.string.organize_oldest else R.string.organize_newest))
            }
            IconButton(onClick = {
                val full = (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)).all {
                    ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (full) openPhotoSettings(context) else permission.launch(mediaPermissions())
            }, enabled = !state.busy) {
                Icon(Icons.Default.PermMedia, stringResource(R.string.local_access))
            }
        }
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
            if (photos.isEmpty() || (swipeMode && current == null)) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.local_empty), Modifier.padding(12.dp))
                    TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.common_retry)) }
                }
            } else if (swipeMode) {
                current?.let { photo ->
                    var drag by remember(photo.key) { mutableFloatStateOf(0f) }
                    // Keep touch coordinates stable while the card follows the finger.
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                        .pointerInput(photo.key, state.busy) {
                            if (!state.busy) detectHorizontalDragGestures(
                                onHorizontalDrag = { change, delta -> change.consume(); drag += delta },
                                onDragCancel = { drag = 0f }, onDragEnd = {
                                    val delta = drag; drag = 0f
                                    if (abs(delta) > 120f) { if (delta > 0) viewModel.keep(false) else viewModel.delete(false) }
                                })
                        }) {
                        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxSize()
                            .graphicsLayer { translationX = drag; rotationZ = drag / 80f }) {
                            Box(Modifier.fillMaxSize()) {
                                LocalPreview(photo, fullscreen == null, Modifier.fillMaxSize())
                                Text(photo.date, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                    .background(Color.Black.copy(alpha = .35f), RoundedCornerShape(10.dp)).padding(8.dp),
                                    color = Color.White, style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { fullscreen = photo }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                    .background(Color.Black.copy(alpha = .35f), RoundedCornerShape(50))) {
                                    Icon(Icons.Default.Fullscreen, stringResource(R.string.local_fullscreen), tint = Color.White)
                                }
                            }
                        }
                    }
                }
            } else LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEach { (date, items) ->
                    item(key = "local-date:$date", span = { GridItemSpan(maxLineSpan) }) {
                        val keys = items.map { it.key }.toSet()
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { viewModel.select(keys) }, verticalAlignment = Alignment.CenterVertically) {
                            Text(localDateLabel(date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${items.size}", style = MaterialTheme.typography.bodySmall)
                            Checkbox(checked = state.selected.containsAll(keys), onCheckedChange = { viewModel.select(keys) }, enabled = !state.busy)
                        }
                    }
                    items(items, key = { it.key }) { photo ->
                        val selected = photo.key in state.selected
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable(enabled = !state.busy) { viewModel.toggle(photo.key) }) {
                            AsyncImage(model = Uri.parse(photo.uri), imageLoader = loader, contentDescription = photo.name,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (selected) 4.dp else 0.dp))
                            if (selected) Icon(Icons.Default.CheckCircle, stringResource(R.string.organize_photo_selected), tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)))
                            if (photo.isVideo) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp))
                        }
                    }
                }
            }
        }
        if (state.message != null || state.uploading || state.pendingDeletion != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.uploading -> stringResource(R.string.local_progress, state.uploadIndex, state.uploadTotal)
                state.pendingDeletion != null -> stringResource(R.string.local_preparing_delete)
                else -> stringResource(localMessage(checkNotNull(state.message))) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (state.uploading || state.pendingDeletion != null) TextButton(onClick = {
                if (state.uploading) viewModel.cancelUpload() else viewModel.cancelDeletion()
            }) { Text(stringResource(R.string.common_cancel)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val enabled = !state.busy && (if (swipeMode) current != null else state.selected.isNotEmpty())
            TextButton(onClick = { viewModel.delete(!swipeMode) }, enabled = enabled) {
                Icon(Icons.Default.DeleteOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.local_delete))
            }
            if (!swipeMode && state.selected.isNotEmpty()) IconButton(onClick = viewModel::clearSelection, enabled = !state.busy) {
                Icon(Icons.Default.Deselect, stringResource(R.string.organize_clear_selection))
            }
            TextButton(onClick = { viewModel.keep(!swipeMode) }, enabled = enabled) {
                Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(if (state.showKept) R.string.local_reconsider else R.string.local_keep))
            }
        }
        val panel = albums.copy(
            assets = listOfNotNull(current?.let { com.minos2020.immichswipe.domain.model.Asset(it.key, "local", Instant.ofEpochMilli(it.takenAt).toString(), if (it.isVideo) "VIDEO" else "IMAGE") }),
            currentIndex = 0, selectedAssetIds = state.selected, albumAssignments = emptyMap(),
            history = state.undoKept.indices.map { "local-keep:$it" },
            isLoading = state.loading, isAddingToAlbum = state.busy, isSyncing = false,
            lastAddedAlbumName = null, albumActionError = albums.albumActionError.takeIf { it == AlbumActionError.LOAD || it == AlbumActionError.ORDER })
        AlbumOrganizingPanel(panel, { viewModel.upload(it, !swipeMode) }, cloudOrganizer::setAlbumSearchQuery,
            { viewModel.showCreate(true) }, cloudOrganizer::loadAvailableAlbums, viewModel::undoKeep, viewModel::next,
            cloudOrganizer::reorderAlbum, cloudOrganizer::saveAlbumOrder, selectionMode = !swipeMode,
            titleOverride = stringResource(R.string.local_upload), hintOverride = stringResource(R.string.local_upload_hint),
            uploadProgress = state.progress.takeIf { state.uploading })
        if (state.showCreateAlbum) CreateAlbumDialog(state.uploading, AlbumActionError.CREATE.takeIf { state.message == LocalMessage.TRANSFER_FAILED },
            { viewModel.upload(null, !swipeMode, it) }, { viewModel.showCreate(false) })
    }
    state.pendingDeletion?.let { LocalDeletionHandler(it, viewModel::validateDeletionChunk, viewModel::deletionFinished) }
    fullscreen?.let { photo -> Dialog(onDismissRequest = { fullscreen = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box { LocalPreview(photo, true, Modifier.fillMaxSize())
                IconButton(onClick = { fullscreen = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Icon(Icons.Default.Close, stringResource(R.string.common_cancel), tint = Color.White) }
            }
        }
    } }
    if (jumpDate) {
        val picker = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = closeDatePicker, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { time ->
                    val day = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    val selected = groups.keys.firstOrNull { if (state.oldestFirst) it >= day else it <= day } ?: groups.keys.lastOrNull()
                    var index = 0
                    for ((date, items) in groups) { if (date == selected) break; index += items.size + 1 }
                    if (groups.isNotEmpty()) scope.launch { grid.animateScrollToItem(index) }
                }
                closeDatePicker()
            }) { Text(stringResource(R.string.common_confirm)) }
        }, dismissButton = { TextButton(onClick = closeDatePicker) { Text(stringResource(R.string.common_cancel)) } }) { DatePicker(picker) }
    }
}

private fun localDateLabel(date: String) = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(date)
private fun localMessage(value: LocalMessage): Int = when (value) {
    LocalMessage.LOAD_FAILED -> R.string.local_load_failed
    LocalMessage.TRANSFER_FAILED -> R.string.local_transfer_failed
    LocalMessage.UPLOADED_NOT_DELETED -> R.string.local_not_deleted
    LocalMessage.DELETE_FAILED -> R.string.local_delete_failed
    LocalMessage.DELETED -> R.string.local_deleted
    LocalMessage.KEPT -> R.string.local_kept_message
    LocalMessage.CANCELLED -> R.string.local_cancelled
}

@AndroidOptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LocalPreview(photo: LocalMedia, active: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    if (!photo.isVideo) AsyncImage(Uri.parse(photo.uri), photo.name, modifier = modifier, contentScale = ContentScale.Fit)
    else {
        val player = remember(photo.key) { ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(photo.uri)); volume = 0f; repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE; prepare()
        } }
        DisposableEffect(player, lifecycle, active) {
            player.playWhenReady = active
            val observer = LifecycleEventObserver { _, event -> when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (active) player.play()
                else -> Unit
            } }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer); player.pause() }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } },
            update = { it.player = player }, modifier = modifier)
    }
}
