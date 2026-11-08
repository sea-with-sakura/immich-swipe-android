package com.minos2020.immichswipe.feature.local.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.minos2020.immichswipe.MainActivity
import com.minos2020.immichswipe.R
import com.minos2020.immichswipe.data.api.RetrofitFactory
import com.minos2020.immichswipe.data.repository.*
import kotlinx.coroutines.flow.first

class LocalUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = inputData.getString("account") ?: return Result.failure()
        val sessions = SessionRepository(applicationContext)
        val config = sessions.sessionConfig.first()?.takeIf { it.localAccount() == account } ?: return Result.success()
        val dao = LocalTransferDatabase.get(applicationContext).tasks()
        val localOnly = inputData.getBoolean("localOnly", false)
        // CONNECTED constraints require public Internet validation, which rejects LAN-only Immich servers.
        @Suppress("DEPRECATION")
        val connected = applicationContext.getSystemService(ConnectivityManager::class.java).activeNetworkInfo?.isConnected == true
        val hasWork = dao.pending(account).any { it.deleteOnly == localOnly &&
            (it.state != TransferState.CLEANUP || LocalMediaDeletion.allowed(applicationContext)) }
        if (!hasWork) return Result.success()
        if (!localOnly && !connected) return Result.retry()
        try { setForeground(getForegroundInfo()) } catch (e: IllegalStateException) { return Result.retry() }
        val media = LocalMediaRepository(applicationContext)
        val transfer = LocalTransferRepository(media, RetrofitFactory.create(config, uploadTimeouts = true), config)
        val retry = LocalTransferProcessor(dao, media, transfer, account,
            { sessions.sessionConfig.first() == config },
            { LocalMediaDeletion.allowed(applicationContext) },
            { LocalMediaDeletion.delete(applicationContext, it) }).run(localOnly)
        return if (retry) Result.retry() else Result.success()
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("local-uploads", context.getString(R.string.local_background_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, "local-uploads")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(if (inputData.getBoolean("localOnly", false)) R.string.local_cleanup_title else R.string.local_background_title))
            .setContentText(context.getString(R.string.local_background_body))
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(3201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(3201, notification)
    }
}
