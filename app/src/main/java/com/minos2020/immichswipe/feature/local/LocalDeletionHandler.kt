package com.minos2020.immichswipe.feature.local

import android.app.Activity
import android.app.RecoverableSecurityException
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.minos2020.immichswipe.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the platform confirmation result, or a successful resolver delete, counts as deletion. */
@Composable
fun LocalDeletionHandler(
    request: LocalDeletion,
    validate: suspend (List<LocalMedia>) -> List<LocalMedia>,
    onFinished: (Set<String>, Boolean) -> Unit
) {
    val context = LocalContext.current
    var index by remember(request.id) { mutableIntStateOf(0) }
    var deleted by remember(request.id) { mutableStateOf(emptySet<String>()) }
    var launched by remember(request.id) { mutableStateOf(emptyList<LocalMedia>()) }
    var approved by remember(request.id) { mutableStateOf(Build.VERSION.SDK_INT >= 29 || request.afterUpload) }
    var retry by remember(request.id) { mutableIntStateOf(0) }
    val chunks = remember(request.id) { request.items.chunked(if (Build.VERSION.SDK_INT >= 30) 200 else 1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) onFinished(deleted, true)
        else if (Build.VERSION.SDK_INT >= 30) {
            deleted = deleted + launched.map { it.key }; index++
        } else retry++
    }
    if (!approved) AlertDialog(onDismissRequest = { onFinished(deleted, true) },
        title = { Text(stringResource(R.string.local_delete)) },
        text = { Text(stringResource(R.string.local_delete_confirm, request.items.size)) },
        confirmButton = { TextButton(onClick = { approved = true }) { Text(stringResource(R.string.local_delete)) } },
        dismissButton = { TextButton(onClick = { onFinished(deleted, true) }) { Text(stringResource(R.string.common_cancel)) } })
    LaunchedEffect(request.id, index, approved, retry) {
        if (!approved) return@LaunchedEffect
        if (index >= chunks.size) { onFinished(deleted, deleted.size != request.items.size); return@LaunchedEffect }
        try {
            launched = validate(chunks[index])
            if (launched.isEmpty()) { index++; return@LaunchedEffect }
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = MediaStore.createDeleteRequest(context.contentResolver, launched.map { Uri.parse(it.uri) }).intentSender
                launcher.launch(IntentSenderRequest.Builder(intent).build())
            } else {
                val item = launched.single()
                try {
                    val count = withContext(Dispatchers.IO) { context.contentResolver.delete(Uri.parse(item.uri), null, null) }
                    if (count > 0) deleted = deleted + item.key
                    index++; retry = 0
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException && retry == 0) {
                        launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                    } else onFinished(deleted, true)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            onFinished(deleted, true)
        }
    }
}
