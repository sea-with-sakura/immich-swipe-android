package com.minos2020.immichswipe.feature.local.transfer

import android.app.ActivityOptions
import android.app.Activity
import java.lang.ref.WeakReference
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.minos2020.immichswipe.feature.local.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object LocalMediaDeletion {
    private var host = WeakReference<Activity>(null)
    private val pending = mutableMapOf<Int, CompletableDeferred<Boolean>>()
    private val prepareMutex = Mutex()
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var nextRequest = 3300
    fun prepareInBackground(context: Context, photos: List<LocalMedia>) {
        preparationScope.launch { prepare(context.applicationContext, photos) }
    }
    fun result(code: Int, result: Int): Boolean {
        val waiting = pending.remove(code) ?: return false
        waiting.complete(result == Activity.RESULT_OK); return true
    }
    fun hasWriteAccess(context: Context, photo: LocalMedia): Boolean = context.checkUriPermission(
        Uri.parse(photo.uri), Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun canPrepare(context: Context): Boolean = allowed(context) && (Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
    suspend fun prepare(context: Context, photos: List<LocalMedia>) = prepareMutex.withLock {
        if (Build.VERSION.SDK_INT < 31 || !canPrepare(context)) return@withLock
        for (chunk in photos.filterNot { hasWriteAccess(context, it) }.chunked(200)) {
            val completion = withContext(Dispatchers.Main) {
                val activity = host.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                if (activity !is LifecycleOwner || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) null
                else {
                    val request = nextRequest++
                    val future = CompletableDeferred<Boolean>()
                    pending[request] = future
                    runCatching {
                        val write = MediaStore.createWriteRequest(context.contentResolver, chunk.map { Uri.parse(it.uri) })
                        activity.startIntentSenderForResult(write.intentSender, request, null, 0, 0, 0)
                    }.onFailure { pending.remove(request); future.complete(false) }
                    request to future
                }
            } ?: return@withLock
            try {
                if (withTimeoutOrNull(1_000) { completion.second.await() } != true) return@withLock
            } finally { withContext(Dispatchers.Main) { pending.remove(completion.first) } }
            // Results precede onResume. Let the next chunk use the same resumed host.
            withTimeoutOrNull(500) {
                while (!withContext(Dispatchers.Main) { (host.get() as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true }) delay(20)
            }
        }
    }
    fun attach(activity: Activity) { host = WeakReference(activity) }
    fun detach(activity: Activity) {
        if (host.get() === activity) { host.clear(); pending.values.forEach { it.complete(false) }; pending.clear() }
    }

    fun allowed(context: Context): Boolean {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 31) MediaStore.canManageMedia(context) && granted(Manifest.permission.ACCESS_MEDIA_LOCATION) &&
            (if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
            else granted(Manifest.permission.READ_EXTERNAL_STORAGE))
        else Build.VERSION.SDK_INT <= 28 && granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @Suppress("DEPRECATION")
    suspend fun delete(context: Context, photo: LocalMedia): Boolean = withContext(Dispatchers.IO) {
        if (!allowed(context)) return@withContext false
        val uri = Uri.parse(photo.uri)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT <= 28) return@withContext resolver.delete(uri, null, null) > 0
        // The silent write grant was acquired while sorting. It permits deletion
        // from the worker with no Activity, even when another app is foreground.
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
            return@withContext resolver.delete(uri, null, null) > 0
        if (Build.VERSION.SDK_INT >= 31) {
            // MANAGE_MEDIA suppresses the platform UI. Do not send without that grant.
            val request = MediaStore.createDeleteRequest(resolver, listOf(uri))
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(if (Build.VERSION.SDK_INT >= 36)
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            // The provider requires a real caller Activity for getCallingPackage().
            // A stopped Activity cannot execute this request until it resumes.
            // Defer this fallback until the caller is in the foreground.
            val dispatched = withContext(Dispatchers.Main) {
                val activity = host.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                if (activity !is LifecycleOwner || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !allowed(context)) false else runCatching {
                    activity.startIntentSenderForResult(request.intentSender, 3202, null, 0, 0, 0, options)
                    true
                }.getOrDefault(false)
            }
            if (!dispatched) return@withContext false
            // Starting the request confirms dispatch, not deletion. Check the actual MediaStore.
            return@withContext withTimeoutOrNull(15_000) {
                while (true) {
                    if (!allowed(context)) return@withTimeoutOrNull false
                    val gone = resolver.query(uri, arrayOf("_id"), null, null, null)?.use { !it.moveToFirst() } == true
                    if (gone) return@withTimeoutOrNull true
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
        }
        false
    }
}
