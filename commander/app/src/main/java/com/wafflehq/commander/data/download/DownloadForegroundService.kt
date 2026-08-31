package com.wafflehq.commander.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.fraction
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "downloads"

@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject
    lateinit var downloader: HostedFileDownloader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager().createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(downloader.activeTarget.value?.identity, downloader.downloadStatus.value),
        )
        observeDownload()
        return START_NOT_STICKY
    }

    private fun observeDownload() {
        scope.launch {
            combine(downloader.activeTarget, downloader.downloadStatus) { target, status -> target to status }
                .collect { (target, status) ->
                    if (target == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(target.identity, status))
                }
        }
    }

    private fun buildNotification(identity: String?, status: DownloadStatus?): Notification {
        val phaseTextRes = when (status?.phase) {
            DownloadPhase.VERIFYING -> R.string.download_status_verifying
            DownloadPhase.INSTALLING -> R.string.download_status_installing
            DownloadPhase.OPENING -> R.string.download_status_opening
            else -> R.string.download_status_downloading
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(identity ?: getString(R.string.downloads_title))
            .setContentText(getString(phaseTextRes))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        val fraction = status?.progress?.fraction()
        if (fraction != null) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadForegroundService::class.java))
        }
    }
}
